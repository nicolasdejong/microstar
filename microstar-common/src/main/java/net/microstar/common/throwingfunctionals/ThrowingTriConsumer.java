package net.microstar.common.throwingfunctionals;

import net.microstar.common.exceptions.WrappedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/** Consumer that can throw checked exceptions */
@FunctionalInterface
public interface ThrowingTriConsumer<T,U,V> {

    default void accept(T t, U u, V v) {
        try {
            acceptThrows(t, u, v);
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
    void acceptThrows(T t, U u, V v) throws Exception; // NOSONAR -- utility method

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
    default ThrowingTriConsumer<T,U,V> andThen(ThrowingTriConsumer<? super T, ? super U, ? super V> after) {
        Objects.requireNonNull(after);
        return (T t, U u, V v) -> { accept(t, u, v); after.accept(t, u, v); };
    }
}
