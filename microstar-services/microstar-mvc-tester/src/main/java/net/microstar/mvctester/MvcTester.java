package net.microstar.mvctester;

import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.mvc.MicrostarSpringMvc;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@ComponentScan(basePackageClasses = { MvcTester.class, MicrostarSpringMvc.class})
public class MvcTester extends MicroStarApplication {
    public static void main(String... args) {
        start(MvcTester.class, args);
    }
}
