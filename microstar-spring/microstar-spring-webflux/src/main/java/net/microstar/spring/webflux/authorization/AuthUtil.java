package net.microstar.spring.webflux.authorization;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.spring.HttpHeadersFacade;
import net.microstar.spring.authorization.UserToken;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.Optional;

@Slf4j
public final class AuthUtil {
    private AuthUtil() {}

    /** The user token must be given in the request as http-header.
      * Websockets cannot set headers but headers can be encoded in the url (see UriToHeadersWebFilter)
      * If the request is holding the cluster secret and no token is provided, the SERVICE_TOKEN is set.
      */
    public static UserToken userTokenFrom(ServerWebExchange exchange) {
        return        UserToken.from(new HttpHeadersFacade(exchange.getRequest().getHeaders()))
            .or(() -> Optional.of(UserToken.SERVICE_TOKEN).filter(tok -> isRequestHoldingSecret(exchange)))
            .orElse(UserToken.GUEST_TOKEN);
    }

    public static boolean isRequestHoldingSecret(ServerWebExchange exchange) {
        return isRequestHoldingSecret(exchange.getRequest());
    }
    public static boolean isRequestHoldingSecret(ServerHttpRequest request) {
        return Optional.ofNullable(request.getHeaders().getFirst(MicroStarConstants.HEADER_X_CLUSTER_SECRET))
            .filter(MicroStarConstants.CLUSTER_SECRET::equals)
            .isPresent();
    }
}
