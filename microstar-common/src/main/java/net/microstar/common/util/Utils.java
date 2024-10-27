package net.microstar.common.util;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import net.microstar.common.exceptions.WrappedException;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

@SuppressWarnings("varargs")
public final class Utils {
    private Utils() {/*utility*/}

    @SafeVarargs public static <T> T           firstNotNull(T... args) {
        return firstNotNullOrElseEmpty(args).orElseThrow(() -> new NullPointerException("all args are null"));
    }
    @SafeVarargs public static <T> Optional<T> firstNotNullOrElseEmpty(T... args) {
        return Arrays.stream(args).filter(Objects::nonNull).findFirst();
    }
    @SafeVarargs public static <T> Optional<T> firstSupplyNotNullOrElseEmpty(Supplier<T>... args) {
        return Arrays.stream(args).filter(Objects::nonNull).map(Supplier::get).filter(Objects::nonNull).findFirst();
    }
    @SafeVarargs public static <T> Optional<T> firstPresent(Optional<T>... args) {
        //noinspection OptionalGetWithoutIsPresent -- false positive: get() is used after isPresent() check
        return Arrays.stream(args)
            .filter(Optional::isPresent)
            .findFirst()
            .map(Optional::get);
    }
    @SafeVarargs public static <T> Optional<T> firstPresent(Supplier<Optional<T>>... args) {
        //noinspection OptionalGetWithoutIsPresent -- false positive: get() is used after isPresent() check
        return Arrays.stream(args)
            .map(Supplier::get)
            .filter(Optional::isPresent)
            .findFirst()
            .map(Optional::get);
    }
    @SafeVarargs public static <T> T           pipe(T in, UnaryOperator<T>... handlers) {
        T value = in;
        for(final UnaryOperator<T> handler: handlers) if(value != null) value = handler.apply(value);
        return Objects.requireNonNull(value);
    }

    public static Class<?> staticThis() {
        Timestamp t;
        return Reflection.getCallerClass(Utils.class);
    }

    public static LocalDateTime convertToUtc(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
    public static LocalDateTime nowUtc() {
        return convertToUtc(LocalDateTime.now());
    }

    public static void sleep(Duration duration) { sleep(duration.toMillis()); }
    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException interrupted) { // NOSONAR -- no re-interrupt to keep this method 'throwing' free
            throw new WrappedException(interrupted);
        }
    }

    /** This method guarantees that garbage collection is done unlike <code>{@link System#gc()}</code> */
    public static void forceGarbageCollection() {
        @Nullable Object obj = new Object();
        final WeakReference<Object> ref = new WeakReference<>(obj);
        //noinspection UnusedAssignment
        obj = null; // NOSONAR -- this call clears the weak reference that will be garbage collected
        while(ref.get() != null) {
            System.gc(); // NOSONAR -- In normal code gc() should not be called -- this is for testing only
        }
    }

    public static class FailedConditionException extends RuntimeException {
        private final transient Object value;
        public FailedConditionException(Object value, String message) { super(message); this.value = value; }
        @Override public String getMessage() {
            return super.getMessage().replaceFirst("\\{\\}", Objects.toString(value));
        }
    }

    public static <T> T requireCondition(String message, T value, Predicate<T> condition) {
        if(condition.test(value)) return value;
        throw new FailedConditionException(value, message);
    }
    public static <T> T requireCondition(T value, Predicate<T> condition) {
        return requireCondition("Value {} failed to meet requirements", value, condition);
    }

    /** This method allows peeking to a call result before returning it. Typical use is logging in a chained call. */
    public static <T> T peek(T result, Consumer<T> handler) {
        handler.accept(result);
        return result;
    }
    public static @Nullable <T> T peekNullable(@Nullable T result, Consumer<T> handler) {
        handler.accept(result);
        return result;
    }

    /** Because is(a).smallerThan(b) is easier to read than (a.compareTo(b) < 0) a small compare utility class was added */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class CompareUtils<T extends Comparable<T>> {
        @Nullable public  final T value;
                  private final boolean isNot;
        @Nullable private final Comparator<T> comparator;

        public CompareUtils(T value) { this(value, false, null); }
        public CompareUtils(Comparator<T> comparator) { this(null, false, comparator); }

        public CompareUtils<T> is(T value)                { return new CompareUtils<>(value, isNot, comparator); }
        public CompareUtils<T> not()                      { return new CompareUtils<>(value, !isNot, comparator); }

        public boolean smallerThan         (T otherValue) { return compare(value, otherValue) <  0; }
        public boolean smallerThanOrEqualTo(T otherValue) { return compare(value, otherValue) <= 0; }
        public boolean equalTo             (T otherValue) { return compare(value, otherValue) == 0; }
        public boolean greaterThan         (T otherValue) { return compare(value, otherValue) >  0; }
        public boolean greaterThanOrEqualTo(T otherValue) { return compare(value, otherValue) >= 0; }

        private int compare(@Nullable T a, T b) {
            if(a == null) throw new IllegalArgumentException("Null value provided");
            final int cmp = comparator == null ? a.compareTo(b) : comparator.compare(a, b);
            return isNot ? -cmp : cmp;
        }
    }

    public static <T extends Comparable<T>> CompareUtils<T> is(T toCompare) { return new CompareUtils<>(toCompare); }
    public static <T extends Comparable<T>> CompareUtils<T> compareUsing(Comparator<T> comparator) { return new CompareUtils<>(comparator); }
}
