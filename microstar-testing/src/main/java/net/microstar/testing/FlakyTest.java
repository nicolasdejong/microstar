package net.microstar.testing;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** When this annotation is added to a test (class or method), the test will be skipped unless
  * an 'includeFlakyTests' env variable is set. Flaky tests are tests that sometimes (or
  * oftentimes depending on hardware/software environment) fail due to timing issues. Of
  * course, it would be best if all tests are created completely independent of environment
  * but that is not always possible or practical.
  */
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ShouldFlakyTestsBeIncludedCondition.class)
public @interface FlakyTest {
    String value() default "";
}
