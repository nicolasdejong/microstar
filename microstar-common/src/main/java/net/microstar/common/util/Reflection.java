package net.microstar.common.util;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;

public final class Reflection {
    private Reflection() {}

    public static Class<?> getCallerClass() {
        return getClass(getCallerStackTraceFrame().getClassName()).orElseThrow();
    }
    public static Class<?> getCallerClass(Class<?>... excludeClasses) {
        return getClass(getCallerStackTraceFrame(excludeClasses).getClassName()).orElseThrow();
    }
    public static Class<?> getCallerClass(String... excludePakOrClassPattern) {
        return getClass(getCallerStackTraceFrame(excludePakOrClassPattern).getClassName()).orElseThrow();
    }

    public static String getCallerId(Class<?>... excludeClasses) {
        final StackWalker.StackFrame sf = getCallerStackTraceFrame(excludeClasses);
        return sf.getClassName() + "." + sf.getMethodName() + ":" + sf.getByteCodeIndex();
    }
    public static StackWalker.StackFrame getCallerStackTraceFrame() {
        return getCallerStackTraceFrame(new Class<?>[0]);
    }
    public static StackWalker.StackFrame getCallerStackTraceFrame(Class<?>... excludeClasses) {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stream -> stream
            .filter(st -> st.getDeclaringClass() != Reflection.class)
            .filter(st -> !isJavaClass(st.getDeclaringClass()))
            .filter(st -> Arrays.stream(excludeClasses).noneMatch(cl -> cl.getName().equals(st.getClassName())))
            .findFirst()
            .orElseThrow()
        );
    }
    public static StackWalker.StackFrame getCallerStackTraceFrame(String... excludePakOrClassPattern) {
        final String excludeRegex =
            "^(" +
                Arrays.stream(excludePakOrClassPattern)
                    .map(pat -> pat.replace(".", "\\.").replace("*", "(.*)"))
                    .collect(Collectors.joining(")|("))
                + ")$";
        final Pattern excludePattern = Pattern.compile(excludeRegex);
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stream -> stream
            .filter(st -> !st.getClassName().equals(Reflection.class.getName()))
            .filter(st -> !Reflection.isJavaClass(st.getDeclaringClass()))
            .filter(st -> !excludePattern.matcher(st.getClassName()).matches())
            .findFirst()
            .orElseThrow(() -> new NoSuchElementException("No caller: all callers are excluded"))
        );
    }

    public static Optional<Class<?>>  getClass(String className) {
        try {
            return Optional.of(Class.forName(className));
        } catch (final ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    public static boolean isJavaClass(Class<?> clazz) {
        return isJavaClass(clazz.getName());
    }
    public static boolean isJavaClass(String className) {
        return className.startsWith("jdk.")
            || className.startsWith("java.")
            || className.startsWith("javax.")
            || className.startsWith("sun.")
            || className.contains(".internal.");
    }

    public static String stackTraceToString() { return stackTraceToString(frame -> false); }
    public static String stackTraceToString(Predicate<StackWalker.StackFrame> stopAtThisFrame) {
        return stackTraceToString(sf -> true, stopAtThisFrame);
    }
    public static String stackTraceToString(Predicate<StackWalker.StackFrame> includeThisFrame, Predicate<StackWalker.StackFrame> stopAtThisFrame) {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stream -> stream
                .filter(st -> st.getDeclaringClass() != Reflection.class)
                .takeWhile(not(stopAtThisFrame))
                .filter(includeThisFrame)
                .map(Object::toString)
                .collect(Collectors.joining("\n"))
        );
    }
    public static String stackTraceToString(Throwable t) { return stackTraceToString(t, element -> false); }
    public static String stackTraceToString(Throwable t, Predicate<StackTraceElement> stopAtThisElement) {
        return stackTraceToString(t, ste -> true, stopAtThisElement);
    }
    public static String stackTraceToString(Throwable t, Predicate<StackTraceElement> includeThisElement, Predicate<StackTraceElement> stopAtThisElement) {
        return Stream.of(t.getStackTrace())
            .takeWhile(st -> !stopAtThisElement.test(st))
            .filter(includeThisElement)
            .map(Object::toString)
            .map(s -> "    at " + s)
            .collect(Collectors.joining("\n"));
    }

    public static Field getField(String fieldName) throws NoSuchFieldException {
        final Class<?> owner = getCallerClass();
        return owner.getDeclaredField(fieldName);
    }
    public static Field getAsPublicField(String fieldName) throws NoSuchFieldException {
        return getAsPublicField(getCallerClass(), fieldName);
    }
    public static Field getAsPublicField(Class<?> owner, String fieldName) throws NoSuchFieldException {
        final Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true); // NOSONAR -- this is the point of this method: give limited access once to a private field
        return field;
    }

    /** Tests if the given object or any of its parent enclosures is annotated */
    @SafeVarargs
    public static boolean objOrParentIsAnnotatedWith(Object obj, Class<? extends Annotation>... annotations) {
        final StringBuilder cname = new StringBuilder();
        for(final String part : obj.getClass().getTypeName().split("\\$")) {
            if(part.isEmpty()) break;
            cname.append(cname.isEmpty() ? "" : "$");
            cname.append(part);

            final String cn = cname.toString();
            try {
                final Class<?> c = Class.forName(cn);
                for(final Class<? extends Annotation> anno : annotations) if(c.isAnnotationPresent(anno)) return true;
            } catch(final Throwable ignored) { /* ignored */ }
        }
        return false;
    }
}
