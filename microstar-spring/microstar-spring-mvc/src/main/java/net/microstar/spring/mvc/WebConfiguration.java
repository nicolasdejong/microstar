package net.microstar.spring.mvc;

import net.microstar.spring.mvc.authorization.UserTokenMethodArgumentResolver;
import net.microstar.spring.mvc.settings.DynamicPropertiesMethodArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(new DynamicPropertiesMethodArgumentResolver());
        argumentResolvers.add(new UserTokenMethodArgumentResolver());
        argumentResolvers.add(new WildcardParamResolver(new AntPathMatcher()));
    }
}
