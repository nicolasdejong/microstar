package net.microstar.dispatcher.services;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import net.microstar.common.conversions.ObjectMapping;
import net.microstar.common.model.ServiceId;
import net.microstar.common.model.ServiceRegistrationRequest;
import net.microstar.dispatcher.DispatcherApplication;
import net.microstar.dispatcher.model.ServiceInfo;
import net.microstar.dispatcher.model.ServiceInfoJar;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.EventEmitter.ServiceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static net.microstar.dispatcher.services.ServiceJarsManager.JarInfo.builder;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServicesTest {
    private static final ServiceJarsManager.JarInfo DUMMY_JAR = builder().name("dummy.jar").build();
    @Mock StarsManager starsManager;
    @Mock EventEmitter eventEmitter;
    @Mock Star localStar;
    @Mock ServiceProcessInfos serviceProcessInfos;

    @BeforeAll static void hideNettyDebugLogging() {
        final Logger netty = (Logger) LoggerFactory.getLogger("io.netty");
        netty.setLevel(Level.INFO);
    }

    @BeforeEach void setupLocalStar() {
        ReflectionTestUtils.setField(localStar, "url", "http://local:1234");
        when(starsManager.getLocalStar()).thenReturn(localStar);
    }
    @AfterEach void cleanupProperties() {
        DynamicPropertiesManager.clearAllState();
    }

    private Services createServices() {
        return new Services(WebClient.builder(), new DispatcherApplication(), eventEmitter, starsManager, new JarRunner(null), new PreparedResponses(ObjectMapping.get(), serviceProcessInfos), serviceProcessInfos); }
    private ServerHttpRequest createRequest(boolean isGuest) {
        final HttpHeaders headers = new HttpHeaders();
        if(!isGuest) headers.set("Cookie", "a=b; expires 2099;X-AUTH-TOKEN=someToken;");
        final ServerHttpRequest req = mock(ServerHttpRequest.class);
        when(req.getHeaders()).thenReturn(headers);
        return req;
    }

    @Test void getServiceGroupsShouldReturnUniqueServiceGroups() {
        final Services services = createServices();
        Stream.of("main/a", "group1/a", "group1/b", "group1/c/1", "group1/c/2", "group2/a", "group2/b", "group3/b", "group3/c")
            .map(ServiceId::of)
            .map(id -> new ServiceInfoJar(id, DUMMY_JAR))
            .forEach(services::register);

        assertThat(services.getServiceGroups(), hasItems("main", "group1", "group2", "group3"));
    }

    @Test void serviceVariationsShouldContainAddedServices() {
        final Services services = createServices();
        assertThat(services.getOrCreateServiceVariations(ServiceId.of("foo/bar")), is(notNullValue()));
        assertThat(services.getOrCreateServiceVariations(ServiceId.of("foo/bar")).getVariations().isEmpty(), is(true));

        final ServiceInfo si0 = new ServiceInfoJar(ServiceId.of("main/service/0"), DUMMY_JAR);
        final ServiceInfo si1 = new ServiceInfoJar(ServiceId.of("main/service/1"), DUMMY_JAR);
        services.register(si0);
        assertThat(services.getOrCreateServiceVariations(ServiceId.of("main/service")).getVariations().size(), is(1));
        services.register(si1);
        assertThat(services.getOrCreateServiceVariations(ServiceId.of("main/service")).getVariations().size(), is(2));
        assertThat(services.getOrCreateServiceVariations(ServiceId.of("main/service")).getVariations(), is(List.of(si1, si0)));
    }

    @Test void getClientForPathShouldOnlyProvidePathToRegisteredServices() {
        final Services services = createServices();
        services.register(
            ServiceRegistrationRequest.builder().id("group/service").protocol("http").listenPort(80).build(),
            new InetSocketAddress("localhost", 80)
        );

        services.register(new ServiceInfo(ServiceId.of("main/service"), Optional.of(DUMMY_JAR)) {});
        services.register(new ServiceInfo(ServiceId.of("group1/service1"), Optional.of(DUMMY_JAR)) {});

        assertThat(services.getClientForPath(HttpMethod.GET, "/group/service"  ), not(is(Mono.empty()))); // falls back to main-service
        assertThat(services.getClientForPath(HttpMethod.GET, "/group1/service1"), not(is(Mono.empty()))); // exists
        assertThat(services.getClientForPath(HttpMethod.GET, "/noGroup/unknown"), is(Mono.empty()));
        assertThat(services.getClientForPath(HttpMethod.GET, "/unknown"        ), is(Mono.empty()));

        // fallback to known service
        DynamicPropertiesManager.setProperty("app.config.dispatcher.fallback", "/group/service/foo");
        assertThat(services.getClientForPath(HttpMethod.GET, "/unknown"        ), not(is(Mono.empty())));

        // fallback to unknown service
        DynamicPropertiesManager.setProperty("app.config.dispatcher.fallback", "/unknown/unknown/foo");
        assertThat(services.getClientForPath(HttpMethod.GET, "/unknown"        ), is(Mono.empty()));
    }

    @Test void registeringAndUnregisteringShouldSendEvent() {
        final Services services = createServices();

        clearInvocations(eventEmitter);
        final ServiceInfoRegistered reg = services.register(
            ServiceRegistrationRequest.builder().id("group/service/0").protocol("http").listenPort(80).build(),
            new InetSocketAddress("localhost", 80)
        );
        verify(eventEmitter).next(argThat( (ArgumentMatcher<ServiceEvent<Map<String,?>>>) event -> {
            assertThat(event.type, is("REGISTERED"));
            assertThat(event.data, instanceOf(Map.class));
            assertThat(event.data.get("serviceId"), is("group/service/0"));
            assertThat(event.data.get("instanceId"), instanceOf(UUID.class));
            assertThat(event.data.get("url").toString(), matchesPattern("^http://.+?:80$"));
            return true;
        }));
        clearInvocations(eventEmitter);

        services.unregister(reg);
        verify(eventEmitter).next(argThat( (ArgumentMatcher<ServiceEvent<ServiceInfo>>) event -> {
            assertThat(event.type, is("UNREGISTERED"));
            assertThat(event.data, instanceOf(ServiceInfo.class));
            assertThat(event.data.id.combined, is("group/service/0"));
            return true;
        }));
    }

    @Test void nonGuestShouldNotThrow() {
        final RequestInfo reqInfo = mock(RequestInfo.class);
        ReflectionTestUtils.setField(reqInfo, "serviceName", "test-service");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher.allowGuests", "false"
        )));
        Assertions.assertDoesNotThrow(() -> createServices().checkGuestAccess(reqInfo, createRequest(/*isGuest=*/false)));
    }
    @Test void guestShouldNotThrowWhenAllowed() {
        final RequestInfo reqInfo = mock(RequestInfo.class);
        ReflectionTestUtils.setField(reqInfo, "serviceName", "test-service");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher.allowGuests", "true"
        )));
        Assertions.assertDoesNotThrow(() -> createServices().checkGuestAccess(reqInfo, createRequest(/*isGuest=*/false)));
    }
    @Test void guestNotAllowedShouldThrow() {
        final RequestInfo reqInfo = mock(RequestInfo.class);
        ReflectionTestUtils.setField(reqInfo, "serviceName", "test-service");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher.allowGuests", "false"
        )));
        Assertions.assertThrows(NotAuthorizedException.class, () -> createServices().checkGuestAccess(reqInfo, createRequest(/*isGuest=*/true)));
    }
    @Test void guestNotAllowedButServiceAllowsShouldNotThrow() {
        final RequestInfo reqInfo = mock(RequestInfo.class);
        ReflectionTestUtils.setField(reqInfo, "serviceName", "test-service");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher.allowGuests", "false",
            "app.config.dispatcher.allowGuestServices", "test-service"
        )));
        Assertions.assertDoesNotThrow(() -> createServices().checkGuestAccess(reqInfo, createRequest(/*isGuest=*/true)));
    }
    @Test void guestAllowedButServiceDeniesShouldThrow() {
        final RequestInfo reqInfo = mock(RequestInfo.class);
        ReflectionTestUtils.setField(reqInfo, "serviceName", "test-service");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "app.config.dispatcher.allowGuests", "true",
            "app.config.dispatcher.denyGuestServices", "test-service"
        )));
        Assertions.assertThrows(NotAuthorizedException.class, () -> createServices().checkGuestAccess(reqInfo, createRequest(/*isGuest=*/true)));
    }
}
