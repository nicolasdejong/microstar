package net.microstar.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;

@Slf4j
@Component
@RequiredArgsConstructor
public class DisabledEndpointsFilter implements WebFilter {

    @Override @Nonnull
    public Mono<Void> filter(ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        final String path = exchange.getRequest().getPath().toString();

        // This settings service is the origin of the settings, so it should not get it from itself.
        // However, this endpoint is part of DefaultEndpoints. Add ignore here.
        if("/refresh-settings".equals(path)) return ignore(exchange);
        return chain.filter(exchange);
    }

    private Mono<Void> ignore(ServerWebExchange exchange) {
        log.info("Ignoring call to {}", exchange.getRequest().getPath());
        exchange.getResponse().setStatusCode(HttpStatus.OK);
        return Mono.empty();
    }
}