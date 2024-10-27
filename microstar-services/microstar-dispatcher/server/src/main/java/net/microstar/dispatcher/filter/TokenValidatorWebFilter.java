package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.UserTokenBase;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.spring.HttpHeadersFacade;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.authorization.AuthUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.Optional;

/** This filter removes the user token if it is not valid, reducing the user to GUEST */
@Slf4j
@Component
@Order(10)
@RequiredArgsConstructor
public class TokenValidatorWebFilter implements WebFilter {
    private static final DynamicPropertiesRef<DispatcherProperties> props = DynamicPropertiesRef.of(DispatcherProperties.class);

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain nextInChain) {
        if(AuthUtil.isRequestHoldingSecret(exchange)) return nextInChain.filter(exchange);

        try {
            getRawUserToken(exchange).ifPresent(tokenText -> {
                if(props.get().retractedTokens.contains(tokenText)) throw new NotAuthorizedException("Token retracted: " + tokenText).log();
                final UserToken validToken = UserToken.fromTokenString(tokenText);

                // Removing guest token from request is an optimization so later the token
                // doesn't have to be decrypted again to see if a user is guest. Only a
                // token existence check has to be performed.
                if(validToken.isGuest()) throw new NotAuthorizedException("Guest needs no token");
            });
        } catch(final UserTokenBase.NotAuthorizedException | NotAuthorizedException e) {
            return nextInChain.filter(removeInvalidToken(exchange));
        }
        return nextInChain.filter(exchange);
    }

    private static ServerWebExchange removeInvalidToken(ServerWebExchange exchange) {
        // exchange.mutate() does not support removing cookies. So never use exchange...getCookies()!
        return exchange.mutate().request(
                exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove(UserToken.HTTP_HEADER_NAME);
                        final @Nullable String cookies = headers.getFirst("Cookie");
                        if(cookies != null) headers.set("Cookie", cookies.replaceAll(UserToken.HTTP_HEADER_NAME + "=.*?(;|$)", "").trim());
                    })
                    .build())
            .build();
    }

    private static Optional<String> getRawUserToken(ServerWebExchange exchange) {
        return UserToken.raw(new HttpHeadersFacade(exchange.getRequest()));
    }
}
