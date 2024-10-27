package net.microstar.testing;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** When this annotation is added to a test, the test will be skipped when a 'skipSlowTests' env variable is set */
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ShouldSlowTestsBeSkippedCondition.class)
public @interface SkipWhenNoSlowTests {}
