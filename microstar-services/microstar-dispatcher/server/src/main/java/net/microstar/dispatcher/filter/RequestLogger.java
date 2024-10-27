package net.microstar.dispatcher.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogger implements WebFilter {

    @Override
    @Nonnull
    public Mono<Void> filter(@Nonnull ServerWebExchange exchange, @Nonnull WebFilterChain nextInChain) {
        if(log.isDebugEnabled()) {
            final ServerHttpRequest req = exchange.getRequest();
            final String path = req.getPath().value();
            final boolean skip = path.startsWith("/src/")
                              || path.startsWith("/node_modules/")
                              || path.startsWith("/version")
                              || path.startsWith("/services/instance-ids")
                              || path.endsWith("/processInfo");
            if(!skip) log.debug("Incoming REQUEST: {}", getRequestTarget(exchange));
        }
        return nextInChain.filter(exchange);
    }

    private static String getRequestTarget(ServerWebExchange exchange) {
        final ServerHttpRequest req = exchange.getRequest();
        String uri = req.getURI().toString();
        final int index = uri.indexOf(exchange.getRequest().getPath().value());
        return req.getMethod() + " " + (index < 0 ? uri : uri.substring(index));
    }
}