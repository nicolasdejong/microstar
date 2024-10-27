package net.microstar.dispatcher.services;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.common.model.ServiceRegistrationRequest;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.ProcessInfo;
import net.microstar.dispatcher.DispatcherApplication;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.model.ServiceInfo;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.model.ServiceInfoStarting;
import net.microstar.dispatcher.services.PreparedResponses.PreparedResponse;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.TimedCounters;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.exceptions.MicroStarException;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.settings.DynamicPropertyRef;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.EventEmitter.ServiceEvent;
import net.microstar.spring.webflux.authorization.AuthUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.ImmutableUtil.copyAndMutate;

/**
 *  Here a list of available services is kept. The service can be requested when it has to be
 *  called. The service has a group, a name and a number (typically a version or timestamp).<p>
 *
 *  Each service can be:<pre>
 * - DORMANT:  the service jar file is found but the service was not requested yet (unregistered)
 * - STARTING: the service jar is in the process of starting after the dispatcher started it (will register when started).
 * - RUNNING:  the service has started and registered itself. It is available for handling requests (registered).
 * </pre>
 * The current directory will be continuously scanned for jar files that will be added to
 * DORMANT when found and removed from the list of dormant services when deleted.<p>
 *
 * For each group-name/service-name a ServiceVariations will be created, including for DORMANT services.
 * When a service is requested, the below code will find the ServiceVariations for that service. When
 * a web-client is created for that service and it turns out it is not yet running, it will be started
 * and the returned Mono will block until the service has started and the request can be sent through.<p>
 *
 * Services that are started *not* by the Dispatcher can register themselves as well, even if running
 * on other machines (the /service/register endpoint is open to all). The clusterSecret key needs
 * to be provided when registering. This is to prevent unknown, possibly malignant, services from
 * adding themselves. When the dispatcher is started, this clusterSecret can be provided in the start
 * command (-DclusterSecret=someCode).
 * The clusterSecret will be "0" when not provided (typically in a dev environment). Services have
 * the same command line option to provide the clusterSecret (which obviously should be the same as the
 * one the Dispatcher has) which will be provided in the 'register' command to the Dispatcher.<p>
 *
 * When a service is requested (because it needs to handle a network request):<pre>
 * - [not found] when no such service exists (leading to a 404)
 * - will start the jar of the service when the service is not yet running (blocking call) and then
 * - returns the service once available. [in the future load-balancing & AB-testing can be added here]
 * </pre>
 * This class (and the Dispatcher) has no knowledge of what the service can do, just how to call it.<p>
 *
 * When a running service is not requested for a while: (NOT YET IMPLEMENTED)<pre>
 * - If the jar is known: it will be stopped after DispatcherProperties.idleStopTime
 * - If the jar is known: the jar will be deleted after ServiceProperties.idleRemoveTime
 * </pre>
 *
 * This class is thread-safe
 */
@Slf4j
@Component
@EnableScheduling
public class Services {
    private static final DynamicPropertyRef<Object> frontendRef = DynamicPropertyRef.of("frontend", Object.class).withDefault(""); // only to send change-event to EventEmitter
    private static final DynamicPropertiesRef<DispatcherProperties> dispatcherPropsRef = DynamicPropertiesRef.of(DispatcherProperties.class);

    private final WebClient.Builder webClientBuilder;
    private final DispatcherApplication application;
    private final EventEmitter eventEmitter;
    @Getter
    private final ServiceInfoRegistered dispatcherService;
    private final StarsManager starsManager;
    private final JarRunner jarRunner;
    private final PreparedResponses preparedResponses;
    private final ServiceProcessInfos serviceProcessInfos;

    // Using an atomic reference to services makes it possible to use this map without synchronisation
    // which improves performance significantly. Updating it (using updateServices()) is slower but happens
    // much less often than getting a registered service for each request.
    // Key is ServiceId without version
    // Value is list of services with the same group & name (e.g. different versions or instances of the same service)
    private final AtomicReference<ImmutableMap<String, ServiceVariations>> servicesRef = new AtomicReference<>(ImmutableUtil.emptyMap());
    private final AtomicReference<ImmutableMap<UUID, ServiceInfoRegistered>> serviceInstanceIdToServiceInfo = new AtomicReference<>(ImmutableUtil.emptyMap());

    private final TimedCounters callCounter = new TimedCounters();
    private final RequestResolver requestResolver;


    @SuppressWarnings("this-escape")
    public Services(WebClient.Builder webClientBuilder, DispatcherApplication application, EventEmitter eventEmitter, StarsManager starsManager,
                    JarRunner jarRunner, PreparedResponses preparedResponses, ServiceProcessInfos serviceProcessInfos) {
        this.webClientBuilder = webClientBuilder;
        this.application = application;
        this.eventEmitter = eventEmitter;
        this.starsManager = starsManager;
        this.jarRunner = jarRunner;
        this.preparedResponses = preparedResponses;
        this.serviceProcessInfos = serviceProcessInfos;

        requestResolver = new RequestResolver(this, starsManager.getLocalStar().url);
        frontendRef       .onChange(() -> eventEmitter.next("SETTINGS-FRONTEND-CHANGED"));
        dispatcherPropsRef.onChange(() -> eventEmitter.next("SETTINGS-DISPATCHER-CHANGED"));

        // The Dispatcher (which this code is running in) is not registered, so do that here so it is included in the list of services
        final InetSocketAddress dispatcherAddress = new InetSocketAddress(starsManager.getLocalStar().url, application.getServerPort());
        dispatcherService = new ServiceInfoRegistered(application.serviceId, application.serviceInstanceId, application.startTime,
            "http", Optional.empty(), Optional.empty(), dispatcherAddress, webClientBuilder, si -> {});

        serviceProcessInfos.setDispatcherService(dispatcherService);
        serviceProcessInfos.setServiceInstanceIdToServiceInfo(serviceInstanceIdToServiceInfo);

        //noinspection OverridableMethodCallDuringObjectConstruction -- making the method final makes it un-mockable
        register(dispatcherService);
    }

    @PreDestroy public void cleanup() {
        // This is called from the Reactor thread, so don't block!
        servicesRef.get().values().forEach(ServiceVariations::disconnect);
    }

    public void handlingRequest() {
        callCounter.increase();
    }
    public int getCallCountInLast(Duration duration) {
        return callCounter.getCountInLast(duration);
    }

    public Set<String> getServiceGroups() {
        return servicesRef.get().keySet().stream()
            .map(key -> key.split("/",2)[0])
            .collect(Collectors.toSet());
    }
    public Set<ServiceVariations> getServiceVariationsInGroup(String group) {
        return servicesRef.get().entrySet().stream()
            .filter(entry -> group.equals(entry.getKey().split("/",2)[0]))
            .map(Map.Entry::getValue)
            .collect(Collectors.toSet());
    }
    public DispatcherApplication getDispatcherApplication() {
        return application;
    }
    public Optional<ServiceInfoRegistered> getRegisteredService(UUID instanceId) {
        return Optional.ofNullable(serviceInstanceIdToServiceInfo.get().get(instanceId));
    }

    public ServiceInfoRegistered     register(ServiceRegistrationRequest registration, InetSocketAddress address) {
        final ServiceId              id = new ServiceId(registration.id);
        final UUID           instanceId = ofNullable(registration.instanceId).orElseGet(UUID::randomUUID);
        final long            startTime = registration.startTime;
        final String           protocol = registration.protocol;
        final Optional<String>      url = Optional.ofNullable(registration.url);
        final Optional<JarInfo> jarInfo = getOrCreateServiceVariations(id).getJarInfoFor(id);

        // For now only check for duplicated registering when no url is provided.
        // The url was added for K8s where individual pods cannot be addressed
        // so duplication should be ignored there.
        if(url.isEmpty()) validateNoDuplicateRegistration(address, instanceId);

        return register(new ServiceInfoRegistered(id, instanceId, startTime, protocol, jarInfo, url, address, webClientBuilder.clone(), si -> {
            log.info("Connection lost with " + si.id + "/" + si.serviceInstanceId + " at " + si.baseUrl);
            unregister(si);
        }));
    }
    public <T extends ServiceInfo> T register(T service) {
        synchronized(servicesRef) {
            getOrCreateServiceVariations(service.id).add(service);
            if (service instanceof ServiceInfoRegistered reg) {
                serviceInstanceIdToServiceInfo.set(copyAndMutate(serviceInstanceIdToServiceInfo.get(),
                    map -> map.put(reg.serviceInstanceId, reg)));
                log.info("Registered service " + reg.id.combined + " at " + reg.baseUrl + " (" + reg.serviceInstanceId + ")");
                logListOfRunningServices();
                serviceProcessInfos.updateProcessInfo(reg);
            } else {
                log.info("Detected dormant service " + service.id.combined);
            }
        }
        eventEmitter.next(new ServiceEvent<>("REGISTERED", Map.of(
            "serviceId", service.id.combined,
            "instanceId", service instanceof ServiceInfoRegistered reg ? reg.serviceInstanceId : new UUID(0, 0),
            "url", service instanceof ServiceInfoRegistered reg ? reg.baseUrl : ""
        )));
        return service;
    }

    public void unregister(ServiceInfoRegistered serviceToUnregister) {
        serviceToUnregister.cleanup();
        synchronized (servicesRef) {
            servicesRef.get().values().forEach(serviceVariations -> serviceVariations.stopped(serviceToUnregister));
            serviceInstanceIdToServiceInfo.updateAndGet(map -> copyAndMutate(map, copy -> copy.remove(serviceToUnregister.serviceInstanceId)));
        }
        log.info("Unregistered service {} on address {}", serviceToUnregister.id.combined, serviceToUnregister.baseUrl);
        logListOfRunningServices();
        eventEmitter.next(new ServiceEvent<>("UNREGISTERED", serviceToUnregister));
    }
    public void unregister(UUID serviceInstanceId) {
        getServiceFrom(serviceInstanceId).ifPresent(this::unregister);
    }

    private void logListOfRunningServices() {
        final List<String> registeredServices = getListOfRegisteredServices().stream()
                .map(reg -> reg.id + " / " + reg.serviceInstanceId + " / " + reg.baseUrl)
                .toList();
        final List<String> startingServices = getListOfStartingServices().stream()
                .map(reg -> reg.id + " / " + reg.serviceInstanceId)
                .toList();
        log.info("List of services:\n"
                + "Registered services:\n - " + String.join("\n - ", registeredServices)
                + (startingServices.isEmpty() ? "" : "\nStarting services:\n - " + String.join("\n - ", startingServices))
        );
    }
    public List<ServiceInfoRegistered> getListOfRegisteredServices() {
        return servicesRef.get().values().stream()
            .flatMap(vars -> vars.getVariations().stream())
            .filter(ServiceInfoRegistered.class::isInstance)
            .map(ServiceInfoRegistered.class::cast)
            .toList();
    }
    public List<ServiceInfoStarting> getListOfStartingServices() {
        return servicesRef.get().values().stream()
            .flatMap(vars -> vars.getVariations().stream())
            .filter(ServiceInfoStarting.class::isInstance)
            .map(ServiceInfoStarting.class::cast)
            .toList();
    }

    public void aboutToRestart(UUID serviceInstanceId) {
        getServiceFrom(serviceInstanceId)
            .ifPresent(serviceInfo -> getOrCreateServiceVariations(serviceInfo.id).starting(serviceInfo));
    }
    public Mono<ResponseEntity<Void>> stop(ServiceInfoRegistered service) { return stop(service.serviceInstanceId); }
    public Mono<ResponseEntity<Void>> stop(UUID serviceInstanceId) {
        return getServiceFrom(serviceInstanceId)
            .map(reg -> reg.webClient.method(HttpMethod.POST).uri("stop").retrieve().toBodilessEntity())
            .orElseGet(() -> {
                log.info("Stopping not-running service {}", serviceInstanceId);
                servicesRef.get().values().forEach(vars -> vars.removeInstance(serviceInstanceId));
                return Mono.empty();
            });
    }

    @Scheduled(fixedRate = 10_000)
    private void pruneStartingServices() {
        servicesRef.get().values().forEach(ServiceVariations::pruneServicesWhoseStartTimedOut);
    }

    public Optional<ServiceInfoRegistered> getServiceFrom(UUID serviceInstanceId) {
        return ofNullable(serviceInstanceIdToServiceInfo.get().get(serviceInstanceId));
    }

    /** Get service variations for a group/name. If no serviceVariations exist for this serviceId, empty will be returned */
    Optional<ServiceVariations> getServiceVariations(String group, String name) {
        return Optional.ofNullable(servicesRef.get().get(group + "/" + name));
    }

    /** Get service variations for a serviceId or, if not found, create a new one */
    public ServiceVariations getOrCreateServiceVariations(ServiceId serviceId) {
        final String groupAndName = serviceId.withoutVersion();
        @Nullable ServiceVariations vars = servicesRef.get().get(groupAndName);
        if(vars == null) {
            synchronized (servicesRef) { // only sync on write
                vars = servicesRef.get().get(groupAndName); // try vars again inside sync in case another thread just added vars
                if(vars == null) {
                    final ServiceVariations finalVars = vars = new ServiceVariations(serviceId.group, serviceId.name, jarRunner, eventEmitter);
                    updateServicesMap(map -> map.put(groupAndName, finalVars));
                }
            }
        }
        return vars;
    }

    public ImmutableMap<UUID,ServiceId> getServiceInstanceIdToServiceIds() {
        return ImmutableMap.<UUID,ServiceId>builder()
            .putAll(serviceInstanceIdToServiceInfo.get().entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().id))
                .collect(ImmutableUtil.toImmutableMap())
            )
            .put(application.serviceInstanceId, application.serviceId) // include dispatcher service
            .buildKeepingLast();
    }

    public Mono<String> getTargetUriFor(ServerHttpRequest request) {
        if(starsManager.isForOtherStar(request)) {
            return starsManager.getTargetStar(request)
                .map(targetStar -> request.getURI().toString().replaceFirst("^\\w+://[^/]+", targetStar.url))
                .map(Mono::just)
                .orElseThrow(() -> new NotFoundException("Unknown target star requested: " + request.getHeaders().getFirst(MicroStarConstants.HEADER_X_STAR_TARGET)));
        }
        return requestResolver.getRequestInfoForTarget(request).getUri(); // may be a fallback uri
    }

    @Builder
    public static class ClientCallMethod {
        @Default public final Optional<Mono<WebClient.RequestBodySpec>> requestBodySpec = Optional.empty();
        @Default public final Optional<PreparedResponse> preparedResponse = Optional.empty();
        public static ClientCallMethod forRequest(Mono<WebClient.RequestBodySpec> req) { return builder().requestBodySpec(Optional.of(req)).build(); }
        public static ClientCallMethod forPreparedResponse(PreparedResponse resp) { return builder().preparedResponse(Optional.of(resp)).build(); }
    }


    /** This method is called by the ProxyController for each request to a service */
    public  Mono<ClientCallMethod> getClientForRequest(ServerHttpRequest request, Consumer<RequestInfo> usedRequestInfo) {
        if(starsManager.isForOtherStar(request)) {
            return starsManager.getTargetStar(request)
                .map(targetStar -> getClientRequest(targetStar, request))
                .map(ClientCallMethod::forRequest)
                .map(Mono::just)
                .orElseThrow(() -> new NotFoundException("Unknown target star requested: " + request.getHeaders().getFirst(MicroStarConstants.HEADER_X_STAR_TARGET)).log());
        }
        final RequestInfo reqInfo = requestResolver.getRequestInfoForTarget(request);
        checkGuestAccess(reqInfo, request);
        usedRequestInfo.accept(reqInfo);

        return preparedResponses.getPrepared(reqInfo)
            .switchIfEmpty(
                Mono.just(reqInfo.unknownTarget
                    ? ClientCallMethod.forRequest(Mono.empty())
                    : ClientCallMethod.forRequest(reqInfo.getClientRequest(request.getMethod())))
            );
    }
    public  Mono<WebClient.RequestBodySpec> getClientForPath(HttpMethod method, String path) {
        final RequestInfo reqInfo = requestResolver.getRequestInfoForTarget(path, "", /*noFallback=*/false, new HttpHeaders());
        return reqInfo.unknownTarget ? Mono.empty() : reqInfo.getClientRequest(method);
    }

    public ImmutableList<ServiceInfoRegistered> getAllRunningServices() {
        return servicesRef.get().values().stream()
            .flatMap(variations -> variations.getVariations().stream())
            .filter(ServiceInfoRegistered.class::isInstance)
            .map(ServiceInfoRegistered.class::cast)
            .collect(ImmutableUtil.toImmutableList());
    }

    public Optional<ProcessInfo> getProcessInfo(ServiceInfoRegistered service) {
        return serviceProcessInfos.getProcessInfo(service);
    }


    private void validateNoDuplicateRegistration(InetSocketAddress address, UUID instanceId) {
        final Optional<ServiceInfoRegistered> existingInstanceService = Optional.ofNullable(serviceInstanceIdToServiceInfo.get().get(instanceId));
        if(existingInstanceService.isPresent()) {
            log.warn("Detected registration attempt from {} for id {} while a service already exists with that id: {}", address, instanceId, existingInstanceService.get().id);
            throw new MicroStarException("Registration attempt from registered service");
        }
        final Optional<ServiceInfoRegistered> existingAddressService = serviceInstanceIdToServiceInfo.get()
            .values()
            .stream()
            .filter(si -> si.address.equals(address))
            .findFirst();
        if(existingAddressService.isPresent()) {
            log.warn("Detected registration attempt from {} while a service already exists at that endpoint: {} / {}", address, existingAddressService.get().id, existingAddressService.get().serviceInstanceId);
            throw new MicroStarException("Duplicate registration address " + address);
        }
    }

    private Mono<WebClient.RequestBodySpec> getClientRequest(Star targetStar, ServerHttpRequest serverRequest) {
        final String reqUri = serverRequest.getURI().toString()
            .replaceFirst("^.*?" + MicroStarConstants.URL_DUMMY_PREVENT_MATCH, "")
            .replaceFirst("^\\w{1,6}://[^/]+/", "/");
        return Mono.just(targetStar)
            .map(star -> star.webClient
                .method(serverRequest.getMethod())
                .uri(concatPath(star.url, reqUri))
                .headers(headers ->{
                    if(serverRequest.getHeaders().containsKey(MicroStarConstants.HEADER_X_FORWARDED)) {
                        throw new FatalException("Request was forwarded to the wrong star(?). This star: " + starsManager.getLocalStar() + " -- Target star: " + targetStar).log();
                    }
                    headers.add(MicroStarConstants.HEADER_X_FORWARDED, star.name);
                })
            );
    }

    private void updateServicesMap(Consumer<ImmutableMap.Builder<String,ServiceVariations>> modifier) { // NOSONAR -- false positive
        final ImmutableMap<String,ServiceVariations> oldServices = servicesRef.get();
        final ImmutableMap.Builder<String,ServiceVariations> newServicesBuilder = ImmutableMap.<String, ServiceVariations>builder() // NOSONAR -- false positive on Builder (!UNKNOWN!)
            .putAll(oldServices);

        modifier.accept(newServicesBuilder);
        servicesRef.set(newServicesBuilder.buildKeepingLast());
    }

    void checkGuestAccess(RequestInfo reqInfo, ServerHttpRequest req) {
        if(AuthUtil.isRequestHoldingSecret(req)) return; // when services talk to each other
        final DispatcherProperties props = dispatcherPropsRef.get();
        final boolean guestUserNotAllowed = isUserGuest(req) && (
              ( props.allowGuests &&  props. denyGuestServices.contains(reqInfo.serviceName))
          ||  (!props.allowGuests && !props.allowGuestServices.contains(reqInfo.serviceName))
        );
        if(guestUserNotAllowed) throw new NotAuthorizedException("Login required for service " + reqInfo.serviceName);
    }

    private boolean isUserGuest(ServerHttpRequest req) {
        return !hasUserToken(req); // TokenValidatorWebFilter removes token when invalid, expired or guest
    }
    private boolean hasUserToken(ServerHttpRequest req) {
        final HttpHeaders headers = req.getHeaders();
        return headers.containsKey(UserToken.HTTP_HEADER_NAME)
            || Optional.ofNullable(req.getHeaders().getFirst("Cookie")).filter(cookies -> cookies.contains(UserToken.HTTP_HEADER_NAME + "=")).isPresent();
    }
}