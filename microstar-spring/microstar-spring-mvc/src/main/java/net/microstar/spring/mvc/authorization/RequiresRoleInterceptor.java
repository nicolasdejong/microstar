package net.microstar.spring.mvc.authorization;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotAllowedException;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

@Slf4j
@Component
public class RequiresRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {

        final @Nullable RequiresRole requiresRole;

        if (!(handler instanceof final HandlerMethod handlerMethod)
            || (requiresRole = getRequiresRoleAnnotationOf(handlerMethod)) == null) {
            return true; // nothing to check
        }

        if(isAllowed(request, requiresRole.or(), requiresRole.value())) return true;

        throw new NotAllowedException("Requires role " + Arrays.asList(requiresRole.value()));
    }

    private static boolean isAllowed(HttpServletRequest request, boolean or, String[] requiredRoles) {
        if(AuthUtil.isRequestHoldingSecret(request)) return true; // when the secret is known, all is allowed
        final UserToken userToken = AuthUtil.userTokenFrom(request);

        return ( or && userToken.hasAnyRoles(requiredRoles))
            || (!or && userToken.hasAllRoles(requiredRoles));
    }

    private static @Nullable RequiresRole getRequiresRoleAnnotationOf(HandlerMethod handlerMethod) {
        return Optional.ofNullable(handlerMethod.getMethodAnnotation(RequiresRole.class))
            .orElseGet(() -> handlerMethod.getBeanType().getAnnotation(RequiresRole.class));
    }
}