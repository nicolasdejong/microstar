package net.microstar.spring.application;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MicroStarApplicationTest {

    @Service
    static class SomeSpringService {
        final Runnable runnable = () -> {};

        static class InnerClass {
            public Runnable getRunnable() {
                return () -> {};
            }
        }
    }

    @Test void shouldDetectSpringComponent() {
        assertThat(MicroStarApplication.objOrParentHasSpringAnnotation(new SomeSpringService().runnable), is(true));
        assertThat(MicroStarApplication.objOrParentHasSpringAnnotation(new SomeSpringService.InnerClass().getRunnable()), is(true));
        assertThat(MicroStarApplication.objOrParentHasSpringAnnotation((Runnable)() -> {}), is(false));
    }
}