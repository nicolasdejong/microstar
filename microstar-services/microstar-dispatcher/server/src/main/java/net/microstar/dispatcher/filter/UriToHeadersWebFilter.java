package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import net.microstar.common.util.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The native browser websocket implementation does not support setting headers.
 * A workaround is to set values in the cookie, which will be sent with the
 * websocket request.<p>
 *
 * This filter allows for another workaround: add http headers into the uri path.
 * This filter will remove this information from the uri path and add them to the
 * headers so the request handlers won't even know that tricks have been used.<p>
 *
 * The format is: /@(header-name/header-value)@/  <br>
 * For example: /some/path/@(x-some-header/some%20header%20value@/
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class UriToHeadersWebFilter implements WebFilter {
    private static final Pattern TUPLE_PATTERN = Pattern.compile("(?<=/)@\\((.*?)\\)@/");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        final @Nullable String requestPath = exchange.getRequest().getPath().value();
        final int firstIndex = requestPath.indexOf("/@(");

        if(firstIndex < 0) return chain.filter(exchange);

        final ServerHttpRequest.Builder reqBuilder = exchange.getRequest().mutate();
        reqBuilder.path(StringUtils.replaceMatches(requestPath, TUPLE_PATTERN, matcher -> {
            final String[] tuple = URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8).split("/", 2);
            reqBuilder.header(tuple[0], tuple.length > 1 ? tuple[1] : "");
            return "";
        }));

        return chain.filter(exchange.mutate().request(reqBuilder.build()).build());
    }
}