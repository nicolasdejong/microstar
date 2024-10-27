package net.microstar.common.throwingfunctionals;

import net.microstar.common.exceptions.WrappedException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;

@FunctionalInterface
public interface ThrowingSupplier<T> extends Supplier<T> {

    @Override @Nullable
    default T get() {
        try {
            return getThrows();
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
    @Nullable T getThrows() throws Exception; // NOSONAR -- utility method
}
