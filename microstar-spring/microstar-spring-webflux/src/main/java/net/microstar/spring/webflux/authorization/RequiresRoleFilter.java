package net.microstar.spring.webflux.authorization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotAllowedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
@Component
@RequiredArgsConstructor
public class RequiresRoleFilter implements WebFilter {
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // From: https://stackoverflow.com/questions/50456712/how-to-intercept-requests-by-handler-method-in-spring-webflux

        getHandlerMethodFor(exchange)
            .map(RequiresRoleFilter::getRequiresRoleAnnotationOf)
            .ifPresent(requiresRole -> validateAllowed(requiresRole, exchange))
            ;
        return chain.filter(exchange);
    }

    private Optional<HandlerMethod> getHandlerMethodFor(ServerWebExchange exchange) {
        return noThrow(() -> requestMappingHandlerMapping.getHandler(exchange).toFuture().get())
            .filter(obj -> obj instanceof HandlerMethod)
            .map(obj -> (HandlerMethod)obj);
    }

    private static void validateAllowed(final RequiresRole requiresRole, ServerWebExchange exchange) {
        if(AuthUtil.isRequestHoldingSecret(exchange)) return; // when the secret is known, all is allowed
        final UserToken userToken = AuthUtil.userTokenFrom(exchange);
        final String[] requiredRoles = requiresRole.value();

        if(  ( requiresRole.or() && !userToken.hasAnyRoles(requiredRoles))
          || (!requiresRole.or() && !userToken.hasAllRoles(requiredRoles))) {
            throw new NotAllowedException("Requires role " + Arrays.asList(requiredRoles));
        }
    }

    private static @Nullable RequiresRole getRequiresRoleAnnotationOf(HandlerMethod handlerMethod) {
        return Optional.ofNullable(handlerMethod.getMethodAnnotation(RequiresRole.class))
            .orElseGet(() -> handlerMethod.getBeanType().getAnnotation(RequiresRole.class));
    }
}
