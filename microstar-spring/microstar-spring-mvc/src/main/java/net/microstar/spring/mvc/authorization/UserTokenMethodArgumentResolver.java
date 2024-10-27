package net.microstar.spring.mvc.authorization;

import jakarta.servlet.http.HttpServletRequest;
import net.microstar.spring.authorization.UserToken;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.annotation.Nullable;

public class UserTokenMethodArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return UserToken.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  @Nullable ModelAndViewContainer modelAndViewContainer,
                                  NativeWebRequest nativeWebRequest,
                                  @Nullable WebDataBinderFactory webDataBinderFactory) {
        final HttpServletRequest request = (HttpServletRequest)nativeWebRequest.getNativeRequest();
        return AuthUtil.userTokenFrom(request);
    }
}
