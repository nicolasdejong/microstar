package net.microstar.statics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.spring.DataStores;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.webflux.settings.client.SettingsService;
import net.microstar.spring.webflux.util.FluxUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import javax.annotation.Nullable;
import java.io.IOException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("SpellCheckingInspection")
class ResourcesProxyTest {
    private static final String settingsText = """
        microstar.dataStores:
          resources:
            type: memory
        
        app.config.statics:

          targets:
            - from: someDir
              to:   /testDir/
            - from: someEndpoint
              to:   http://localhost:${port}/deeper/

          userTargets:
            unknown: /public/
            default: /frontend/
            tester:  /tester/

          notFoundMapping:
            .*/test.html: /error.html
            .*/testfoo.html: /foo/error.html
            .*/testfoopath.html: /foo/

        contentTypes:
          default: application/octet-stream
          extToType:
            txt: text/plain
        """;
    private static final UserToken TOKEN_UNKNOWN = UserToken.GUEST_TOKEN;
    private static final UserToken TOKEN_USER_ABC = UserToken.builder().name("abc").email("abc@some.org").build();
    private static final UserToken TOKEN_USER_TESTER = UserToken.builder().name("tester").email("tester@some.org").build();

    @BeforeEach void setup() {
        AppSettings.handleExternalSettingsText(settingsText);
    }
    @AfterEach void cleanup() {
        DataStores.closeAll();
        DynamicPropertiesManager.clearAllState();
    }

    @Test void getFileResourceShouldReturnFileWithType() {
        final ServerWebExchange exchange = MockServerWebExchange.builder(
            MockServerHttpRequest.get("/someDir/file.txt").build()
        ).build();

        final BlockingDataStore store = BlockingDataStore.forStore(DataStores.get("resources"));
        store.write("/testDir/file.txt", "some text");

        final ResourcesProxy ctrl = createProxy();
        @Nullable final ResponseEntity<Flux<DataBuffer>> result = ctrl.getResource(exchange, TOKEN_UNKNOWN).block();

        assertThat(result, notNullValue());
        assertThat(result.getStatusCode(), is(HttpStatus.OK));
        assertThat(result.getHeaders().getFirst("Content-Type"), is("text/plain"));
        assertThat(result.getBody(), notNullValue());
        assertThat(FluxUtils.toString(result.getBody()).block(), is("some text"));
    }
    @Test void getUrlShouldProxyTheRequest() throws IOException, InterruptedException {
        final Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.setLevel(Level.INFO);

        try(final MockWebServer mockServer = new MockWebServer()) {
            final int port = mockServer.getPort();
            mockServer.enqueue(new MockResponse.Builder()
                .body("some text")
                .setHeader("Content-Type", "text/plain")
                .build());

            AppSettings.handleExternalSettingsText(settingsText
                .replace("${port}", String.valueOf(port))
            );
            final ServerWebExchange exchange = MockServerWebExchange.builder(
                MockServerHttpRequest.get("/someEndpoint/file.txt").build()
            ).build();

            final ResourcesProxy ctrl = createProxy();
            @Nullable final ResponseEntity<Flux<DataBuffer>> result = ctrl.getResource(exchange, TOKEN_UNKNOWN).block();

            assertThat(result, notNullValue());
            assertThat(result.getStatusCode(), is(HttpStatus.OK));
            assertThat(result.getHeaders().getFirst("Content-Type"), is("text/plain"));
            assertThat(result.getBody(), notNullValue());
            assertThat(FluxUtils.toString(result.getBody()).block(), is("some text"));
            assertThat(mockServer.takeRequest().getPath(), is("/deeper/file.txt"));
        }
    }
    @Test void requestPathShouldBeNormalized() {
        getStore().write("index.html", "outside public so should not be read");
        getStore().write("public/index.html", "ok public");
        getStore().write("frontend/index.html", "ok frontend");
        getStore().write("public/testResource.txt", "test-resource");
        getStore().write("notPublic.txt", "outside public so should not be read");

        assertThat(download("index.html", TOKEN_UNKNOWN), is("ok public"));
        assertThat(download("../index.html", TOKEN_UNKNOWN), is("ok public"));
        assertThat(download("testResource.txt", TOKEN_UNKNOWN), is("test-resource"));
        assertThat(download("../notPublic.txt", TOKEN_UNKNOWN), is("/public/ 404 resource for path ../notPublic.txt"));
    }
    @Test void userTargetShouldDependOnUser() {
        getStore().write("public/index.html"       , "public contents");
        getStore().write("public/deeper/foo.html"  , "public foo");
        getStore().write("public/deeper/bar.html"  , "public bar");

        getStore().write("frontend/index.html"     , "frontend contents");
        getStore().write("frontend/deeper/foo.html", "frontend foo");

        getStore().write("tester/index.html"       , "tester contents");
        getStore().write("tester/deeper/foo.html"  , "tester foo");

        assertThat(download("/index.html",      TOKEN_UNKNOWN),  is("public contents"));
        assertThat(download("/",                TOKEN_UNKNOWN),  is("public contents"));
        assertThat(download("/deeper/foo.html", TOKEN_UNKNOWN),  is("public foo"));
        assertThat(download("/deeper/bar.html", TOKEN_UNKNOWN),  is("public bar"));

        assertThat(download("/index.html",      TOKEN_USER_ABC), is("frontend contents"));
        assertThat(download("/",                TOKEN_USER_ABC), is("frontend contents"));
        assertThat(download("/deeper/foo.html", TOKEN_USER_ABC), is("frontend foo"));
        assertThat(download("/deeper/bar.html", TOKEN_USER_ABC), is("public bar"));

        assertThat(download("/index.html",      TOKEN_USER_TESTER), is("tester contents"));
        assertThat(download("/",                TOKEN_USER_TESTER), is("tester contents"));
        assertThat(download("/deeper/foo.html", TOKEN_USER_TESTER), is("tester foo"));
        assertThat(download("/deeper/bar.html", TOKEN_USER_TESTER), is("public bar"));
    }
    @Test void notFoundShouldLeadTo404FilePage() {
        getStore().write("public/404.html",     "/public/ 404 page");
        getStore().write("public/foo/404.html", "/public/foo/ 404 page");
        getStore().write("/error.html",         "/ error page");
        getStore().write("/foo/error.html",     "/foo/ error page");
        getStore().write("/foo/404.html",       "/foo/ 404 page");

        assertThat(download("/nonExisting.html",         TOKEN_UNKNOWN), is("/public/ 404 page"));
        assertThat(download("/foo/path.html",            TOKEN_UNKNOWN), is("/public/foo/ 404 page"));
        assertThat(download("/a/b/c/d/testfoo.html",     TOKEN_UNKNOWN), is("/foo/ error page"));
        assertThat(download("/foo/bar/nonExisting.html", TOKEN_UNKNOWN), is("/public/foo/ 404 page"));
        assertThat(download("/test.html",                TOKEN_UNKNOWN), is("/ error page"));
        assertThat(download("/a/testfoopath.html",       TOKEN_UNKNOWN), is("/foo/ 404 page"));
        assertThat(download("/any/where/file.html",      TOKEN_UNKNOWN), is("/public/ 404 page"));
    }

    private BlockingDataStore getStore() {
        return BlockingDataStore.forStore(DataStores.get("resources"));
    }
    private ResourcesProxy createProxy() {
        return new ResourcesProxy(new Targets(WebClient.builder()), new UserTargetsConfiguration(Mockito.mock(SettingsService.class)));
    }
    private String download(String uri, UserToken userToken) {
        final ServerWebExchange exchange = MockServerWebExchange.builder(
            MockServerHttpRequest.get(uri).build()
        ).build();

        final ResourcesProxy ctrl = createProxy();
        try {
            //noinspection DataFlowIssue -- forego null checks since this is a test only
            return FluxUtils.toString(ctrl.getResource(exchange, userToken).block().getBody()).block();
        } catch(final NotFoundException notFound) {
            return "<not found>";
        }
    }
}