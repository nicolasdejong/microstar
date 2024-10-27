package net.microstar.spring.webflux.filters;

import lombok.RequiredArgsConstructor;
import net.microstar.spring.webflux.application.DispatcherDelegateWebflux;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ConnectedFilter implements WebFilter {
    final DispatcherDelegateWebflux dispatcher;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain nextInChain) {
        if(requestIsFromDispatcher(exchange)) dispatcher.connectionChecker.setIsConnected();
        return nextInChain.filter(exchange);
    }

    private static boolean requestIsFromDispatcher(ServerWebExchange exchange) {
        return exchange.getRequest().getHeaders().containsKey("X-Forwarded-Path");
    }
}
