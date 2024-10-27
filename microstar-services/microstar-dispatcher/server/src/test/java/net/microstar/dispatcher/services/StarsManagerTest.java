package net.microstar.dispatcher.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import net.microstar.common.util.Utils;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.EventEmitter;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SuppressWarnings("NotNullFieldNotInitialized") // The fields are initialized in setup()
@Slf4j
class StarsManagerTest {
    private MockWebServer mockServerA;
    private MockWebServer mockServerB; // setup() sets this as local star
    private MockWebServer mockServerC;
    private EventEmitter mockEventEmitter;
    private StarsManager starsManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach void setup() throws IOException {
        // Works on VDI and Mac, sometimes not on Jenkins. Don't know why. There must be a
        // race condition but haven't found it yet. So, try a few times in case of failure.
        for(int tries = 1; tries <= 5; tries++) {
            mockServerA = new MockWebServer();
            mockServerB = new MockWebServer();
            mockServerC = new MockWebServer();
            mockEventEmitter = Mockito.mock(EventEmitter.class);

            DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
                "app.config.dispatcher", Map.of(
                    "url", "http://localhost:" + mockServerB.getPort(),
                    "stars", Map.of(
                        "aliveCheckInterval", "100s",
                        "instances", List.of(
                            Map.of("name", "server-A", "url", "http://localhost:" + mockServerA.getPort()),
                            Map.of("name", "server-B", "url", "http://localhost:" + mockServerB.getPort()),
                            Map.of("name", "server-C", "url", "http://localhost:" + mockServerC.getPort())
                        )
                    )
                )
            )));

            // Needed for initial 'isActive' check
            setMockResponseOn(mockServerA, 1);
            // mockServerB is local
            setMockResponseOn(mockServerC, 1);

            starsManager = new StarsManager(WebClient.builder(), mockEventEmitter);

            // Wait a bit on the 'isActive' thread so the list of active stars is ready
            try {
                waitForCondition(() -> starsManager.getActiveStars().size() == 3);
                break;
            } catch(final IllegalStateException timeout) {
                cleanup();
                log.warn("StarsManagerTest.setup failed! Retry. tries={}", tries);
            }
        }
    }
    @AfterEach void cleanup() throws IOException {
        starsManager.cleanup();
        mockServerA.shutdown();
        mockServerB.shutdown();
        mockServerC.shutdown();
        DynamicPropertiesManager.clearAllState();
    }

    @Jacksonized @Builder @EqualsAndHashCode @ToString
    private static class FooBar implements Comparable<FooBar> {
        public final String foo;
        public final String bar;

        @Override public int compareTo(@NotNull FooBar o) { return (foo + bar).compareTo(o.foo + o.bar); }
    }

    @Test void getStarShouldReturnStarFromProperties() {
        assertThat(starsManager.getStar("server-A").orElseThrow().name, is("server-A"));
        assertThat(starsManager.getStar("server-B").orElseThrow().name, is("server-B"));
        assertThat(starsManager.getStar(StarsManager.FIRST_AVAILABLE_STAR).orElseThrow().name, is("server-A"));
        assertThat(starsManager.getStar("non-existent").isPresent(), is(false));
    }
    @Test void getStarsShouldReturnStarsFromProperties() {
        assertThat(starsManager.getStars().size(), is(3));
        assertThat(starsManager.getStars().stream().map(s->s.name).toList(), is(List.of("server-A", "server-B", "server-C")));
        assertThat(starsManager.getStars().stream().map(s->s.url.replaceAll("\\D","")).toList(),
            is(List.of(String.valueOf(mockServerA.getPort()), String.valueOf(mockServerB.getPort()), String.valueOf(mockServerC.getPort()))));
    }
    @Test void getActiveStarShouldNotReturnInactiveStars() throws IOException {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        mockServerC.shutdown();

        starsManager.refresh();
        waitForCondition(() -> starsManager.getActiveStars().size() == 2);

        assertThat(starsManager.getActiveStar("server-A").orElseThrow().name, is("server-A"));
        assertThat(starsManager.getActiveStar("server-B").orElseThrow().name, is("server-B"));
        assertThat(starsManager.getActiveStar("server-C").isPresent(), is(false));
        assertThat(starsManager.getActiveStar(StarsManager.FIRST_AVAILABLE_STAR).orElseThrow().name, is("server-A"));
        assertThat(starsManager.getActiveStar("non-existent").isPresent(), is(false));
    }
    @Test void getStarsShouldReturnLocalStarOnlyWhenNoProperties() throws IOException {
        cleanup(); // this test should not use the init()
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher", Map.of(
                "url", "http://localhost:12345",
                "stars", Map.of("instances", Collections.emptyList())
            )
        )));
        starsManager = new StarsManager(WebClient.builder(), mockEventEmitter);

        assertThat(starsManager.getStars().size(), is(1));
        assertThat(starsManager.getStars().get(0).name, is("main"));
        assertThat(starsManager.getStars().get(0).url, is("http://localhost:12345"));
    }
    @Test void starsWithoutNameShouldGetUrlAsName() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher", Map.of(
                "url", "http://localhost:8080",
                "stars", Map.of(
                    "instances", List.of(
                        Map.of("url", "http://localhost:8080"),
                        Map.of("url", "http://unit.test.server/with/path"),
                        Map.of("url", "https://other.unit.test.server:1234"),
                        Map.of("url", "https://other.unit.test.server:1234/with/path")
                    )
                )
            )
        )));
        assertThat(starsManager.getStars().stream().map(s->s.name).toList(),
            is(List.of("localhost:8080", "unit.test.server/with/path", "other.unit.test.server:1234", "other.unit.test.server:1234/with/path")));
    }
    @Test void getLocalStarShouldReturnLocalStar() {
        assertThat(starsManager.getLocalStar().name, is("server-B"));
    }
    @Test void isLocalShouldCheckAgainstLocalName() {
        assertThat(starsManager.isLocal("server-A"), is(false));
        assertThat(starsManager.isLocal("server-B"), is(true));
    }
    @Test void isActiveShouldCheckAgainstActiveStars() throws IOException {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        mockServerC.shutdown();

        starsManager.refresh();
        waitForCondition(() -> starsManager.getActiveStars().size() == 2);

        assertThat(starsManager.isActive("server-A"), is(true));
        assertThat(starsManager.isActive("server-B"), is(true));
        assertThat(starsManager.isActive("server-C"), is(false));
    }
    @Test void getFirstAvailableStarShouldReturnFirstAvailableStar() {
        assertThat(starsManager.getFirstAvailableStarName(), is("server-A"));
    }

    @Test void offlineStarsShouldBeDetected() throws IOException {
        Utils.sleep(1000); // skip refresh debounce
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        mockServerC.shutdown();

        starsManager.refresh();
        waitForCondition(() -> starsManager.getActiveStars().size() == 2);

        assertThat(starsManager.getActiveStars().stream().map(s->s.name).toList(), is(List.of("server-A","server-B")));
    }
    @Test void offlineStarsShouldBeDetectedVia500() {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        mockServerC.enqueue(new MockResponse.Builder().code(500).build());

        starsManager.refresh();
        waitForCondition(() -> starsManager.getActiveStars().size() == 2);

        assertThat(starsManager.getActiveStars().stream().map(s->s.name).toList(), is(List.of("server-A","server-B")));
    }
    @Test void eventShouldBeEmittedWhenActiveStarsChange() throws IOException {
        DynamicPropertiesManager.setProperty("app.config.dispatcher.stars.notifyOfChangesDebounce", "500ms");
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        mockServerC.shutdown();

        clearInvocations(mockEventEmitter); // the setup already generated events -- clear them here
        starsManager.refresh(); // The refresh is async so not ready when this call returns
        waitForCondition(() -> starsManager.getActiveStars().size() == 2);

        // at this point the event (which is sent async) may be underway so wait a bit for
        // it to arrive at the eventEmitter.
        sleep(500);

        verify(mockEventEmitter, atLeastOnce()).next(argThat( (ArgumentMatcher<String>) event -> {
            assertThat(event, is("STARS-CHANGED"));
            return true;
        }));
    }

    @Test void relayShouldReturnResultsFromAllStars() {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        setMockResponseOn(mockServerC);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").build())
                .map(response -> response.content.orElse("NONE"))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1A", "1B", "1C")));
    }
    @Test void relayShouldReturnResultsFromLocalWhenNoStarsConfigured() {
        final MockWebServer mockServerLocal = mockServerB;
        noThrow(() -> { mockServerA.shutdown(); mockServerC.shutdown(); });
        mockServerLocal.enqueue(new MockResponse.Builder().body("1.0L").addHeader("Content-Type", "text/plain").build());
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher", Map.of(
                "url", "http://localhost:" + mockServerLocal.getPort(),
                "stars", Map.of("instances", Collections.emptyList())
            )
        )));

        mockServerLocal.enqueue(new MockResponse.Builder().body("1.0L").addHeader("Content-Type", "text/plain").build());
        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").build())
                .map(response -> response.content.orElse("NONE"))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1.0L")));
    }
    @Test void relaySingleShouldStopCallingAfterFirstSuccessfulStar() {
        setMockResponseOn(mockServerA, 1);
        setMockResponseOn(mockServerB, 1);
        setMockResponseOn(mockServerC, 1);

        final AtomicInteger callCount = new AtomicInteger(0);
        final Dispatcher dispatcher = dispatch(recordedRequest -> {
            callCount.incrementAndGet();
            return new MockResponse.Builder()
                .code(200)
                .body("AAA")
                .build();
        });
        mockServerA.setDispatcher(dispatcher);
        mockServerB.setDispatcher(dispatcher);
        mockServerC.setDispatcher(dispatcher);

        final @Nullable String resultString =
            starsManager.relaySingle(RelayRequest.forGet("version").build())
                .map(response -> response.content.orElse("NONE"))
                .block();

        assertThat(resultString, is("AAA"));
        assertThat(callCount.get(), is(1));
    }
    @Test void relaySpecificStarShouldOnlyCallThatStar() {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        setMockResponseOn(mockServerC);

        final RelayRequest relayRequest = RelayRequest.forGet("version").build();
        final Star starToCall = starsManager.getActiveStars().get(2);

        final @Nullable String response =
            starsManager.relay(starToCall, relayRequest)
                .map(resultValue -> resultValue.content.orElse("NONE"))
                .block();

        assertThat(response, is("1C"));
    }
    @Test void relayRequestSpecificStarShouldOnlyCallThatStar() {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        setMockResponseOn(mockServerC);

        final RelayRequest relayRequest = RelayRequest.forGet("version")
            .star(starsManager.getActiveStars().get(1).name)
            .build();
        final @Nullable List<String> results =
            starsManager.relay(relayRequest)
                .map(resultValue -> resultValue.content.orElse("NONE"))
                .collectList()
                .block();
        assertNotNull(results);
        assertThat(results.size(), is(1));
        assertThat(results.get(0), is("1B"));
    }
    @Test void relayShouldHandleError500() {
        setMockResponseOn(mockServerA, 1);
        mockServerB.enqueue(
            new MockResponse.Builder().code(500)
                .setHeader("content-type", "application/json")
                .body("{}")
                .build()
        );
        setMockResponseOn(mockServerC);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").build())
                .map(response -> response.status.is2xxSuccessful()
                    ? response.content.orElse("NONE")
                    : ("ERROR" + response.status.value()))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1A", "1C", "ERROR500")));
    }
    @Test void relayShouldHandleError404() {
        setMockResponseOn(mockServerA);
        mockServerB.enqueue(
            new MockResponse.Builder().code(404)
                .setHeader("content-type", "application/json")
                .body("{}")
                .build()
        );
        setMockResponseOn(mockServerC);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").build())
                .map(response -> response.status.is2xxSuccessful()
                    ? response.content.orElse("NONE")
                    : ("ERROR" + response.status.value()))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1A", "1C", "ERROR404")));
    }
    @Test void relayShouldExcludeInactiveStars() throws IOException {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        mockServerC.shutdown();

        starsManager.refresh();
        waitForCondition(() -> starsManager.getActiveStars().size() == 2);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").build())
                .map(response -> response.status.is2xxSuccessful()
                    ? response.content.orElse("NONE")
                    : ("ERROR" + response.status))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1A", "1B")));
    }
    @Test void relayShouldSendAndReceivePayloadFromAllStars() {
        setMockResponseOn(mockServerA, 1);
        setMockResponseOn(mockServerB, 1);
        setMockResponseOn(mockServerC, 1);

        final String serviceName = "some-service-name";
        final String servicePath = "/some/path";
        final Set<String> requestPaths = Collections.synchronizedSet(new HashSet<>());
        final Function<String,Dispatcher> createDispatcher = id -> dispatch(req -> {
                requestPaths.add(req.getPath());
                final FooBar foobar = map(req.getBody().readUtf8(), FooBar.class);
                return new MockResponse.Builder()
                    .code(200)
                    .body(quote("{`foo`:`FOO:" + id + ":" + foobar.foo +"`,`bar`:`BAR:" + id + ":" + foobar.bar + "`}"))
                    .addHeader("Content-Type", "application/json")
                    .build();
            });

        mockServerA.setDispatcher(createDispatcher.apply("AA"));
        mockServerB.setDispatcher(createDispatcher.apply("BB"));
        mockServerC.setDispatcher(createDispatcher.apply("CC"));

        final @Nullable List<FooBar> results =
            starsManager.relay(RelayRequest.forPost(serviceName)
                    .servicePath(servicePath)
                    .payload(FooBar.builder().foo("F1").bar("F2").build())
                    .build()
                )
                .mapNotNull(response -> response.content.flatMap(text -> noThrow(() -> objectMapper.readValue(text, FooBar.class))).orElse(null))
                .collectList()
                .block();

        assertThat(requestPaths, is(Set.of("/" + concatPath(serviceName, servicePath))));
        assertThat(sort(results), is(List.of(
            FooBar.builder().foo("FOO:AA:F1").bar("BAR:AA:F2").build(),
            FooBar.builder().foo("FOO:BB:F1").bar("BAR:BB:F2").build(),
            FooBar.builder().foo("FOO:CC:F1").bar("BAR:CC:F2").build()
        )));
    }
    @Test void relayShouldExcludeLocalStarWhenSoConfigured() {
        setMockResponseOn(mockServerA);
        setMockResponseOn(mockServerB);
        setMockResponseOn(mockServerC);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").excludeLocalStar().build())
                .map(response -> response.content.orElse("NONE"))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1A", "1C")));
    }
    @Test void relayShouldCallLocalStarForStarNamedLocal() {
        setMockResponseOn(mockServerB, 1);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forGet("version").onlyLocalStar().build())
                .map(response -> response.content.orElse("NONE"))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1B")));
    }
    @Test void relayRequestMethodShouldSetHttpMethod() {
        assertThat(RelayRequest.forGet   ("name").build().methodAsHttpMethod(), is(HttpMethod.GET));
        assertThat(RelayRequest.forPost  ("name").build().methodAsHttpMethod(), is(HttpMethod.POST));
        assertThat(RelayRequest.forPut   ("name").build().methodAsHttpMethod(), is(HttpMethod.PUT));
        assertThat(RelayRequest.forDelete("name").build().methodAsHttpMethod(), is(HttpMethod.DELETE));
    }
    @Test void relayPostShouldSupportEmptyBody() {
        setMockResponseOn(mockServerA, 1);
        setMockResponseOn(mockServerB, 1);
        setMockResponseOn(mockServerC, 1);

        final @Nullable List<String> results =
            starsManager.relay(RelayRequest.forPost("version").build())
                .map(response -> response.content.orElse("NONE"))
                .collectList()
                .block();

        assertThat(sort(results), is(List.of("1A", "1B", "1C")));
    }

    @Test void sendEventShouldSendEventToOtherStars() {
        mockServerA.enqueue(new MockResponse.Builder().code(501).build());
        mockServerB.setDispatcher(dispatch2(req -> fail("local star should not be called")));
        mockServerC.setDispatcher(dispatch(req -> {
            assertThat(req.getPath(), is("/service/path"));
            assertThat(req.getBody().readUtf8(), is("payload"));
            return new MockResponse.Builder()
                .code(200)
                .build();
        }));

        final @Nullable List<RelayResponse<String>> results =
            starsManager.sendEventToOtherStars("service", "path", "payload").block();

        assertThat(results, notNullValue());
        assertThat(results.stream().map(resp -> resp.status.value()).collect(Collectors.toSet()), is(Set.of(200,501)));
    }

    private void setMockResponseOn(MockWebServer mockServer) { setMockResponseOn(mockServer, 10); }
    private void setMockResponseOn(MockWebServer mockServer, int count) {
        final String target = mockServer == mockServerA ? "A" : mockServer == mockServerB ? "B" : "C";
        for(int i=0; i<count; i++) mockServer.enqueue(new MockResponse.Builder().body("1" + target).addHeader("Content-Type", "text/plain").build());
    }

    private static void waitForCondition(Supplier<Boolean> conditionChecker) {
        waitForCondition(Duration.ofSeconds(7), conditionChecker);
    }
    private static void waitForCondition(Duration maxWait, Supplier<Boolean> conditionChecker) {
        waitForCondition(maxWait, Duration.ofMillis(10), conditionChecker);
    }
    private static void waitForCondition(Duration maxWait, Duration interval, Supplier<Boolean> conditionChecker) {
        final long eol = System.currentTimeMillis() + maxWait.toMillis();
        while(!conditionChecker.get()) {
            sleep(interval);
            if(System.currentTimeMillis() > eol) throw new IllegalStateException("Timeout for condition");
        }
    }
    private static <T> List<T> sort(@Nullable List<T> list) { return Objects.requireNonNull(list).stream().sorted().toList(); }
    private static String quote(String s) { return s.replace("`", "\""); }
    private static <T> T map(String s, @SuppressWarnings("SameParameterValue") Class<T> type) { return noCheckedThrow(() -> new ObjectMapper().readValue(s, type)); }
    private static Dispatcher dispatch(Function<RecordedRequest, MockResponse> handler) {
        return new Dispatcher() {
            @NotNull @Override
            public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
                return handler.apply(recordedRequest);
            }
        };
    }
    private static Dispatcher dispatch2(Consumer<RecordedRequest> handler) {
        return new Dispatcher() {
            @NotNull @Override
            public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
                handler.accept(recordedRequest);
                return new MockResponse.Builder().code(200).build();
            }
        };
    }
}