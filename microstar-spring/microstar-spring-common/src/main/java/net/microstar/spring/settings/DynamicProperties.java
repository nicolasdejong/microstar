package net.microstar.spring.settings;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Dynamic counterpart of the @ConfigurationProperties.
 * Classes that are annotated by DynamicProperties will be created using Jackson
 * ObjectMapper so should be @Builder and @Jacksonized
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DynamicProperties {

    @AliasFor("prefix")
    String value() default "";

    @AliasFor("value")
    String prefix() default "";

    boolean changeRequiresRestart() default false;
}
