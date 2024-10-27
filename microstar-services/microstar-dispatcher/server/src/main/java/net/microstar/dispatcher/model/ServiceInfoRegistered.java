package net.microstar.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.StringUtils;
import net.microstar.dispatcher.IsAliveConnection;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.TimedCounters;
import net.microstar.spring.application.ConnectionChecker;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter.EventDataSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = true)
public class ServiceInfoRegistered extends ServiceInfo implements EventDataSource {
    private static final DynamicPropertiesRef<DispatcherProperties> dispatcherProps = DynamicPropertiesRef.of(DispatcherProperties.class);
    private static final Duration IS_ALIVE_INIT_MAX_DURATION = Duration.ofDays(36500); // no longer mandatory so max wait forever for init
    public final int isAlivePort;
    public final String baseUrl;
    public final InetSocketAddress address;
    @EqualsAndHashCode.Include
    public final UUID serviceInstanceId;
    public final long startTime;
    @JsonIgnore
    public final WebClient webClient;
    @JsonIgnore
    public final IsAliveConnection isAliveConnection; // this one has internal state and needs closing after use
    @JsonIgnore
    public final ConnectionChecker connectionChecker;
    @JsonIgnore
    private final TimedCounters callCounter = new TimedCounters();

    @SuppressWarnings("this-escape")
    public ServiceInfoRegistered(ServiceId id, UUID instanceId, long startTime, String protocol, Optional<JarInfo> jarInfo, // NOSONAR -- paramCount
                                 Optional<String> url, InetSocketAddress address, WebClient.Builder webClientBuilder,
                                 Consumer<ServiceInfoRegistered> whenDisconnected) {
        super(id, jarInfo);
        this.address           = address;
        this.serviceInstanceId = instanceId;
        this.startTime         = startTime;
        this.baseUrl           = url.orElseGet(() -> protocol + "://" + Optional.ofNullable(address.getAddress()).map(InetAddress::getHostAddress).orElse("localhost").replaceFirst("^/","") + ":" + address.getPort());

        // The isAliveConnection is replaced by the IsAliveChecker
        // The isAliveConnection is deprecated but stays on for a bit so the Dispatcher is compatible with older services
        // At some point the isAliveConnection and the aliveConnectionPort from the register response should be removed

        this.isAliveConnection = new IsAliveConnection(IS_ALIVE_INIT_MAX_DURATION, dispatcherProps.get().services.aliveCheckInterval, "IsAlive." + id)
            .whenConnectionIsLost(() -> { log.info("Old connection lost with " + id + "/" + serviceInstanceId); whenDisconnected.accept(this); });
        log.info("Registered: id={} given url={} baseUrl={}", id, url, this.baseUrl);
        if(!id.equals(ServiceId.get()) && url.isEmpty()) { // Prevent Dispatcher checking Dispatcher
            this.isAliveConnection.start();
            this.isAlivePort   = isAliveConnection.getAddress().getPort();
        } else {
            this.isAlivePort   = 0;
        }

        final DefaultUriBuilderFactory uriFactory = new DefaultUriBuilderFactory(baseUrl);
        uriFactory.setEncodingMode(EncodingMode.NONE); // Keep the URI as-is (e.g. don't expand %codes)
        this.webClient         = webClientBuilder
            .uriBuilderFactory(uriFactory)
            .defaultHeaders(headers -> {
                url.flatMap(u -> StringUtils.getRegexGroup(u, "^.*?://([^/:?]+)[/:?].*$")).ifPresent(host -> headers.put("Host", List.of(host)));
            })
            .build();

        this.connectionChecker = new ConnectionChecker(isConnectedSupplier());
        this.connectionChecker.whenDisconnected(() -> {
            connectionChecker.stop();
            log.info("Connection lost with " + id + "/" + serviceInstanceId);
            whenDisconnected.accept(this);
        });

        if(!id.equals(ServiceId.get())) this.connectionChecker.start(); // Prevent Dispatcher checking Dispatcher
    }

    public String toString() {
        return "ServiceInfoRegistered(id:" + id.combined + "; baseUrl:" + baseUrl + "; serviceInstanceId:" + serviceInstanceId + ";#calls:" + callCounter.getCountInLast(Duration.ofDays(100) )+ ")";
    }

    public void cleanup() {
        isAliveConnection.stop();
        connectionChecker.stop();
    }

    public Mono<Void> tellServiceToStop() {
        log.info("STOP: {}: {}", id, serviceInstanceId);
        return webClient
            .method(HttpMethod.POST)
            .uri("stop")
            .headers(headers -> MicroStarApplication.get().ifPresent(app -> app.setHeaders(headers)))
            .retrieve()
            .toBodilessEntity()
            .then();
    }

    /** Websocket data */
    @Override public Map<String,Object> getEventData() {
        return Map.of("id", id, "instanceId", serviceInstanceId);
    }

    public void called() {
        callCounter.increase();
    }

    public int getCallCountInLast(Duration duration) {
        return callCounter.getCountInLast(duration);
    }

    private Supplier<Boolean> isConnectedSupplier() {
        return () -> webClient
            .get()
            .uri(IOUtils.concatPath(baseUrl, "/version"))
            .retrieve()
            .toEntity(String.class)
            .filter(resp -> resp.getStatusCode().is2xxSuccessful())
            .mapNotNull(HttpEntity::getBody)
            .map(v -> v.length() > 0)
            .switchIfEmpty(Mono.just(false))
            .onErrorResume(t -> Mono.just(false))
            .block(); // called by separate thread so blocking is preferred
    }
}
