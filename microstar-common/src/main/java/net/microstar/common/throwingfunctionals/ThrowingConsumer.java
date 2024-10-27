package net.microstar.common.throwingfunctionals;

import net.microstar.common.exceptions.WrappedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.Consumer;

/** Consumer that can throw checked exceptions */
@FunctionalInterface
public interface ThrowingConsumer<T> extends Consumer<T> {

    default void accept(T t) {
        try {
            acceptThrows(t);
        } catch(final RuntimeException thrown) {
            //noinspection ProhibitedExceptionThrown -- no need to wrap RuntimeException
            throw thrown;
        } catch(final IOException thrown) {
            throw new UncheckedIOException(thrown);
        } catch(final Exception thrown) {
            //noinspection ProhibitedExceptionThrown -- this is the point here: only throw unchecked exceptions
            throw WrappedException.wrap(thrown);
        }
    }

    @SuppressWarnings("ProhibitedExceptionDeclared") // allowed because it is a wrapper
    void acceptThrows(T t) throws Exception; // NOSONAR -- utility method

    /**
     * Returns a composed {@code Consumer} that performs, in sequence, this
     * operation followed by the {@code after} operation. If performing either
     * operation throws an exception, it is relayed to the caller of the
     * composed operation.  If performing this operation throws an exception,
     * the {@code after} operation will not be performed.
     *
     * @param after the operation to perform after this operation
     * @return a composed {@code Consumer} that performs in sequence this
     * operation followed by the {@code after} operation
     * @throws NullPointerException if {@code after} is null
     */
    @SuppressWarnings("LambdaUnfriendlyMethodOverload") // should return throwing type
    default ThrowingConsumer<T> andThen(ThrowingConsumer<? super T> after) {
        Objects.requireNonNull(after);
        return (T t) -> { accept(t); after.accept(t); };
    }
}
