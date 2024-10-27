package net.microstar.common.util;

import net.microstar.common.throwingfunctionals.ThrowingRunnable;
import net.microstar.common.throwingfunctionals.ThrowingSupplier;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ExceptionUtils {
    private ExceptionUtils() {}

    /** Sometimes an operation either succeeds or doesn't, and we're not interested in the reason for failure
      *
      * @return true if runnable finished without throwing, false otherwise.
      */
    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    public static boolean noThrow(ThrowingRunnable runnable) {
        try {
            runnable.runThrows();
            return true;
        } catch(final Exception ignored) {
            return false;
        }
    }

    /** When runner throws, the exception handler is called.
      *
      * @return true if runnable finished without throwing, false otherwise.
      */
    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    public static boolean noThrow(ThrowingRunnable runnable, Consumer<Exception> exceptionHandler) {
        try {
            runnable.runThrows();
            return true;
        } catch(final Exception ex) {
            exceptionHandler.accept(ex);
            return false;
        }
    }

    /** Sometimes an operation either succeeds or doesn't, and we're not interested in the reason for failure */
    public static <T> Optional<T> noThrow(ThrowingSupplier<T> supplier) {
        try {
            return Optional.ofNullable(supplier.getThrows());
        } catch(final Exception ignored) {
            return Optional.empty(); // pity that an optional cannot have a reason for being empty
        }
    }

    /** When supplier throws call the handler and return empty, otherwise return the result as an optional */
    public static <T> Optional<T> noThrow(ThrowingSupplier<T> supplier, Consumer<Exception> exceptionHandler) {
        try {
            return Optional.ofNullable(supplier.getThrows());
        } catch(final Exception cause) {
            exceptionHandler.accept(cause);
            return Optional.empty();
        }
    }

    /** When supplier throws, use the mapper to either (re)throw or map to a specific value */
    public static <T> T noThrowMap(ThrowingSupplier<T> supplier, Function<Exception,T> exceptionMapper) {
        try {
            return Objects.requireNonNull(supplier.getThrows());
        } catch(final Exception toBeMapped) {
            return exceptionMapper.apply(toBeMapped);
        }
    }

    /** Like noThrow() but all checked exceptions will be wrapped in a WrappedException
      * so no mandatory 'throws' or 'catch' is needed for methods that throw checked
      * exceptions. Note that IOException will be wrapped in an UncheckedIOException.
      */
    public static void noCheckedThrow(ThrowingRunnable runnable) {
        runnable.run();
    }

    /** Like noThrow() but all checked exceptions will be wrapped in a WrappedException
      * so no mandatory 'throws' or 'catch' is needed for methods that throw checked
      * exceptions. Note that IOException will be wrapped in an UncheckedIOException.
      */
    public static <T> T noCheckedThrow(ThrowingSupplier<T> supplier) {
        return Objects.requireNonNull(supplier.get());
    }

    /** When supplier throws, use the mapper to convert to a different exception which will be thrown */
    public static <T> T rethrow(ThrowingSupplier<T> supplier, Function<Exception,? extends RuntimeException> exceptionMapper) {
        try {
            return Objects.requireNonNull(supplier.getThrows());
        } catch(final Exception toBeMapped) {
            throw exceptionMapper.apply(toBeMapped);
        }
    }
}
