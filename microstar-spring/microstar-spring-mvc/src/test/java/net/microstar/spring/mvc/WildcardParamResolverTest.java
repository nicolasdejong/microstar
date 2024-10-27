package net.microstar.spring.mvc;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WildcardParamResolverTest {

    @Test void wildcardShouldRemoveRootPart() {
        assertThat(resolve("/root/some/name.txt"), is("some/name.txt"));
    }
    @Test void wildcardShouldRetainEndingSlash() {
        assertThat(resolve("/root/some/path/"), is("some/path/"));
    }

    private static String resolve(String path) {
        final WildcardParamResolver resolver = new WildcardParamResolver(new AntPathMatcher());
        final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        final NativeWebRequest natReq = Mockito.mock(NativeWebRequest.class);
        final MethodParameter param = Mockito.mock(MethodParameter.class);

        final String patternAttr = "/root/**";

        when(natReq.getNativeRequest()).thenReturn(req);
        when(req.getAttribute(any(String.class))).thenAnswer(inv -> {
            if(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE.equals(inv.getArgument(0))) return patternAttr;
            if(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE.equals(inv.getArgument(0))) return path;
            return null;
        });

        return Optional.ofNullable(resolver.resolveArgument(param, null, natReq, null)).map(Object::toString).orElse("");
    }
}