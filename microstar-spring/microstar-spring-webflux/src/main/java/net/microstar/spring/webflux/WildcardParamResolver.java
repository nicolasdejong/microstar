package net.microstar.spring.webflux;

import lombok.RequiredArgsConstructor;
import net.microstar.spring.WildcardParam;
import org.springframework.core.MethodParameter;
import org.springframework.util.PathMatcher;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@RequiredArgsConstructor
public class WildcardParamResolver implements HandlerMethodArgumentResolver {
    private final PathMatcher pathMatcher;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterAnnotation(WildcardParam.class) != null;
    }

    @Override
    public Mono<Object> resolveArgument(MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        @Nullable final PathPattern pattern   = exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        @Nullable final String      innerPath = exchange.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        return pattern == null || innerPath == null
            ? Mono.empty()
            : Mono.just(extractPath(pattern.toString(), innerPath));
    }

    private String extractPath(String pattern, String innerPath) {
        final String extractedPath = pathMatcher.extractPathWithinPattern(pattern, innerPath);
        final String encodedResult = extractedPath + (innerPath.endsWith("/") && !extractedPath.isEmpty() && !extractedPath.endsWith("/") ? "/" : "");
        return decode(encodedResult);
    }

    private static String decode(String toDecode) {
        // It seems the path is not decoded (so it may contain %code codes that need decoding). So do it here then.
        return URLDecoder.decode(toDecode, StandardCharsets.UTF_8);
    }
}
