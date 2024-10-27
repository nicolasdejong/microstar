package net.microstar.spring.mvc;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.microstar.spring.WildcardParam;
import org.springframework.core.MethodParameter;
import org.springframework.util.PathMatcher;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

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
    public Object resolveArgument(MethodParameter parameter,
                                  @Nullable ModelAndViewContainer modelAndViewContainer,
                                  NativeWebRequest nativeWebRequest,
                                  @Nullable WebDataBinderFactory webDataBinderFactory) {
        final HttpServletRequest request = (HttpServletRequest)nativeWebRequest.getNativeRequest();
        @Nullable final String pattern   = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        @Nullable final String innerPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        return pattern == null || innerPath == null
            ? ""
            : extractPath(pattern, innerPath);
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
