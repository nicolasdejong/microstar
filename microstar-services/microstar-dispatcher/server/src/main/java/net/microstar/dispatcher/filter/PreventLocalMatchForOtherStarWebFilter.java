package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import net.microstar.dispatcher.services.StarsManager;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static net.microstar.common.MicroStarConstants.HEADER_X_STAR_TARGET;
import static net.microstar.common.MicroStarConstants.URL_DUMMY_PREVENT_MATCH;
import static net.microstar.common.io.IOUtils.concatPath;

/**
 * When a different star is given as target, it is mandatory that the fallback proxy
 * rest handler is triggered. Update the path here so that none of the other rest
 * methods trigger by adding a prefix. The prefix will be removed before calling the
 * target star.
 */
@Order(40)
@Component
@RequiredArgsConstructor
public class PreventLocalMatchForOtherStarWebFilter implements WebFilter {
    private final StarsManager starsManager;

    @Override @Nonnull
    public Mono<Void> filter(ServerWebExchange exchange, @Nonnull WebFilterChain chain) {
        final @Nullable String starTarget = exchange.getRequest().getHeaders().getFirst(HEADER_X_STAR_TARGET);

        return starTarget == null || starsManager.getStars().size() <= 1 || starsManager.getLocalStar().name.equals(starTarget)
            ? chain.filter(exchange)
            : chain.filter(alterPath(exchange))
            ;
    }

    private static ServerWebExchange alterPath(ServerWebExchange exchange) {
        final String oldPath = exchange.getRequest().getPath().toString();
        final String newPath = "/" + concatPath(URL_DUMMY_PREVENT_MATCH, oldPath);

        return exchange.mutate().request(exchange.getRequest().mutate().path(newPath).build()).build();
    }
}