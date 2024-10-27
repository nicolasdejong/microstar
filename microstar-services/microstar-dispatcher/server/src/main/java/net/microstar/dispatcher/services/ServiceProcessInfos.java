package net.microstar.dispatcher.services;

import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.ProcessInfo;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.EventEmitter.ServiceEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;


@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceProcessInfos {
    @Setter // Initially this is null, but set by Services before this bean is used
    private ServiceInfoRegistered dispatcherService;
    @Setter
    private AtomicReference<ImmutableMap<UUID, ServiceInfoRegistered>> serviceInstanceIdToServiceInfo = new AtomicReference<>(ImmutableUtil.emptyMap());
    private final AtomicReference<Map<UUID, ProcessInfo>> serviceInstanceIdToProcessInfo = new AtomicReference<>(ImmutableUtil.emptyMap());
    private final EventEmitter eventEmitter;

    public Optional<ProcessInfo> getProcessInfo(ServiceInfoRegistered service) {
        return Optional.ofNullable(serviceInstanceIdToProcessInfo.get().get(service.serviceInstanceId));
    }

    @Scheduled(fixedRate = 30_000)
    public void updateProcessInfos() {
        Threads.execute(() -> {
            pruneProcessInfos();
            final List<Mono<ProcessInfo>> monoList = serviceInstanceIdToServiceInfo.get().values().stream()
                .map(this::getProcessInfoFrom)
                .toList();

            Mono.zipDelayError(monoList, array -> "done").block();
            emitProcessInfosEvent();
        });
    }
    public void updateProcessInfo(ServiceInfoRegistered service) {
        Threads.execute(() -> {
            getProcessInfoFrom(service).block();
            emitProcessInfosEvent();
        });
    }
    public void emitProcessInfosEvent() {
        record ServiceAndProcessInfo(ServiceInfoRegistered serviceInfo, @Nullable ProcessInfo processInfo) {}
        eventEmitter.next(new ServiceEvent<>(
            "PROCESS-INFOS",
            serviceInstanceIdToServiceInfo.get().entrySet().stream()
                .map(entry -> new ServiceAndProcessInfo(entry.getValue(), serviceInstanceIdToProcessInfo.get().get(entry.getKey())))
                .filter(sapi -> sapi.processInfo != null)
                .map(sapi -> Map.entry(sapi.serviceInfo.serviceInstanceId, sapi.processInfo))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        ));
    }

    private void pruneProcessInfos() {
        ImmutableUtil.removeFromMapRef(serviceInstanceIdToProcessInfo, (uuid, service) -> !serviceInstanceIdToServiceInfo.get().containsKey(uuid));
    }
    private void updateProcessInfo(ServiceInfoRegistered service, ProcessInfo processInfo) {
        ImmutableUtil.updateMapRef(serviceInstanceIdToProcessInfo, map -> map.put(service.serviceInstanceId, processInfo));
    }
    private Mono<ProcessInfo> getProcessInfoFrom(ServiceInfoRegistered service) {
        if(service.serviceInstanceId.equals(dispatcherService.serviceInstanceId)) {
            final ProcessInfo latestDispatcherProcessInfo = ProcessInfo.getLatest();
            updateProcessInfo(dispatcherService, latestDispatcherProcessInfo);
            return Mono.just(latestDispatcherProcessInfo);
        }

        //noinspection ConstantValue
        return service.webClient == null
            ? Mono.empty()
            : service.webClient
                    .get()
                    .uri("processInfo")
                    .headers(headers -> MicroStarApplication.get().orElseThrow().setHeaders(headers))
                    .retrieve()
                    .bodyToMono(ProcessInfo.class)
                    .doOnSuccess(processInfo -> {
                        // If the call fails, this method is call with a null-value (!) instead of simply not being called
                        if(processInfo != null) updateProcessInfo(service, processInfo);
                    })
                    .onErrorComplete()
                    ;
    }
}
