package net.microstar.spring.webflux.settings;

import net.microstar.spring.settings.DynamicProperties;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class DynamicPropertiesMethodArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().isAnnotationPresent(DynamicProperties.class);
    }

    @Override
    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        return Mono.just(DynamicPropertiesManager.getInstanceOf(parameter.getParameterType()));
    }
}
