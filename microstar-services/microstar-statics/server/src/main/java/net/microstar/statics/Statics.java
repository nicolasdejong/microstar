package net.microstar.statics;

import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.webflux.MicrostarSpringWebflux;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackageClasses = { Statics.class, MicrostarSpringWebflux.class })
public class Statics extends MicroStarApplication {
    public static void main(String... args) {
        start(Statics.class, args);
    }
}
