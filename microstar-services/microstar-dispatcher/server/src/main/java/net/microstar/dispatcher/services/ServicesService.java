package net.microstar.dispatcher.services;

import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.common.model.ServiceRegistrationRequest;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.ServiceInfoJar;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.model.ServiceInfoStarting;
import net.microstar.dispatcher.model.ServicesForClient;
import net.microstar.dispatcher.model.ServicesForClient.Service.ServiceBuilder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ServicesService {
    private static final Duration DURATION_1M  = Duration.ofMinutes(1);
    private static final Duration DURATION_10M = Duration.ofMinutes(10);
    private static final Duration DURATION_8H  = Duration.ofHours(8);
    private static final Duration DURATION_24H = Duration.ofHours(24);
    private final Services services;

    public ServiceInfoRegistered register(ServiceRegistrationRequest reg, InetSocketAddress address) {
        final ServiceInfoRegistered service = services.register(reg, address);
        if(isSettingsServiceRegistered()) {
            log.info("Ask " + service.id + " to request settings");
            Threads.execute(() -> tellServiceToRequestSettings(service).block());
        }
        return service;
    }

    public void unregister(UUID serviceInstanceId) {
        services.unregister(serviceInstanceId);
    }

    public void aboutToRestart(UUID serviceInstanceId) {
        services.aboutToRestart(serviceInstanceId);
    }

    public ServicesForClient getServicesForClient() {
        return ServicesForClient.builder()
            .services(services.getServiceGroups().stream()
                .flatMap(serviceGroup -> services.getServiceVariationsInGroup(serviceGroup).stream())
                .flatMap(serviceVariations -> serviceVariations.getVariations().stream())
                .map(serviceInfo -> {
                    final ServiceBuilder builder = ServicesForClient.Service.builder();
                    // TODO: add counts to Dispatcher class since otherwise the counts will reset when dispatcher restarts
                    // TODO: should counts be stored outside ServiceInfoReg? Counts will reset when dispatcher restarts
                    if(serviceInfo instanceof ServiceInfoStarting starting) {
                        builder
                            .instanceId(starting.serviceInstanceId);
                    } else
                    if(serviceInfo instanceof ServiceInfoRegistered reg) {
                        builder
                            .requestCount1m(reg.getCallCountInLast(DURATION_1M))
                            .requestCount10m(reg.getCallCountInLast(DURATION_10M))
                            .requestCount8h(reg.getCallCountInLast(DURATION_8H))
                            .requestCount24h(reg.getCallCountInLast(DURATION_24H))
                            .instanceId(reg.serviceInstanceId)
                            .runningSince(reg.startTime);
                    } else {
                        builder
                            .requestCount1m(services.getCallCountInLast(DURATION_1M))
                            .requestCount10m(services.getCallCountInLast(DURATION_10M))
                            .requestCount8h(services.getCallCountInLast(DURATION_8H))
                            .requestCount24h(services.getCallCountInLast(DURATION_24H));
                    }
                    return builder
                        .id(serviceInfo.id)
                        .state(serviceInfo instanceof ServiceInfoRegistered ? "RUNNING" :
                               serviceInfo instanceof ServiceInfoStarting   ? "STARTING" :
                               serviceInfo instanceof ServiceInfoJar        ? "DORMANT" : "NONE")
                        .build();
                    }
                )
                .toList()
            ).build();
    }

    public ImmutableMap<UUID, ServiceId> getServiceInstanceIds() {
        return services.getServiceInstanceIdToServiceIds();
    }

    public boolean isSettingsServiceRegistered() {
        return services.getAllRunningServices().stream().anyMatch(rs -> "microstar-settings".equals(rs.id.name));
    }

    public Mono<Void> tellServicesToRequestSettings() {
        log.info("Tell all services to request settings");
        return Mono.zipDelayError(
            services.getAllRunningServices().stream()
                .filter(service -> !"dispatcher".equals(service.id.name))
                .map(this::tellServiceToRequestSettings)
                .toList(), results -> "")
            .onErrorReturn("") // prevent error from being printed to the console
            .then();
    }
    public Mono<?> tellServiceToRequestSettings(ServiceInfoRegistered service) {
        return service.webClient
            .get()
            .uri("/refresh-settings")
            .header(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET)
            .retrieve()
            .toBodilessEntity()
            .doOnError(t -> log.warn("Failed to request settings for {}: {}", service.id, t.getMessage()))
            ;
    }
}
