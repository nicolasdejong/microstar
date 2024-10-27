package net.microstar.dispatcher.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.ByteSizeString;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ProcessInfo;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.model.DispatcherProperties.RestartRule;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static net.microstar.common.util.ThreadUtils.debounce;
import static net.microstar.common.util.Utils.is;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class RestartRulesChecker {
    @SuppressWarnings("this-escape")
    private final DynamicPropertiesRef<DispatcherProperties> props = DynamicPropertiesRef.of(DispatcherProperties.class)
        .onChange(this::checkRules);

    private final Services services;
    private static final Duration DEBOUNCE_DURATION = Duration.ofSeconds(5);

    @Scheduled(fixedDelay = 30_000)
    public void checkRules() {
        debounce(DEBOUNCE_DURATION, this::checkRulesNow);
    }
    void checkRulesNow() {
        final Set<UUID> restartedServices = ConcurrentHashMap.newKeySet(); // Hashcode of mocks cannot be set, so use serviceInstanceId
        props.get().restartRules.forEach(rule -> checkRule(rule, restartedServices));
    }

    private void checkRule(RestartRule rule, Set<UUID> restartedServices) {
        services.getAllRunningServices().stream()
            .filter(service -> !restartedServices.contains(service.serviceInstanceId))
            .filter(service -> isIncluded(rule, service))
            .filter(service -> services.getProcessInfo(service).map(processInfo ->
                checkRule(rule, service, processInfo)).orElse(false))
            .forEach(serviceToRestart -> {
                restartedServices.add(serviceToRestart.serviceInstanceId);
                restart(serviceToRestart);
            });
    }
    private boolean checkRule(RestartRule rule, ServiceInfoRegistered service, ProcessInfo processInfo) {
        return isOverMaxProcMem   (rule, service, processInfo)
            || isOverMaxHeapUse   (rule, service, processInfo)
            || isOverMaxMinHeapUse(rule, service, processInfo)
            || isOverMaxUptime    (rule, service, processInfo)
            || isTime             (rule, service, processInfo);
    }

    private void restart(ServiceInfoRegistered service) {
        if(isDispatcher(service)) services.stop(service).block(); // Dispatcher will be started again by Watchdog
        services.getOrCreateServiceVariations(service.id).restartService(service);
    }
    private String toString(ServiceInfoRegistered service) { return service.id + "/" + service.serviceInstanceId; }

    private boolean isIncluded(RestartRule rule, ServiceInfoRegistered service) {
        class L {
            private L(){} // satisfy Sonar
            static boolean anyMatches(List<String> idMatchers, ServiceInfoRegistered service) {
                return idMatchers.stream().anyMatch(idMatcher -> matches(idMatcher, service.id));
            }
            static boolean matches(String idMatcher, ServiceId id) {
                // idMatcher is one of service-name (1) or service-name/version (2) or group/service-name/version (3) or group/service-name (2)
                final String[] parts = idMatcher.split("/");
                if(parts.length  > 3) return false;
                if(parts.length == 1) return matchesIdPart(id.name, idMatcher);
                if(parts.length == 3) return L.matchesIdPart(id.group, parts[0])
                    && L.matchesIdPart(id.name, parts[1])
                    && L.matchesIdPart(id.version, parts[2]);
                // one of service-name/version of group/service-name
                return (L.matchesIdPart(id.name,  parts[0]) && L.matchesIdPart(id.version, parts[1]))
                    || (L.matchesIdPart(id.group, parts[0]) && L.matchesIdPart(id.name,    parts[1]));
            }
            static boolean matchesIdPart(String toCheck, String pattern) {
                return pattern.isEmpty() || toCheck.matches(pattern.replaceAll("(?<![.])\\*", ".*"));
            }
        }
        if(L.anyMatches(rule.exclude, service)) return false;
        return rule.include.isEmpty() || L.anyMatches(rule.include, service);
    }

    private boolean isOverMaxProcMem(RestartRule rule, ServiceInfoRegistered service, ProcessInfo processInfo) {
        final boolean isOver = processInfo.residentMemorySize.getBytesLong() > rule.maxProcMem.getBytesLong() && rule.maxProcMem.getBytesLong() > 0;
        if(isOver) log.warn("Service {} is over max process memory ({} > {}) -- restarting", toString(service), ByteSizeString.toString(processInfo.residentMemorySize), ByteSizeString.toString(rule.maxProcMem));
        return isOver;
    }
    private boolean isOverMaxHeapUse(RestartRule rule, ServiceInfoRegistered service, ProcessInfo processInfo) {
        final boolean isOver = processInfo.heapUsed.getBytesLong() > rule.maxHeapUsed.getBytesLong() && rule.maxHeapUsed.getBytesLong() > 0;
        if(isOver) log.warn("Service {} is over max heap usage ({} > {}) -- restarting", toString(service), ByteSizeString.toString(processInfo.heapUsed), ByteSizeString.toString(rule.maxHeapUsed));
        return isOver;
    }
    private boolean isOverMaxMinHeapUse(RestartRule rule, ServiceInfoRegistered service, ProcessInfo processInfo) {
        final boolean isOver = processInfo.minHeapUsed.getBytesLong() > rule.maxMinHeapUsed.getBytesLong() && rule.maxMinHeapUsed.getBytesLong() > 0;
        if(isOver) log.warn("Service {} is over max min heap usage ({} > {}) -- restarting", toString(service), ByteSizeString.toString(processInfo.minHeapUsed), ByteSizeString.toString(rule.maxMinHeapUsed));
        return isOver;
    }
    private boolean isOverMaxUptime(RestartRule rule, ServiceInfoRegistered service, ProcessInfo processInfo) {
        final boolean isOver = is(processInfo.uptime).greaterThanOrEqualTo(rule.maxUptime) && !rule.maxUptime.isZero();
        if(isOver) log.warn("Service {} is over max run time ({} > {}) -- restarting", toString(service), processInfo.uptime, rule.maxUptime);
        return isOver;
    }
    private boolean isTime(RestartRule rule, ServiceInfoRegistered service, ProcessInfo processInfo) {
        if(rule.time.equals(LocalTime.MAX)) return false;
        // It is not guaranteed that this check will be called at the exact configured minute.
        // Therefore, trigger also upto fifteen minutes after the configured time if the uptime
        // of the process is longer than fifteen minutes.
        final int now = LocalTime.now().toSecondOfDay();
        final int restartTime = rule.time.toSecondOfDay();
        final boolean isTime = now >= restartTime && now < restartTime + 14*60 && processInfo.uptime.toSeconds() > 15*60;
        if(isTime) log.warn("Service {} is set to restart at {} which is now(ish) -- restarting", toString(service), rule.time);
        return isTime;
    }
    private boolean isDispatcher(ServiceInfoRegistered service) {
        final @Nullable ServiceInfoRegistered dispatcherService = services.getDispatcherService();
        return dispatcherService != null && service.serviceInstanceId.equals(dispatcherService.serviceInstanceId);
    }
}
