package net.microstar.webfluxtester;

import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.webflux.MicrostarSpringWebflux;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackageClasses = { WebFluxTester.class, MicrostarSpringWebflux.class })
public class WebFluxTester extends MicroStarApplication {
    public static void main(String... args) {
        start(WebFluxTester.class, args);
    }
}
