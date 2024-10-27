package net.microstar.spring.webflux;

import net.microstar.spring.MicrostarSpring;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
  *  Marker to use in the @ComponentScan to mark this package for scanning,
  *  which is easier to understand than a random class from the package.
  *  This way it is also easier to see who is scanning for this package (middle click).
  */
@Configuration // ComponentScan will only work if this is a configuration class
@ComponentScan(basePackageClasses = { MicrostarSpringWebflux.class, MicrostarSpring.class })
public class MicrostarSpringWebflux {}
