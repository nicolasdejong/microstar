package net.microstar.dispatcher.model;

import lombok.EqualsAndHashCode;
import net.microstar.spring.settings.DynamicPropertiesManager;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class ServiceInfoStarting extends ServiceInfo {
    @EqualsAndHashCode.Include
    public final UUID serviceInstanceId;
    public final long timeout;
    public final boolean replaceRunning;
    public final Optional<UUID> replaceInstanceId;
    public final CompletableFuture<ServiceInfoRegistered> future;

    public ServiceInfoStarting(ServiceInfo service) { this(service, /*replaceRunning=*/false, Optional.empty()); }
    public ServiceInfoStarting(ServiceInfo service, boolean replaceAllRunning, Optional<UUID> replaceInstance) {
        super(service.id, service.jarInfo);
        final DispatcherProperties props = DynamicPropertiesManager.getInstanceOf(DispatcherProperties.class);
        this.serviceInstanceId = service instanceof ServiceInfoRegistered reg ? reg.serviceInstanceId : UUID.randomUUID();
        this.timeout = System.currentTimeMillis() + props.services.startupTimeout.toMillis();
        this.replaceRunning = replaceAllRunning;
        this.replaceInstanceId = replaceInstance;
        future = new CompletableFuture<>();
    }

    @Override
    public String toString() {
        return "ServiceInfoStarting(instanceId=" + serviceInstanceId + "; timeout=" + timeout + ")";
    }
}
