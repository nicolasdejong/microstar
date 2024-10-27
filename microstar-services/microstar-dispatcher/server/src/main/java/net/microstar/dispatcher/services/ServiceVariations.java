package net.microstar.dispatcher.services;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.model.ServiceInfo;
import net.microstar.dispatcher.model.ServiceInfoJar;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.model.ServiceInfoStarting;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.TimeoutException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static net.microstar.common.util.ImmutableUtil.emptyList;
import static net.microstar.common.util.ImmutableUtil.updateListRef;
import static net.microstar.common.util.Utils.is;

/** <pre>
 * A service variation represents all available services that have the same group + name.
 *
 * A single service (group + name) can have multiple variations:
 * - multiple instances of the same (e.g. for load balancing)
 * - different versions (old and new may be active at the same time while deploying or there is a traffic percentage difference)
 *
 * This class is thread-safe
 */
@Slf4j
@RequiredArgsConstructor
public class ServiceVariations {
    private final DynamicPropertiesRef<DispatcherProperties> dispatcherProps = DynamicPropertiesRef.of(DispatcherProperties.class);
    private final String serviceGroup;
    private final String serviceName;
    private final JarRunner jarRunner;
    private final EventEmitter eventEmitter;

    /** All services that are known for this serviceGroup/serviceName, including not running */
    private final AtomicReference<ImmutableList<ServiceInfo>> serviceVariationsRef = new AtomicReference<>(emptyList());

    /** Subset of variations with the latest version (including (re)starting and not running) */
    private final AtomicReference<ImmutableList<ServiceInfo>> mostCurrentServicesRef = new AtomicReference<>(emptyList());

    /** Subset of mostCurrent that are all available for handling requests */
    private final AtomicReference<ImmutableList<ServiceInfoRegistered>> availableServicesRef = new AtomicReference<>(emptyList());

    /** Subset of mostCurrent that are (re)starting */
    private final AtomicReference<ImmutableList<ServiceInfoStarting>> startingServicesRef = new AtomicReference<>(emptyList());

    private final AtomicInteger callCounter = new AtomicInteger(0);

    public void disconnect() {
        final List<ServiceInfoRegistered> regs = availableServicesRef.get();
        if(!regs.isEmpty()) log.info("disconnect {}{}/{} service", regs.size() == 1 ? "" : (regs.size() + "x "), serviceGroup, serviceName);
        regs.forEach(reg -> reg.isAliveConnection.stop());
    }

    public String toString() { return String.format("[ServiceVariations of %s/%s with %s variations]", serviceGroup, serviceName, serviceVariationsRef.get().size()); }

    public void add(ServiceInfo newVariation) { // NOSONAR -- complexity isn't that high, just synchronized(update(removeIf(...)))
        // Calling the complete future should run outside the sync & updateListRef
        final Runnable[] runComplete = { null };

        synchronized (serviceVariationsRef) {
            updateListRef(serviceVariationsRef, variations -> {
                // Add new variation
                variations.add(newVariation);

                // When the new variation is registered, remove starting service that initiated the registration
                if(newVariation instanceof ServiceInfoRegistered newRegisteredVariation) {
                    variations.removeIf(ss -> {
                        if (ss instanceof ServiceInfoStarting res && res.serviceInstanceId.equals(newRegisteredVariation.serviceInstanceId)) {
                            log.info("Finished starting: {}: {} {}{}", res.id, res.serviceInstanceId, res.replaceRunning ? "replace all " : "", res.replaceInstanceId.map(i->"replace instance " + i).orElse(""));
                            runComplete[0] = () -> res.future.complete(newRegisteredVariation);
                            if(res.replaceRunning) stopSiblingsOf(newRegisteredVariation);
                            res.replaceInstanceId.ifPresent(this::stop);
                            return true;
                        }
                        return false;
                    });
                }
            });
            updated();
        }
        if(runComplete[0] != null) runComplete[0].run();
    }

    public void stop(UUID instanceId) {
        stop(reg -> reg.serviceInstanceId.equals(instanceId));
    }
    public void stopSiblingsOf(ServiceInfoRegistered remainingService) {
        stop(reg -> reg.id.name.equals(remainingService.id.name)
                && !reg.serviceInstanceId.equals(remainingService.serviceInstanceId));
    }
    public void stop(Predicate<ServiceInfoRegistered> stopCondition) {
        serviceVariationsRef.get().stream()
            .filter(ServiceInfoRegistered.class::isInstance)
            .map(ServiceInfoRegistered.class::cast)
            .filter(stopCondition)
            .forEach(serviceToStop -> Threads.execute(() -> serviceToStop.tellServiceToStop().block()) );
    }

    public void removeAllDormants(ServiceId id) {
        synchronized (serviceVariationsRef) {
            updateListRef(serviceVariationsRef, variations -> variations
                .removeIf(serviceInfo -> serviceInfo.id.equals(id) && (serviceInfo instanceof ServiceInfoJar)));
        }
        updated();
    }
    public void removeInstance(UUID instanceId) {
        synchronized (serviceVariationsRef) {
            getServiceInfoFor(instanceId).ifPresent(serviceInfoToRemove -> {
                updateListRef(serviceVariationsRef, serviceInfos -> serviceInfos.remove(serviceInfoToRemove));
                updated();
                eventEmitter.next(new EventEmitter.ServiceEvent<>("UNREGISTERED", serviceInfoToRemove));
            });
        }
    }
    public Optional<ServiceInfo> getServiceInfoFor(UUID instanceId) {
        return serviceVariationsRef.get().stream().filter(serviceInfo ->
                (serviceInfo instanceof ServiceInfoRegistered reg    && instanceId.equals(reg.serviceInstanceId))
             || (serviceInfo instanceof ServiceInfoStarting starting && instanceId.equals(starting.serviceInstanceId))
        ).findFirst();
    }

    public void stopped(ServiceInfoRegistered stoppedService) {
        synchronized (serviceVariationsRef) {
            updateListRef(serviceVariationsRef, variations -> variations.stream()
                .filter(ServiceInfoRegistered.class::isInstance)
                .map(ServiceInfoRegistered.class::cast)
                .filter(reg -> reg.serviceInstanceId.equals(stoppedService.serviceInstanceId))
                .findFirst()
                .ifPresent(serviceToRemove -> {
                    log.info("Service stopped: {}:{} -- {}", stoppedService.id, stoppedService.baseUrl.replaceFirst("^https?://localhost:", ""), stoppedService.serviceInstanceId);
                    variations.remove(serviceToRemove);

                    // remember the jar file
                    if (serviceToRemove.jarInfo.isPresent() && getJarInfoFor(variations, serviceToRemove.id).isEmpty()) {
                        variations.add(new ServiceInfoJar(serviceToRemove));
                    }
                }));
            updated();
        }
    }
    public void starting(ServiceInfo startingService) {
        log.info(startingService.id + " is " + (startingService instanceof ServiceInfoRegistered ? "RE": "") + "STARTING");

        synchronized (serviceVariationsRef) {
            if (startingService instanceof ServiceInfoRegistered reg) removeInstance(reg.serviceInstanceId);
            pruneServicesWhoseStartTimedOut();
            updateListRef(serviceVariationsRef, variations -> variations.add(new ServiceInfoStarting(startingService)));
        }
        updated();
    }

    /** Returns service infos, sorted by latest (the highest version) first */
    public ImmutableList<ServiceInfo> getVariations() {
        return serviceVariationsRef.get();
    }

    /** Returns an available running service that can be called. May not return immediately
      * in case there are no available running services and one should be started first.
      * (more precise: the Mono is returned immediately, but the Mono.block() may take a while)
      */
    public Mono<ServiceInfoRegistered> getServiceToCall() {
        // This method is called for every request so should be as fast as possible.
        final class Local {
            private Local() {} // keep Sonar happy
            static final Set<String> messages = new HashSet<>();
            static void ifNotCalledBefore(String msg, Consumer<String> logger) {
                if(!messages.contains(msg)) { messages.add(msg); logger.accept(msg); }
            }
        }

        // For now gets the most current registered service info
        // Later support multiple running instances (e.g. for older versions, load balancing, AB-testing, etc)
        return getAvailableService()

            // No available services.
            // If there are no starting services, start one, followed by:
            // If there is a starting service, wait for it.
            .switchIfEmpty(Mono.defer(() -> {
                // When the Dispatcher has just started, running services may not
                // yet have registered themselves. Requests to services that are
                // not registered (which Dispatcher interprets as: not running)
                // will lead to starting that service. To prevent this from
                // happening before the service had time to register itself,
                // add a small delay when the Dispatcher has just started.
                // Otherwise, new services will be started before the already
                // running service had time to register itself.
                if(serverJustStartedAndIsWaitingForServicesToRegister()) {
                    Local.ifNotCalledBefore("Waiting to start service " + serviceName + " because waiting for existing services to connect", log::info);
                    return Mono.delay(Duration.ofMillis(1000)) // pause a bit, then try again
                        .flatMap(unused -> getServiceToCall());
                }

                return addAvailableService();
            }));
    }

    private Mono<ServiceInfoRegistered> getAvailableService() {
        // Any currently available registered service? Then use it
        // When multiple services, round-robin call them for each request
        final List<ServiceInfoRegistered> availableList = availableServicesRef.get();
        if(availableList.size() == 1) return Mono.just(availableList.get(0));
        if(availableList.size() > 1) return Mono.just(availableList.get(callCounter.incrementAndGet() % availableList.size()));

        // Any starting service? Then wait for it
        final List<ServiceInfoStarting> startingServices = startingServicesRef.get();
        if (!startingServices.isEmpty()) return waitForStartingService(startingServices);

        // No result leads to 404
        return Mono.empty();
    }

    /** Start a service so the availableServices gets filled.
      * Don't start a service if a service is currently starting.
      * The returned mono will wait until the service has started.
      */
    private Mono<ServiceInfoRegistered> addAvailableService() {
        // Now it is possible that there are multiple requests at
        // the same time asking for a service that is not running.
        // To prevent starting the same service multiple times,
        // synchronize the next block.
        synchronized(serviceVariationsRef) {

            // This thread may have waited a bit when another thread
            // added a new service. So, first check before adding a
            // new service.
            if ( !availableServicesRef.get().isEmpty()
              || ! startingServicesRef.get().isEmpty()) {
                return getAvailableService();
            }

            // Nothing available and nothing currently starting? Then perhaps there is a jar to start
            if (dispatcherProps.get().services.startWhenCalled) startService();

            // startingServicesRef will have changed by startService() when a service was started

            // No service available -- leads to 404

            return getAvailableService();
        }
    }

    public void restartService(ServiceInfoRegistered service) {
        startService(service.id, false, Optional.of(service.serviceInstanceId));
    }
    public void startService(ServiceId serviceId, boolean replaceAllRunning, Optional<UUID> replaceInstance) {
        getJarInfoFor(serviceId).ifPresent(jarFile -> startService(new ServiceInfoJar(serviceId, jarFile), replaceAllRunning, replaceInstance));
    }
    public boolean isKnownService(ServiceId id) {
        return serviceVariationsRef.get().stream().anyMatch(service -> service.id.equals(id));
    }
    public Optional<JarInfo> getJarInfoFor(ServiceId id) {
        return getJarInfoFor(serviceVariationsRef.get(), id);
    }

    /** When the server just started, it is waiting for existing services to register itself and no new
      * services should be started in that time because they may already be running.
      */
    private boolean serverJustStartedAndIsWaitingForServicesToRegister() {
        final Duration activeDuration = MicroStarApplication.get().map(app -> app.getRunTime().minus(app.getStartupTime())).orElse(Duration.ofSeconds(0));
        return is(activeDuration).smallerThan(dispatcherProps.get().services.initialRegisterTime);
    }
    private static Optional<JarInfo> getJarInfoFor(List<ServiceInfo> infos, ServiceId id) {
        return infos.stream()
            .filter(serviceInfo -> serviceInfo.id.equals(id))
            .flatMap(serviceInfo -> serviceInfo.jarInfo.stream())
            .findFirst();
    }

    private void updated() {
        // mostCurrent, available, starting
        synchronized (serviceVariationsRef) {
            serviceVariationsRef.set(ImmutableUtil.copyAndMutate(serviceVariationsRef.get(), variations -> variations.sort(ServiceInfo.serviceWithHighestVersionFirstComparator)));

            final ServiceId highestId = serviceVariationsRef.get().stream().findFirst().map(v -> v.id).orElse(ServiceId.NONE);
            mostCurrentServicesRef.set(ImmutableList.copyOf(
                serviceVariationsRef.get().stream()
                .filter(v -> v.id.equals(highestId))
                .toList()
            ));

            availableServicesRef.set(ImmutableList.copyOf(
                serviceVariationsRef.get().stream()
                .filter(ServiceInfoRegistered.class::isInstance)
                .map   (ServiceInfoRegistered.class::cast)
                .distinct()
                .toList()
            ));

            startingServicesRef.set(ImmutableList.copyOf(
                serviceVariationsRef.get().stream()
                .filter(ServiceInfoStarting.class::isInstance)
                .map   (ServiceInfoStarting.class::cast)
                .distinct()
                .toList()));

            log.debug("Versions of {} updated: {} running service{} (of {}): {}",
                serviceName,
                availableServicesRef.get().size(),
                availableServicesRef.get().size() == 1 ? "" : "s",
                serviceVariationsRef.get().size(),
                availableServicesRef.get().stream().map(s -> s.id.group + "/" + s.id.version + ":" + s.baseUrl.replaceFirst("^https?://localhost:", "")).toList()
            );
        }
    }
    void pruneServicesWhoseStartTimedOut() {
        final long now = System.currentTimeMillis();
        synchronized (serviceVariationsRef) {
            updateListRef(serviceVariationsRef, variations -> variations.removeIf(
                service -> service instanceof ServiceInfoStarting res && res.timeout < now
            ));
            updated();
        }
    }

    private void startService() {
        log.info("ServiceVariations[{}-{}] Attempt to start service", serviceGroup, serviceName);
        mostCurrentServicesRef.get().stream()
            .filter(ServiceInfoJar.class::isInstance)
            .map(ServiceInfoJar.class::cast)
            .findFirst()
            .ifPresent(service -> startService(service, /*replaceRunning=*/false, /*replaceInstance=*/Optional.empty()));
    }
    private void startService(ServiceInfoJar serviceInfoJar, boolean replaceAllRunning, Optional<UUID> replaceInstance) {
        log.info("Start service-jar " + serviceInfoJar.jarInfo
            .map(jarInfo -> jarInfo.name)
            .map(Object::toString)
            .orElseThrow()
            + (replaceAllRunning ? " (replace all running instances of same name)" : "")
            + (replaceInstance.map(i -> " and replace instance " + i).orElse(""))
        );
        final ServiceInfoStarting startingService = new ServiceInfoStarting(serviceInfoJar, replaceAllRunning, replaceInstance);
        jarRunner.run(serviceInfoJar.id, serviceInfoJar.jarInfo.orElseThrow(), ImmutableUtil.mapOf( // ImmutableMap has ordered entries
            "serviceName",               serviceInfoJar.id.name, // unused: this is only because the Windows TaskManager has limited length for the command-line column
            "instanceId",                startingService.serviceInstanceId.toString(),
            "app.config.dispatcher.url", dispatcherProps.get().url,
            // TODO: StringUtils.obfuscate() the values below when all running services can handle obfuscation
            "encryption.encPassword",    DynamicPropertiesManager.getProperty("encryption.encPassword").orElse(""),
            "clusterSecret",             MicroStarConstants.CLUSTER_SECRET,

            // Special case -- Only when starting the settings service
            //                 The Dispatcher is the only one who knows what datastore the settings service should use
            //                 as settings can only be loaded once is known where the settings store is (bootstrapping).
            "microstar.dataStores.settings", "microstar-settings".equals(serviceName)
                ? getSettingsStoreCompactSettings()
                : null
        ));
        synchronized (serviceVariationsRef) {
            updateListRef(serviceVariationsRef, variations -> variations.add(startingService));
            updated();
        }
        eventEmitter.next(new EventEmitter.ServiceEvent<>("SERVICE-STARTING", startingService));
    }
    private Mono<ServiceInfoRegistered> waitForStartingService(List<ServiceInfoStarting> startingServices) {
        log.debug("Holding request for service that is starting [{}-{}]", serviceGroup, serviceName);
        final ServiceInfoStarting startingService = startingServices.get(callCounter.incrementAndGet() % startingServices.size());
        return Mono.fromFuture(startingService.future)
            .timeout(dispatcherProps.get().services.startupTimeout)
            .onErrorMap(reactiveEx -> new TimeoutException("Service timed out: " + startingService.id))
            .flatMap(Mono::justOrEmpty);
    }
    private @Nullable String getSettingsStoreCompactSettings() {
        final Optional<ImmutableMap<String, Object>> mapOpt = DynamicPropertiesManager.getCombinedSettings().getMap("microstar.DataStores.settings");
        return mapOpt.map(map -> map.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(";")))
            .orElse(null);
    }
}
