package net.microstar.dispatcher.filter;

import net.microstar.spring.application.AppSettings;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MappingsWebFilterTest {
    private static final String settingsText = """
        app.config.dispatcher:
          mappings:
            /somePath: /someOtherPath
            ts:        test-server
            /bar:      /foobar
            foo:       /bar
            /endless:  /loop
            /loop:     /endless
            ">literal?foo":  /fooTarget
            /absolute: https://some-absolute/path
        """;

    @AfterEach void cleanupProperties() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void testMappings() {
        AppSettings.handleExternalSettingsText(settingsText);

        assertThat(mapPath("/somePath/"),        is("/someOtherPath/"));
        assertThat(mapPath("/somePath/abc/def"), is("/someOtherPath/abc/def"));
        assertThat(mapPath("/foo/a/b"),          is("/foobar/a/b")); // two mappings combined
        assertThat(mapPath("/endless/foo"),      is("/endless/foo")); // loop
        assertThat(mapPath("/other"),            is("/other"));
        assertThat(mapPath("/literal?foo"),      is("/fooTarget"));
        assertThat(mapPath("/ts/time/costs-set/costs1"),  is("/test-server/time/costs-set/costs1")); // replace only once
        assertThat(mapPath("/a/b/costs/time/costs-set/costs1"),  is("/a/b/costs/time/costs-set/costs1")); // don't replace rest of url
        assertThat(mapRequest("/absolute/rest").getHeaders().getFirst(MappingsWebFilter.REMAP_PROXY_KEY), is("https://some-absolute/path/rest"));
        assertThat(mapPath("/absolute/rest"),  is("/absolute/rest")); // no url change as the redirect header is set
    }
    @Test void shouldIgnoreMappingsHeader() {
        AppSettings.handleExternalSettingsText(settingsText);

        final ServerHttpRequest req = mapRequest(MockServerWebExchange.builder(
            MockServerHttpRequest.get("/").header(MappingsWebFilter.REMAP_PROXY_KEY, "http://some.domain").build()
        ).build());
        assertThat(req.getPath().value(), is("/"));
        assertFalse(req.getHeaders().containsKey(MappingsWebFilter.REMAP_PROXY_KEY));
    }

    private static String mapPath(String uri) { return mapRequest(uri).getPath().value(); }
    private static ServerHttpRequest mapRequest(String uri) {
        return mapRequest(MockServerWebExchange.builder(
            MockServerHttpRequest.get(uri).build()
        ).build());
    }
    private static ServerHttpRequest mapRequest(ServerWebExchange exchange) {
        final MappingsWebFilter mappingFilter = new MappingsWebFilter();
        final ServerWebExchange[] result = { null };
        final WebFilterChain chain = new WebFilterChain() {
            @Override @Nonnull
            public Mono<Void> filter(@Nonnull ServerWebExchange swe) {
                result[0] = swe;
                return Mono.empty();
            }
        };
        mappingFilter.filter(exchange, chain).block();

        return result[0].getRequest();
    }
}