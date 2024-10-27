package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.dispatcher.services.Services;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CounterFilter implements WebFilter {
    private final Services services;

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain nextInChain) {
        if(!isAliveCheck(exchange)) services.handlingRequest();
        return nextInChain.filter(exchange);
    }

    private boolean isAliveCheck(ServerWebExchange exchange) {
        return "/version".equals(exchange.getRequest().getPath().value());
    }
}
