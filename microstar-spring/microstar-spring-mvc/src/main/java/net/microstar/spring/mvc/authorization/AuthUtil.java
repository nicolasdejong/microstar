package net.microstar.spring.mvc.authorization;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.spring.HttpHeadersFacade;
import net.microstar.spring.authorization.UserToken;

import java.util.Optional;

@Slf4j
public final class AuthUtil {
    private AuthUtil() {}

    /** The user token must be given in the request as http-header.
      * Websockets cannot set headers but headers can be encoded in the url (see UriToHeadersWebFilter)
      * If the request is holding the cluster secret and no token is provided, the SERVICE_TOKEN is set.
      */
    public static UserToken userTokenFrom(HttpServletRequest request) {
        final HttpHeadersFacade headers = new HttpHeadersFacade(request);
        return        UserToken.from(headers)
            .or(() -> Optional.of(UserToken.SERVICE_TOKEN).filter(tok -> isRequestHoldingSecret(request)))
            .orElse(UserToken.GUEST_TOKEN);
    }

    public static boolean isRequestHoldingSecret(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(MicroStarConstants.HEADER_X_CLUSTER_SECRET))
            .filter(MicroStarConstants.CLUSTER_SECRET::equals)
            .isPresent();
    }
}
