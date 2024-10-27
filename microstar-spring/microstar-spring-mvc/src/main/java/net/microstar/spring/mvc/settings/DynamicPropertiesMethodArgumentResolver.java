package net.microstar.spring.mvc.settings;

import net.microstar.spring.settings.DynamicProperties;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import javax.annotation.Nullable;

public class DynamicPropertiesMethodArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().isAnnotationPresent(DynamicProperties.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  @Nullable ModelAndViewContainer modelAndViewContainer,
                                  NativeWebRequest nativeWebRequest,
                                  @Nullable WebDataBinderFactory webDataBinderFactory) {
        return DynamicPropertiesManager.getInstanceOf(parameter.getParameterType());
    }
}
