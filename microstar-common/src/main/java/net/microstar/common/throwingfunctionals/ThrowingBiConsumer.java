package net.microstar.common.throwingfunctionals;

import net.microstar.common.exceptions.WrappedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.function.BiConsumer;

@FunctionalInterface
public interface ThrowingBiConsumer<T,U> extends BiConsumer<T,U> {

    default void accept(T t, U u) {
        try {
            acceptThrows(t, u);
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

    @SuppressWarnings({"ProhibitedExceptionDeclared", "RedundantThrows"}) // allowed because it is a wrapper
    void acceptThrows(T t, U u) throws Exception; // NOSONAR -- utility

    @Override
    default ThrowingBiConsumer<T, U> andThen(BiConsumer<? super T, ? super U> after) {
        Objects.requireNonNull(after);

        return (t, u) -> {
            accept(t, u);
            after.accept(t, u);
        };
    }
}
