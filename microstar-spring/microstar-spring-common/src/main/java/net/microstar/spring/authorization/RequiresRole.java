package net.microstar.spring.authorization;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Annotation to check if these roles (default: OR) exist for current UserToken or else returns 403 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresRole {
    /** Role(s) that are required (or=true means any role is ok, or=false means all roles are required */
    String[] value() default {};
    boolean or() default true;
}
