package net.microstar.spring.webflux;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.webflux.authorization.UserTokenMethodArgumentResolver;
import net.microstar.spring.webflux.settings.DynamicPropertiesMethodArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Slf4j
@RequiredArgsConstructor
@Configuration
public class WebConfiguration implements WebFluxConfigurer {
    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        configurer.addCustomResolver(new DynamicPropertiesMethodArgumentResolver());
        configurer.addCustomResolver(new UserTokenMethodArgumentResolver());
        configurer.addCustomResolver(new WildcardParamResolver(new AntPathMatcher()));
    }
}