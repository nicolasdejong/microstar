package net.microstar.common.throwingfunctionals;

import net.microstar.common.exceptions.WrappedException;

import java.io.IOException;
import java.io.UncheckedIOException;

@FunctionalInterface
public interface ThrowingRunnable extends Runnable {

    @Override
    default void run() {
        try {
            runThrows();
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
    void runThrows() throws Exception; // NOSONAR -- utility method
}
