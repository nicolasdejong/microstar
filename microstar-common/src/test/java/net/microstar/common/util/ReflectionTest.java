package net.microstar.common.util;

import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.springframework.core.annotation.Order;
import org.springframework.test.annotation.Repeat;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings({"unused", "FieldMayBeFinal"})
class ReflectionTest {
    public String somePublicField = "public";
    private String somePrivateField = "private";

    @Test void getCallerClassShouldReturnThisClass() {
        assertThat(Reflection.getCallerClass(), is(getClass()));
        assertThat(Reflection.getCallerClass(getClass()), is(ReflectionUtils.class));
        assertThat(Reflection.getCallerClass("*.common.*"), is(ReflectionUtils.class));
    }
    @Test void isJavaClassShouldCorrectlyDetectJavaClasses() {
        assertThat(Reflection.isJavaClass(Reflection.class), is(false));
        assertThat(Reflection.isJavaClass(String.class), is(true));
        assertThat(Reflection.isJavaClass("javax.some.Type"), is(true));
    }
    @Test void stackTraceToStringShouldNotContainUtilityFrames() {
        assertThat(Reflection.stackTraceToString(), not(containsString("Reflection.stackTraceToString")));
    }
    @Test void stackTraceToStringShouldContainCallingFrame() {
        assertThat(Reflection.stackTraceToString(), containsString(".ReflectionTest.stackTraceToStringShould"));
    }
    @Test void stackTraceToStringShouldContainFullTrace() {
        assertThat(Reflection.stackTraceToString(), containsString("org.junit."));
    }
    @Test void stackTraceToStringFilteredShouldHaveLimitedTrace() {
        assertThat(Reflection.stackTraceToString(stackFrame -> stackFrame.toString().contains(".junit.")).split("\n").length, is(1));
        assertThat(Reflection.stackTraceToString(stackFrame -> !Reflection.isJavaClass(stackFrame.getDeclaringClass())), not(containsString("java.base")));
    }
    @Test void stackTraceOfThrowableShouldContainFullTrace() {
        assertThat(Reflection.stackTraceToString(new Exception("test")), containsString("org.junit."));
    }
    @Test void stackTraceOfThrowableFilteredShouldContainLimitedTrace() {
        assertThat(Reflection.stackTraceToString(new Exception("test"), element -> element.toString().contains(".junit.")), not(containsString(".junit.")));
        assertThat(Reflection.stackTraceToString(new Exception("test"), element -> !Reflection.isJavaClass(element.getClassName())), not(containsString("java.base")));
    }
    @Test void getFieldShouldReturnTheField() throws NoSuchFieldException, IllegalAccessException {
        assertThat(Reflection.getField("somePublicField").get(this), is("public"));
        assertThat(Reflection.getAsPublicField("somePrivateField").get(this), is("private"));
        assertThat(Reflection.getAsPublicField("somePrivateField").canAccess(this), is(true));
    }

    @Order(1)
    private static class AnnotatedClass {
        public final Runnable runnable = () -> {};
        public Runnable getRunnable() { return () -> {}; }

        private static class InnerClass {
            public final Runnable innerRunnable = () -> {};
            public Runnable getInnerRunnable() { return () -> {}; }
        }
    }

    @Test void objOrParentIsAnnotatedWithShouldDetectAnnotations() {
        assertThat(Reflection.objOrParentIsAnnotatedWith((Runnable)() -> {}), is(false));
        assertThat(Reflection.objOrParentIsAnnotatedWith(new AnnotatedClass().runnable, Repeat.class), is(false));
        assertThat(Reflection.objOrParentIsAnnotatedWith(new AnnotatedClass().runnable, Order.class), is(true));
        assertThat(Reflection.objOrParentIsAnnotatedWith(new AnnotatedClass().getRunnable(), Order.class), is(true));
        assertThat(Reflection.objOrParentIsAnnotatedWith(new AnnotatedClass(), Order.class), is(true));
        assertThat(Reflection.objOrParentIsAnnotatedWith(new AnnotatedClass.InnerClass().innerRunnable, Order.class), is(true));
        assertThat(Reflection.objOrParentIsAnnotatedWith(new AnnotatedClass.InnerClass().getInnerRunnable(), Order.class), is(true));
    }
}