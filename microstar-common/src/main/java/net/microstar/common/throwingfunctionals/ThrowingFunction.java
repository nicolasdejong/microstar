package net.microstar.common.throwingfunctionals;

import net.microstar.common.exceptions.WrappedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

/** Copy of  Function that allows checked exceptions to be thrown */
@FunctionalInterface
public interface ThrowingFunction<T,R> extends Function<T,R> {

    @Override
    default R apply(T t) {
        try {
            return applyThrows(t);
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

    R applyThrows(T t) throws Exception; // NOSONAR -- utility
}
