package net.microstar.dispatcher.filter;

import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.services.Services;
import net.microstar.spring.exceptions.IllegalInputException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static net.microstar.common.MicroStarConstants.HEADER_X_SERVICE_ID;
import static net.microstar.common.MicroStarConstants.HEADER_X_SERVICE_UUID;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SetServiceIdInRequestFilterTest {
    private static final String SOME_ENDPOINT = "/services"; // any endpoint that does not require anything since filter works for all endpoints
    private static final String SOME_SERVICE_ID = "some/service/0";

    final Services servicesMock = Mockito.mock(Services.class);

    @Test
    void whenKnownInstanceIdIsGivenItShouldSucceed() {
        final ServiceInfoRegistered serviceInfoRegisteredMock = Mockito.mock(ServiceInfoRegistered.class);
        when(servicesMock.getServiceFrom(any(UUID.class)))
            .thenReturn(Optional.of(serviceInfoRegisteredMock)); // known UUID

        ReflectionTestUtils.setField(serviceInfoRegisteredMock, "id", ServiceId.of(SOME_SERVICE_ID));

        final SetServiceIdInRequestFilter webFilter = new SetServiceIdInRequestFilter(servicesMock);
        final WebFilterChain filterChain = filterExchange -> {
            final HttpHeaders headers = filterExchange.getRequest().getHeaders();
            assertThat(headers.getFirst(HEADER_X_SERVICE_ID), is(SOME_SERVICE_ID));
            return Mono.empty();
        };
        final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .get(SOME_ENDPOINT)
                .header(HEADER_X_SERVICE_UUID, UUID.randomUUID().toString())
        );

        webFilter.filter(exchange, filterChain).block();
    }
    @Test void whenUnknownInstanceIdIsGivenItShouldFail() {
        when(servicesMock.getServiceFrom(any(UUID.class)))
            .thenReturn(Optional.empty()); // unknown UUID

        final SetServiceIdInRequestFilter webFilter = new SetServiceIdInRequestFilter(servicesMock);
        final WebFilterChain filterChain = filterExchange -> Mono.empty();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .get(SOME_ENDPOINT)
                .header(HEADER_X_SERVICE_UUID, UUID.randomUUID().toString())
        );

        final Exception e = assertThrows(IllegalInputException.class, () -> webFilter.filter(exchange, filterChain).block());
        assertThat(e.getMessage(), containsString("Unknown instanceId"));
    }
    @Test void whenInvalidInstanceIdIsGivenItShouldFail() {
        final SetServiceIdInRequestFilter webFilter = new SetServiceIdInRequestFilter(servicesMock);
        final WebFilterChain filterChain = filterExchange -> Mono.empty();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .get(SOME_ENDPOINT)
                .header(HEADER_X_SERVICE_UUID, "illegal uuid value")
        );

        final Exception e = assertThrows(IllegalInputException.class, () -> webFilter.filter(exchange, filterChain).block());
        assertThat(e.getMessage(), containsString("Invalid UUID"));
    }
    @Test void whenNoInstanceIdIsGivenTheServiceIdHeaderShouldBeRemoved() {
        final SetServiceIdInRequestFilter webFilter = new SetServiceIdInRequestFilter(servicesMock);
        final WebFilterChain filterChain = filterExchange -> {
            final HttpHeaders headers = filterExchange.getRequest().getHeaders();
            assertThat(headers.getFirst(HEADER_X_SERVICE_ID), is(nullValue()));
            return Mono.empty();
        };
        final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .get(SOME_ENDPOINT)
                .header(HEADER_X_SERVICE_ID, "some-service-id")
        );

        webFilter.filter(exchange, filterChain).block();
    }
}