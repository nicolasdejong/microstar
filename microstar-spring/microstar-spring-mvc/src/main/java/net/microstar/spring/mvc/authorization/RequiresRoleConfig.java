package net.microstar.spring.mvc.authorization;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Slf4j
@Component
@RequiredArgsConstructor
public class RequiresRoleConfig implements WebMvcConfigurer {
    private final RequiresRoleInterceptor requiresRoleInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requiresRoleInterceptor).order(Ordered.LOWEST_PRECEDENCE);
    }
}
