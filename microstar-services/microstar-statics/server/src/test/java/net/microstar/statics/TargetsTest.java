package net.microstar.statics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import net.microstar.common.datastore.DataStore;
import net.microstar.spring.DataStores;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.settings.client.SettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.CharSequenceEncoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("SpellCheckingInspection")
class TargetsTest {
    @BeforeAll static void hideDebugLogging() {
        final Logger netty = (Logger) LoggerFactory.getLogger("io.netty");
        netty.setLevel(Level.INFO);
        final Logger cse = (Logger) LoggerFactory.getLogger("org.springframework.core.codec.CharSequenceEncoder");
        cse.setLevel(Level.INFO);
    }
    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
        DataStores.closeAll();
    }

    @SuppressWarnings("HttpUrlsUsage")
    @Test void shouldCreateCorrectUrlFromRequestPath() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.statics.targets", List.of(
                Map.of(
                    "from", "prefixA",
                    "to",   "http://localhost:1234/prefixB/"
                ),
                Map.of(
                    "from", "/fromA/fromB/",
                    "to",   "http://localhost:1234/somePrefix" // deliberately missing ending slash
                ),
                Map.of(
                    "from", "/a/b/",
                    "to",   "http://localhost:1234/ab/"
                ),
                Map.of(
                    "from", "a/b/c",
                    "to",   "http://localhost:1234/"
                ),
                Map.of(
                    "from", "foobar",
                    "to",   "http://foobar.com/"
                )
            ),
            "microstar.dataStores." + DataService.DATASTORE_NAME + ".type", "memory"
        )));

        final ResourcesProxy proxy = createProxy();

        assertThat(getDataFor(proxy, "/prefixA"),           is("http://localhost:1234/prefixB/"));
        assertThat(getDataFor(proxy, "/prefixA/foo/bar"),   is("http://localhost:1234/prefixB/foo/bar"));
        assertThat(getDataFor(proxy, "/fromA/fromB/fromC"), is("http://localhost:1234/somePrefix/fromC"));
        assertThat(getDataFor(proxy, "/a/foo"),             is("/public/ 404 resource for path /a/foo"));
        assertThat(getDataFor(proxy, "/a/b/foo"),           is("http://localhost:1234/ab/foo"));
        assertThat(getDataFor(proxy, "/a/b/c/foo"),         is("http://localhost:1234/foo")); // should NOT choose a/b
        assertThat(getDataFor(proxy, "/foobar/zoo"),        is("http://foobar.com/zoo"));
        assertThat(getDataFor(proxy, "/foobarzoo"),         is("/public/ 404 resource for path /foobarzoo"));
    }
    @Test void shouldCreateCorrectPathFromRequestPath() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.statics.targets", List.of(
                Map.of(
                    "from", "prefixA",
                    "to",   "/someDir"
                ),
                Map.of(
                    "from", "prefixB",
                    "to",   "/someDir/deeper/"
                )
            ),
            "microstar.dataStores." + DataService.DATASTORE_NAME + ".type", "memory"
        )));

        final ResourcesProxy proxy = createProxy(
                "/someDir/index.html",
                "/someDir/foo/bar.txt",
                "/someDir/deeper/index.html",
                "/someDir/deeper/foo/bar.txt"
            );
        assertThat(getDataFor(proxy, "/prefixA"),             is("/someDir/index.html"));
        assertThat(getDataFor(proxy, "/prefixA/foo/bar.txt"), is("/someDir/foo/bar.txt"));
        assertThat(getDataFor(proxy, "/prefixB"),             is("/someDir/deeper/index.html"));
        assertThat(getDataFor(proxy, "/prefixB/foo/bar.txt"), is("/someDir/deeper/foo/bar.txt"));
    }
    @Test void fallbackShouldBeChosenIfNoMatches() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.statics.targets", List.of(
                Map.of(
                    "from", "prefixA",
                    "to",   "/someDir"
                )
            ),
            "microstar.dataStores." + DataService.DATASTORE_NAME + ".type", "memory",
            "app.config.statics.fallback", "/fallback/path/"
        )));

        final ResourcesProxy proxy = createProxy(
            "/someDir/index.html",
            "/fallback/path/prefixB/index.html",
            "/fallback/path/prefixB/foo/index.html"
        );
        assertThat(getDataFor(proxy, "/prefixA"),     is("/someDir/index.html"));
        assertThat(getDataFor(proxy, "/prefixB"),     is("/fallback/path/prefixB/index.html"));
        assertThat(getDataFor(proxy, "/prefixB/foo"), is("/fallback/path/prefixB/foo/index.html"));
    }

    private ResourcesProxy createProxy(String... existingPaths) {
        final DataStore store = DataStores.get(DataService.DATASTORE_NAME).get();
        Arrays.stream(existingPaths).forEach(name -> {
            noCheckedThrow(() -> store.write(name, name).get());
        });
        return new ResourcesProxy(new Targets(WebClient.builder()), new UserTargetsConfiguration(Mockito.mock(SettingsService.class))) {
            Mono<ResponseEntity<Flux<DataBuffer>>> proxy(Targets.Target target, String uri, ServerWebExchange exchange) {
                return Mono.just(ResponseEntity.ok(Flux.just(
                    CharSequenceEncoder.textPlainOnly()
                        .encodeValue(
                            target.toPathFor(uri),
                            bufferFactory,
                            ResolvableType.forClass(String.class),
                            MimeType.valueOf("text/plain"),
                            Collections.emptyMap()
                        )
                )));
            }
        };
    }
    private @Nullable String getDataFor(ResourcesProxy proxy, String url) {
        final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get(url)
        );
        try {
            //noinspection DataFlowIssue
            return proxy.getResource(exchange, UserToken.GUEST_TOKEN).block().getBody().reduce("", (a, d) -> a + d.toString(StandardCharsets.UTF_8)).block();
        } catch(final NotFoundException nf) {
            return "<not found>";
        }
    }
}
