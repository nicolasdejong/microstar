package net.microstar.dispatcher.filter;

import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UriToHeadersWebFilterTest {

    @SuppressWarnings("cast") // lombok code redundant cast to String
    @Builder
    private static class FilterResults {
        public final String path;
        @Singular
        public final Map<String,String> headers;
    }

    private static FilterResults runFilter(String path) {
        final FilterResults.FilterResultsBuilder results = FilterResults.builder();
        final RequestPath requestPath = mock(RequestPath.class);
        final ServerHttpRequest request = mock(ServerHttpRequest.class);
        final ServerHttpRequest.Builder requestBuilder = mock(ServerHttpRequest.Builder.class);
        final ServerWebExchange exchange = mock(ServerWebExchange.class);
        final ServerWebExchange.Builder exchangeBuilder = mock(ServerWebExchange.Builder.class);
        when(requestPath.value()).thenReturn(path);

        when(request.getPath()).thenReturn(requestPath);
        when(request.mutate()).thenReturn(requestBuilder);
        when(requestBuilder.path(anyString()))
            .thenAnswer(invocation -> {
                results.path(invocation.getArgument(0));
                return invocation.getMock();
            });
        when(requestBuilder.header(anyString(),anyString()))
            .thenAnswer(invocation -> {
                results.header(invocation.getArgument(0), invocation.getArgument(1));
                return invocation.getMock();
            });
        when(requestBuilder.build()).thenReturn(request);

        when(exchange.getRequest()).thenReturn(request);
        when(exchange.mutate()).thenReturn(exchangeBuilder);
        when(exchangeBuilder.request(ArgumentMatchers.any(ServerHttpRequest.class))).thenReturn(exchangeBuilder);
        when(exchangeBuilder.build()).thenReturn(exchange);

        final UriToHeadersWebFilter filter = new UriToHeadersWebFilter();

        //noinspection ReactiveStreamsUnusedPublisher
        filter.filter(exchange, c -> Mono.empty());

        return results.build();
    }

    @Test void headersShouldBeRemovedFromUriPath() {
        assertThat(runFilter("/some/path/@(key/value)@/").path, is("/some/path/"));
        assertThat(runFilter("/some/path/@(key/value)@/more").path, is("/some/path/more"));
        assertThat(runFilter("/some/path/@(key/value)@/@(key2/value2)@/").path, is("/some/path/"));
        assertThat(runFilter("/some/path/@(key/value)@/@(key/value/with/slash)@/").path, is("/some/path/"));
    }
    @Test void headersShouldBeExtractedFromUriPath() {
        assertThat(runFilter("/some/path/@(key/value)@/").headers, is(Map.of("key", "value")));
        assertThat(runFilter("/some/path/@(key/value)@/@(key2/value2)@/").headers, is(Map.of("key", "value", "key2", "value2")));
        assertThat(runFilter("/some/path/@(key/value)@/@(key2/value/with/slash)@/").headers, is(Map.of("key", "value", "key2", "value/with/slash")));
        assertThat(runFilter("/some/path/@(spaced%20key/spaced%20value)@/").headers, is(Map.of("spaced key", "spaced value")));
    }
}