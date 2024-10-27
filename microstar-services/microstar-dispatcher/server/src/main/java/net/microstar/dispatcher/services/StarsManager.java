package net.microstar.dispatcher.services;

import com.google.common.collect.ImmutableList;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.CollectionUtils;
import net.microstar.common.util.Threads;
import net.microstar.common.util.TimedRunner;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.StarProperties;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.function.Predicate.not;
import static net.microstar.common.MicroStarConstants.HEADER_X_STAR_NAME;
import static net.microstar.common.MicroStarConstants.HEADER_X_STAR_TARGET;
import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.Caching.cache;
import static net.microstar.common.util.Caching.clearCache;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ThreadUtils.cancelDebounce;
import static net.microstar.common.util.ThreadUtils.debounce;

/**
 * Keeps track of stars as configured in app.config.dispatcher.stars and
 * provides functionality to call services on a specific or all stars.<p>
 *
 * A 'star' is a MicroStar instance (dispatcher + services) on a
 * host and port.
 */
@Slf4j
@Component
public class StarsManager {
    private static final String UPDATE_THREAD_ID = "Stars.DelayCheckInterval";
    private static final String UPDATE_ACTIVE_STARS_ID = "StarsManager.updateActiveStars";
    private static final String CACHE_ID = "StarsManager.cache";
    public static final String DEFAULT_LOCAL_STAR_NAME = "main";
    public static final String FIRST_AVAILABLE_STAR = "first-available-star";
    private final DynamicPropertiesRef<DispatcherProperties> propsRef;
    private final EventEmitter                eventEmitter;
    private final AtomicReference<Star>       localStar = new AtomicReference<>(Star.builder().name(DEFAULT_LOCAL_STAR_NAME).url("").build());
    private final AtomicReference<List<Star>> stars = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<List<Star>> activeStarsRef = new AtomicReference<>(Collections.emptyList());
    private final Map<String,Long>            lastStarActivity = Collections.synchronizedMap(new HashMap<>());
    private       Duration                    maxActivityAgo = Duration.ofSeconds(10);
    private final List<Consumer<Star>>        onAddedStarListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Star>>        onRemovedStarListeners = new CopyOnWriteArrayList<>();


    @SuppressWarnings("this-escape")
    public StarsManager(WebClient.Builder webClientBuilder, EventEmitter eventEmitter) {
        this.eventEmitter = eventEmitter;
        propsRef = DynamicPropertiesRef.of(DispatcherProperties.class)
            .onChange(props -> {
                maxActivityAgo = Duration.ofMillis(props.stars.aliveCheckInterval.toMillis() * 2);
                updateStarsForNewProperties(props, webClientBuilder);
                setStarIsActive(getLocalStar(), true);
                periodicallyCheckStarConnections(props.stars.aliveCheckInterval);
                updateActiveStarsNow();
            });
        propsRef.callOnChangeHandlers(); // handlers use propsRef so cannot be chained to previous statement
    }

    public void cleanup() {
        TimedRunner.cancel(UPDATE_THREAD_ID, /*wait=*/true, /*interrupt=*/true);
        cancelDebounce(UPDATE_ACTIVE_STARS_ID);
        propsRef.removeChangeListeners();
        clearCache(CACHE_ID);
    }

    public void refresh() {
        cancelDebounce(UPDATE_ACTIVE_STARS_ID);
        lastStarActivity.clear();
        setStarIsActive(getLocalStar(), true);
        callStarsToCheckIfActive();
    }

    public StarsManager onAddedStar  (Consumer<Star> callback) {   onAddedStarListeners.add(callback); return this; }
    public StarsManager onRemovedStar(Consumer<Star> callback) { onRemovedStarListeners.add(callback); return this; }

    private void   callOnAddedStar  (Star addedStar)   {   onAddedStarListeners.forEach(consumer -> consumer.accept(addedStar)); }
    private void   callOnRemovedStar(Star removedStar) { onRemovedStarListeners.forEach(consumer -> consumer.accept(removedStar)); }

    public Optional<Star> getStar(String name) {
        if(FIRST_AVAILABLE_STAR.equals(name)) return getActiveStar(name);
        return getStars().stream()
            .filter(star -> star.name.equals(name))
            .findFirst();
    }
    public List<Star> getStars() { return stars.get(); }

    public Optional<Star> getActiveStar(String name) {
        if(FIRST_AVAILABLE_STAR.equals(name)) return getActiveStar(getFirstAvailableStarName());
        return getActiveStars().stream()
            .filter(star -> star.name.equals(name))
            .findFirst();
    }
    public List<Star> getActiveStars() { return activeStarsRef.get(); }
    public Star getLocalStar() { return localStar.get(); }

    public boolean isLocal(String name) {
        return localStar.get().name.equals(name);
    }
    public boolean isActive(String name) {
        return activeStarsRef.get().stream().anyMatch(star -> star.name.equals(name));
    }
    public String getFirstAvailableStarName() {
        return activeStarsRef.get().stream().map(s->s.name).findFirst().orElseGet(() -> getLocalStar().name);
    }

    public boolean isForOtherStar(ServerHttpRequest request) {
        if(stars.get().size() <= 1) return false; // no extra stars configured? Then don't support star-targets
        final @Nullable String targetStarName = request.getHeaders().getFirst(HEADER_X_STAR_TARGET);
        return targetStarName != null && !targetStarName.equals(localStar.get().name);
    }
    public Optional<Star> getTargetStar(ServerHttpRequest request) {
        @Nullable String targetStarName = request.getHeaders().getFirst(HEADER_X_STAR_TARGET);
        if(FIRST_AVAILABLE_STAR.equals(targetStarName)) targetStarName = getFirstAvailableStarName();
        if(targetStarName == null || targetStarName.equals(localStar.get().name)) return Optional.empty();
        return getActiveStar(targetStarName);
    }

    /**
     *  Don't use the relay methods directly from here -- use the Dispatcher facade class instead.
     *  It will do type conversion, this is all raw text because types are not yet known
     *  here. The Dispatcher facade will do the conversion using an ObjectMapper from the text.
     */

    public Flux<RelayResponse<String>> relay(RelayRequest relayRequest) {
        return getCallStars(relayRequest)
            .flatMap(star -> relay(star, relayRequest))
        ;
    }

    public Mono<RelayResponse<String>> relaySingle(RelayRequest relayRequest) {
        return getCallStars(relayRequest)
            .flatMap(star -> relay(star, relayRequest), 1) // async of 1 or still multiple requests will be done when first is successful
            .take(1, true /*subscribe only to first instead of subscribing to all and later just use one*/)
            .next()
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("No stars to call")))
            ;
    }

    public Mono<RelayResponse<String>> relay(Star star, RelayRequest relayRequest) {
        return getResponseFor(star, relayRequest).toEntity(String.class)
            .map(entity -> this.<String>responseBuilder(star)
                .status((HttpStatus)entity.getStatusCode())
                .content(Optional.ofNullable(entity.getBody()))
                .build())
            .onErrorResume(error ->
                error instanceof WebClientResponseException responseException
                    ? Mono.just(this.<String>responseBuilder(star).status((HttpStatus)responseException.getStatusCode()).build())
                    : Mono.just(this.<String>responseBuilder(star).failed())
            );
    }

    public Optional<WebClient.ResponseSpec> getResponseFor(String starName, RelayRequest relayRequest) {
        return getActiveStars().stream()
            .filter(star -> starName.equals(star.name))
            .findFirst()
            .map(star -> getResponseFor(star, relayRequest));
    }
    public WebClient.ResponseSpec getResponseFor(Star star, RelayRequest relayRequest) {
        if(relayRequest.methodAsHttpMethod() == HttpMethod.GET && relayRequest.payload != null) {
            throw new IllegalArgumentException("Reactor WebClient does not support payload on http GET");
        }
        final String uri = concatPath(relayRequest.serviceName, relayRequest.servicePath).replace(" ", "%20").replace("+", "%2B");
        final MultiValueMap<String,String> queryParamsMap = new LinkedMultiValueMap<>();
        relayRequest.params.forEach(queryParamsMap::add);
        final Function<UriBuilder, URI> addQueryParams = ub -> ub.queryParams(queryParamsMap).build();

        return (relayRequest.methodAsHttpMethod() == HttpMethod.GET
            ? star.webClient.get().uri(uri, addQueryParams)
            : star.webClient.method(relayRequest.methodAsHttpMethod()).uri(uri, addQueryParams)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(relayRequest.payload == null ? "" : relayRequest.payload)
            )
            .headers(headers -> {
                headers.set(HEADER_X_STAR_NAME, localStar.get().name);
                headers.set(HEADER_X_STAR_TARGET, star.name);
                if(relayRequest.userToken != null) {
                    headers.set(UserToken.HTTP_HEADER_NAME, relayRequest.userToken);
                }
            })
            .retrieve();
    }

    public Mono<List<RelayResponse<String>>> sendEventToOtherStars(String serviceName, String servicePath) { return sendEventToOtherStars(serviceName, servicePath, null); }
    public Mono<List<RelayResponse<String>>> sendEventToOtherStars(String serviceName, String servicePath, @Nullable Object payload) {
        return relay(RelayRequest.builder()
            .excludeLocalStar()
            .method(HttpMethod.POST)
            .serviceName(serviceName)
            .servicePath(servicePath)
            .payload(payload)
            .build()
        ).collectList();
    }

    public <T> RelayResponse.RelayResponseBuilder<T> responseBuilder(Star star) {
        return RelayResponse.<T>builder()
            .starName(star.name)
            .starUrl(star.url)
            ;
    }

    private void updateStarsForNewProperties(DispatcherProperties props, WebClient.Builder webClientBuilder) {
        final Star thisStar = Optional.ofNullable(localStar.get())
            .filter(star -> !star.url.isEmpty())
            .orElseGet(() -> createLocalStar(props, webClientBuilder));
        final List<Star> configuredStars = props.stars.instances.stream().map(starProps -> Star.from(starProps, webClientBuilder)).toList();
        final List<Star> newStars = configuredStars.stream().anyMatch(star -> isStarLocalStar(star.url, props.url))
                ? configuredStars
                : CollectionUtils.concat(List.of(thisStar), configuredStars);
        stars.set(ImmutableList.copyOf(newStars));
        localStar.set(stars.get().stream()
            .filter(star -> isStarLocalStar(star.url, props.url))
            .findFirst()
            .orElseGet(() -> createLocalStar(props, webClientBuilder))
        );
    }
    private Star createLocalStar(DispatcherProperties props, WebClient.Builder webClientBuilder) {
        return Star.from(
            StarProperties.builder()
                .url(props.url)
                .name(DEFAULT_LOCAL_STAR_NAME)
                .build(), webClientBuilder);
    }
    private void periodicallyCheckStarConnections(Duration aliveCheckInterval) {
        // In tests make sure to call cleanup() when the StarsManager is not mocked or
        // otherwise the stars will be continued to get probed via the TimedRunner
        // (which will interfere with MockServers)
        TimedRunner.runPeriodicallyAtFixedDelay(UPDATE_THREAD_ID, Duration.ZERO, aliveCheckInterval, this::callStarsToCheckIfActive);
    }
    private void updateActiveStars() { debounce(UPDATE_ACTIVE_STARS_ID, Duration.ofMillis(100), this::updateActiveStarsNow); }
    private void updateActiveStarsNow() {
        final List<Star> oldActiveStars = activeStarsRef.get();
        activeStarsRef.set(ImmutableList.copyOf(getStars().stream().filter(star -> isActive(star) || isLocal(star.name)).toList()));
        final List<Star> newActiveStars = activeStarsRef.get();

        if (!oldActiveStars.equals(newActiveStars) && (getStars().size() > 1 || oldActiveStars.size() > 1)) {
            newActiveStars.stream()
                .filter(not(oldActiveStars::contains))
                .peek(star -> log.info("Star connected: {}", star))
                .forEach(this::callOnAddedStar);
            oldActiveStars.stream()
                .filter(not(newActiveStars::contains))
                .peek(star -> log.info("Star disconnected: {}", star))
                .forEach(this::callOnRemovedStar);

            eventEmitter.next("STARS-CHANGED");

            log.info("State of one or more stars changed\nStars:\n{}", getStars().stream()
                .map(star -> "- " + star.name + "::" + star.url + (isActive(star) ? " ON" : " OFFLINE") + (getLocalStar().name.equals(star.name) ? " <-- THIS STAR" : ""))
                .collect(Collectors.joining("\n"))
            );
        }
    }
    private Flux<Star> getCallStars(RelayRequest relayRequest) {
        return Flux.fromStream(getActiveStars().stream())
            .filter(star -> relayRequest.includeLocalStar || !star.name.equals(localStar.get().name))
            .filter(star -> relayRequest.star == null
                         || relayRequest.star.equals(star.name)
                         || (RelayRequest.LOCAL_STAR_REF_NAME.equals(relayRequest.star) && star.name.equals(localStar.get().name))
            )
            ;
    }
    private void callStarsToCheckIfActive() {
        getStars().forEach(star ->
            // Run each check in its own thread as a check can take some time (like 'no response' after 30s)
            Threads.execute(() -> callStarToCheckIfActive(star))
        );
    }
    private void callStarToCheckIfActive(Star star) {
        try {

            // No need to check local star (which has the potential of going wrong if the gateway is borked)
            if(localStar.get().url.equals(star.url)) {
                setStarIsActive(star, true);
                return;
            }

            star.webClient.get().uri("version").retrieve()
                .onStatus(HttpStatusCode::isError, resp -> Mono.just(new IllegalStateException(resp.statusCode().toString())))
                .bodyToMono(String.class)
                .doOnSuccess(s -> setStarIsActive(star, true))
                .doOnError(e -> setStarIsActive(star, false))
                .block();
        } catch(final Exception ignored) {
            // Not interested in why the star is not reachable
            // This may also be an InterruptedException when shutting down while doing a check
        }
    }
    private void setStarIsActive(Star star, boolean set) {
        if(set) {
            final long prevActivityTime = lastActivityTime(star);
            lastStarActivity.put(star.url, now());
            if(ago(prevActivityTime) > maxActivityAgo.toMillis() / 2) updateActiveStars();
        } else {
            lastStarActivity.remove(star.url);
        }
        updateActiveStars();
    }
    public boolean isActive(Star star)         { return lastActivityAgo(star) < maxActivityAgo.toMillis(); }
    private long   lastActivityTime(Star star) { return Optional.ofNullable(lastStarActivity.get(star.url)).orElse(0L); }
    private long   lastActivityAgo(Star star)  { return ago(lastActivityTime(star)); }
    private static long now() { return System.currentTimeMillis(); }
    private static long ago(long time) { return now() - time; }
    private static boolean isStarLocalStar(String starUrl, String dispatcherUrl) {
        // starUrl is a host (which may or may not be 'localhost') and a port
        // dispatcherUrl is always localhost (even if not 'localhost') and a port
        //
        // so both point to the same target if:
        // - starUrl is a local address
        // - port of both is the same
        //
        return cache(CACHE_ID, starUrl, () -> noThrow(() -> {
            final URL starUrlObj          = URI.create(starUrl).toURL();
            final URL dispatcherUrlObj    = URI.create(dispatcherUrl).toURL();
            final int starPort            = Optional.of(starUrlObj.getPort()      ).filter(port -> port > 0).orElse(80);
            final int dispatcherPort      = Optional.of(dispatcherUrlObj.getPort()).filter(port -> port > 0).orElse(80);
            final InetAddress starAddress = InetAddress.getByName(starUrlObj.getHost());

            final boolean starIsLocal = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(NetworkInterface::inetAddresses)
                .anyMatch(addr -> addr.equals(starAddress));

            return starIsLocal && starPort == dispatcherPort;
        }).orElse(false));
    }
}
