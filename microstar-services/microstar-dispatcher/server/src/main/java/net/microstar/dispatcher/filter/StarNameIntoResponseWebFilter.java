package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import net.microstar.common.MicroStarConstants;
import net.microstar.dispatcher.services.StarsManager;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;

@Component
@RequiredArgsConstructor
public class StarNameIntoResponseWebFilter implements WebFilter {
    private final StarsManager starsManager;

    @Override @Nonnull
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            final HttpHeaders headers = exchange.getResponse().getHeaders();
            if(!headers.containsKey(MicroStarConstants.HEADER_X_STAR_NAME)) {
                headers.set(MicroStarConstants.HEADER_X_STAR_NAME, starsManager.getLocalStar().name);
            }
            headers.set(MicroStarConstants.HEADER_X_STAR_GATEWAY,starsManager.getLocalStar().name);
            return Mono.empty();
        });
        return chain.filter(exchange);
    }
}