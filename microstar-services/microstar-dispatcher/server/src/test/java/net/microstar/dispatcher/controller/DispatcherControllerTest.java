package net.microstar.dispatcher.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.microstar.common.conversions.ObjectMapping;
import net.microstar.dispatcher.MiniBusDispatcher;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.webflux.MiniBus;
import org.awaitility.Durations;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.time.Duration;
import java.util.function.BiFunction;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatcherControllerTest {

    @Test void whoAmIShouldGiveCurrentUser() {
        final UserToken token = UserToken.builder().id("someId").name("someName").email("someEmail").build();
        final String tokenString = token.toTokenString(Duration.ofDays(7));
        // Same as sleep(1000) but not flaky (according to Sonar). Needed for assert between 50s and 59s
        await().pollDelay(Duration.ofMillis(1)).until(() -> true);
        final DispatcherController.MeData me = run(new HttpHeaders() {{ add(UserToken.HTTP_HEADER_NAME, tokenString); }}, DispatcherController::whoAmI);
        assertThat(me.id, is("someId"));
        assertThat(me.name, is("someName"));
        assertThat(me.email, is("someEmail"));
        assertThat(me.token, is(tokenString));
        assertThat(me.tokenExpire, startsWith("6d23h59m5")); // Anything between ...50s and ...59s
    }
    @Test void whoAmIShouldGiveGuestResultWhenNoToken() {
        final DispatcherController.MeData me = run(new HttpHeaders(), DispatcherController::whoAmI);
        assertThat(me.name, is("Guest"));
        assertThat(me.tokenExpire, is("expired"));
    }

    @Test void handleMiniBusMessageShouldDeserializeCorrectly() throws ClassNotFoundException, JsonProcessingException {
        final MiniBusDispatcher miniBusDispatcher = Mockito.mock(MiniBusDispatcher.class);
        final ObjectMapper objectMapper = ObjectMapping.get();
        final MiniBus miniBus = Mockito.mock(MiniBus.class);
        final DispatcherController dc = new DispatcherController(null, null, null, null, miniBusDispatcher, objectMapper, miniBus, null);

        final MiniBus.Event event = MiniBus.Event.builder().topic("someTopic").message("someMessage").name("someName").build();

        doAnswer(inv -> {
            final MiniBus.Event evt = inv.getArgument(0);
            assertThat(evt.topic, is(event.topic));
            assertThat(evt.message, is(event.message));
            assertThat(evt.name, is(event.name));
            return null;
        }).when(miniBus).handleExternalMessage(any());

        // The minibus message is dispatched in separate thread
        dc.handleMiniBusMessage(objectMapper.writeValueAsString(event), event.getClass().getName());
        verify(miniBus, timeout(500).times(1)).handleExternalMessage(any());
    }

    private static <T> T run(HttpHeaders headers, BiFunction<DispatcherController, ServerWebExchange, T> toRun) {
        final DispatcherController ctrl = new DispatcherController(null, null, null, null, null, null, null, null);
        final ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
        final ServerHttpRequest req = Mockito.mock(ServerHttpRequest.class);
        when(exchange.getRequest()).thenReturn(req);
        when(req.getHeaders()).thenReturn(headers);
        return toRun.apply(ctrl, exchange);
    }
}