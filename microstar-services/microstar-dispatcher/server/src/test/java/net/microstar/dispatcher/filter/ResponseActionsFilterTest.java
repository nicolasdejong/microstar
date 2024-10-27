package net.microstar.dispatcher.filter;

import net.microstar.spring.application.AppSettings;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResponseActionsFilterTest {
    private static final String settingsText = """
        app.config.dispatcher:
          responseActions:
            - service: someService
              status: 404
              redirect: /redirected/
            - service: someOtherService
              status: 404
              redirect: /redirectedOther/
        """;

    @AfterEach
    void cleanupProperties() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test
    void testActions() {
        AppSettings.handleExternalSettingsText(settingsText);
        final List<String> validations = new ArrayList<>();

        validateAction("/someService/", 200, (status, headers) -> {
            assertThat(status.value(), is(200));
            assertThat(headers.isEmpty(), is(true));
            validations.add("200");
        });
        validateAction("/someService/", 404, (status, headers) -> {
            assertThat(status.value(), is(302));
            assertThat(headers.getFirst("Location"), is("/redirected/"));
            validations.add("404");
        });
        validateAction("/someOtherService/", 404, (status, headers) -> {
            assertThat(status.value(), is(302));
            assertThat(headers.getFirst("Location"), is("/redirectedOther/"));
            validations.add("404.2");
        });
        assertThat(validations, is(List.of("200", "404", "404.2")));
    }

    private static void validateAction(String path, int status, BiConsumer<HttpStatus,HttpHeaders> validator) {
        final ServerWebExchange exchange = mock(ServerWebExchange.class);
        final ServerHttpRequest      req = mock(ServerHttpRequest.class);
        final ServerHttpResponse    resp = mock(ServerHttpResponse.class);
        final HttpHeaders        headers = new HttpHeaders();
        final AtomicReference<HttpStatus>           httpStatus = new AtomicReference<>(HttpStatus.valueOf(status));
        final AtomicReference<Supplier<Mono<Void>>> beforeCommitRef = new AtomicReference<>();

        when(exchange.getRequest()).thenReturn(req);
        when(req.getPath()).thenReturn(RequestPath.parse(path, ""));

        when(exchange.getResponse()).thenReturn(resp);
        when(resp.getStatusCode()).thenReturn(HttpStatus.valueOf(status));
        when(resp.getHeaders()).thenReturn(headers);
        doAnswer(invocation -> { httpStatus.set(invocation.getArgument(0)); return null; }).when(resp).setStatusCode(any(HttpStatus.class));
        doAnswer(invocation -> { beforeCommitRef.set(invocation.getArgument(0)); return null; }).when(resp).beforeCommit(any());

        final ResponseActionsFilter filter = new ResponseActionsFilter();
        final WebFilterChain chain = new WebFilterChain() {
            @Override @Nonnull
            public Mono<Void> filter(@Nonnull ServerWebExchange swe) { return Mono.empty(); }
        };
        filter.filter(exchange, chain).block();
        Optional.ofNullable(beforeCommitRef.get())
            .map(Supplier::get)
            .ifPresent(Mono::block);

        validator.accept(httpStatus.get(), headers);
    }
}