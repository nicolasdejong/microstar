package net.microstar.authorization;

import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.webflux.MicrostarSpringWebflux;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackageClasses = { Authorization.class, MicrostarSpringWebflux.class})
public class Authorization extends MicroStarApplication {
    public static void main(String... args) {
        start(Authorization.class, args);
    }
}
