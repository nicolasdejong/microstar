package net.microstar.spring.webflux;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.MethodParameter;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WildcardParamResolverTest {

    @Test
    void wildcardShouldRemoveRootPart() {
        assertThat(resolve("/root/some/name.txt"), is("some/name.txt"));
    }
    @Test void wildcardShouldRetainEndingSlash() {
        assertThat(resolve("/root/some/path/"), is("some/path/"));
    }

    private static String resolve(String path) {
        final WildcardParamResolver resolver = new WildcardParamResolver(new AntPathMatcher());
        final MethodParameter param = Mockito.mock(MethodParameter.class);
        final BindingContext context = Mockito.mock(BindingContext.class);
        final ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);

        final String patternAttr = "/root/**";

        when(exchange.getAttribute(any(String.class))).thenAnswer(inv -> {
            if(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE.equals(inv.getArgument(0))) return PathPatternParser.defaultInstance.parse(patternAttr);
            if(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE.equals(inv.getArgument(0))) return path;
            return null;
        });

        //noinspection DataFlowIssue
        return resolver.resolveArgument(param, context, exchange).map(Object::toString).block();
    }
}