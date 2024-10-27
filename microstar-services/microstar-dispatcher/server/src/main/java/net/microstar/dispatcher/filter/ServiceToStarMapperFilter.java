package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.services.StarsManager;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static net.microstar.dispatcher.services.StarsManager.FIRST_AVAILABLE_STAR;

/** When a service is configured to be mapped to a star, add an http-header here that points to that star.
  * When a start target is already given, it will not be overwritten.
  * */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class ServiceToStarMapperFilter implements WebFilter {
    private final DynamicPropertiesRef<DispatcherProperties> propsRef = DynamicPropertiesRef.of(DispatcherProperties.class);
    private final StarsManager starsManager;

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain nextInChain) {
        final Map<String,String> serviceTargets = propsRef.get().stars.serviceTargets;
        return nextInChain.filter(serviceTargets.isEmpty() ? exchange : setStarTarget(exchange, serviceTargets));
    }

    private ServerWebExchange setStarTarget(ServerWebExchange exchange, Map<String,String> serviceTargets) {
        return Optional.ofNullable(getServiceTargetFrom(exchange, serviceTargets))
            .filter(starsManager::isActive)
            .map(targetName -> exchange.mutate().request(
                exchange.getRequest().mutate()
                    .headers(headers -> {
                        final @Nullable String existingTarget = headers.getFirst(MicroStarConstants.HEADER_X_STAR_TARGET);
                        if(existingTarget == null || !starsManager.isActive(existingTarget)) {
                            headers.set(MicroStarConstants.HEADER_X_STAR_TARGET, targetName);
                        }
                        if(starsManager.isLocal(Objects.requireNonNull(headers.getFirst(MicroStarConstants.HEADER_X_STAR_TARGET)))) {
                            headers.remove(MicroStarConstants.HEADER_X_STAR_TARGET);
                        }
                    })
                    .build()
                ).build())
            .orElse(exchange);
    }

    private @Nullable String getServiceTargetFrom(ServerWebExchange exchange, Map<String,String> serviceTargets) {
        // path is one of:
        // - /groupName/serviceName/...
        // - /serviceName
        // Now we can search through all known services but here we want to be fast.
        // So simply try both. Just don't name a group the same as a service.
        final String path0 = exchange.getRequest().getPath().toString();
        if(!path0.startsWith("/")) return null;
        final String path = path0.substring(1);

        final int slash1 = path.indexOf('/');
        final String name1 = slash1 < 0 ? path : path.substring(0, slash1);
        final @Nullable String target1 = serviceTargets.get(name1);
        if(target1 != null) return mapTarget(target1);

        final int slash2 = path.indexOf('/', slash1 + 1);
        final String name2 = slash2 < 0 ? path.substring(slash1+1) : path.substring(slash1+1, slash2);
        return mapTarget(serviceTargets.get(name2));
    }

    private @Nullable String mapTarget(@Nullable String targetName) {
        return targetName == null ? null : FIRST_AVAILABLE_STAR.equals(targetName) ? starsManager.getFirstAvailableStarName() : targetName;
    }
}
