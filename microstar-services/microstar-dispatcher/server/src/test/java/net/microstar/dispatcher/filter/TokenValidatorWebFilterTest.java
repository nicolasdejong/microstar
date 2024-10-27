package net.microstar.dispatcher.filter;

import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class TokenValidatorWebFilterTest {
    private static final String SOME_ENDPOINT = "/services"; // any endpoint that does not require anything since filter works for all endpoints

    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void invalidUserTokenShouldBeRemoved() {
        final String tokenString = "Invalid token";
        runFilterForToken(tokenString, filterExchange -> {
            final HttpHeaders headers = filterExchange.getRequest().getHeaders();
            assertThat(headers.getFirst(UserToken.HTTP_HEADER_NAME), is(nullValue()));
            return Mono.empty();
        });
    }
    @Test void validTokenShouldBeLeftAlone() {
        final String tokenString = UserToken.builder().name("someName").build().toTokenString();
        runFilterForToken(tokenString, filterExchange -> {
            final HttpHeaders headers = filterExchange.getRequest().getHeaders();
            assertThat(headers.getFirst(UserToken.HTTP_HEADER_NAME), is(tokenString));
            return Mono.empty();
        });
    }
    @Test void validButBlacklistedTokensShouldBeRemoved() {
        final String tokenString = UserToken.builder().name("someName").build().toTokenString();
        DynamicPropertiesManager.setProperty("app.config.dispatcher.retractedTokens", Set.of(tokenString));
        runFilterForToken(tokenString, filterExchange -> {
            final HttpHeaders headers = filterExchange.getRequest().getHeaders();
            assertThat(headers.getFirst(UserToken.HTTP_HEADER_NAME), is(nullValue()));
            return Mono.empty();
        });
    }

    private void runFilterForToken(@Nullable String tokenText, WebFilterChain filterChain) {
        final TokenValidatorWebFilter webFilter = new TokenValidatorWebFilter();
        final MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest
                .get(SOME_ENDPOINT)
                .header(UserToken.HTTP_HEADER_NAME, tokenText)
        );
        webFilter.filter(exchange, filterChain).block();
    }
}
