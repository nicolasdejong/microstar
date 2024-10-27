package net.microstar.common.util;

import net.microstar.common.throwingfunctionals.ThrowingRunnable;
import net.microstar.common.throwingfunctionals.ThrowingSupplier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExceptionUtilsTest {
    private static final ThrowingSupplier<String> supplyThrowIOException           = () -> { throw new IOException("test"); };
    private static final ThrowingSupplier<String> supplyThrowIllegalStateException = () -> { throw new IllegalStateException("test"); };
    private static final ThrowingRunnable            runThrowIOException           = () -> { throw new IOException("test"); };
    private static final ThrowingRunnable            runThrowIllegalStateException = () -> { throw new IllegalStateException("test"); };

    @Test void testNoThrowRunnable() {
        final boolean[] called = { false };

        assertThat(ExceptionUtils.noThrow(runThrowIOException), is(false));
        assertThat(ExceptionUtils.noThrow(() -> { called[0] = true; }), is(true));
        assertThat("Not called!", called[0], is(true));
    }
    @Test void testNoThrowRunnableWithExceptionHandler() {
        final boolean[] handled = { false };
        final boolean[] called = { false };

        assertThat(ExceptionUtils.noThrow(runThrowIOException, ex -> {
                handled[0] = true;
                assertThat(ex, instanceOf(IOException.class));
                assertThat(ex.getMessage(), is("test"));
            }),
            is(false));
        assertThat("Exception was not handled!", handled[0], is(true)); handled[0] = false;

        assertThat(ExceptionUtils.noThrow(runThrowIllegalStateException, ex -> {
                handled[0] = true;
                assertThat(ex, instanceOf(IllegalStateException.class));
                assertThat(ex.getMessage(), is("test"));
            }),
            is(false));
        assertThat("Exception was not handled!", handled[0], is(true)); handled[0] = false;

        ExceptionUtils.noThrow(() -> called[0] = true);
        assertThat("Not called!", called[0], is(true));
    }
    @Test void testNoThrowSupplier() {
        assertThat(ExceptionUtils.noThrow(supplyThrowIOException), is(Optional.empty()));
        assertThat(ExceptionUtils.noThrow(supplyThrowIllegalStateException), is(Optional.empty()));
        assertThat(ExceptionUtils.noThrow(() -> "abc"), is(Optional.of("abc")));
    }
    @Test void testNoThrowSupplierWithExceptionHandler() {
        final boolean[] handled = { false };

        assertThat(ExceptionUtils.noThrow(supplyThrowIOException, ex -> {
                handled[0] = true;
                assertThat(ex, instanceOf(IOException.class));
                assertThat(ex.getMessage(), is("test"));
            }),
            is(Optional.empty()));
        assertThat("Exception was not handled!", handled[0], is(true)); handled[0] = false;
        assertThat(ExceptionUtils.noThrow(supplyThrowIllegalStateException, ex -> {
                handled[0] = true;
                assertThat(ex, instanceOf(IllegalStateException.class));
                assertThat(ex.getMessage(), is("test"));
            }),
            is(Optional.empty()));
        assertThat("Exception was not handled!", handled[0], is(true)); handled[0] = false;

        assertThat(ExceptionUtils.noThrow(() -> "abc"), is(Optional.of("abc")));
    }
    @Test void testNoThrowSupplierWithExceptionMapper() {
        assertThat(ExceptionUtils.noThrowMap(supplyThrowIOException, ex -> {
                assertThat(ex, instanceOf(IOException.class));
                assertThat(ex.getMessage(), is("test"));
                return "abc";
            }),
            is("abc"));
        assertThat(ExceptionUtils.noThrowMap(() -> "abc", ex -> "def"), is("abc"));
    }
    @Test void testRethrow() {
        assertThrows(IllegalStateException.class, () -> ExceptionUtils.rethrow(supplyThrowIOException, ex -> {
                assertThat(ex, instanceOf(IOException.class));
                assertThat(ex.getMessage(), is("test"));
                return new IllegalStateException("test");
            }));
        assertThat(ExceptionUtils.noThrowMap(() -> "abc", ex -> "def"), is("abc"));
    }

    @Test void testNoCheckedThrowRunnable() {
        assertThrows(RuntimeException.class,      () -> ExceptionUtils.noCheckedThrow(() -> { throw new IOException("test"); }));
        assertThrows(IllegalStateException.class, () -> ExceptionUtils.noCheckedThrow(() -> { throw new IllegalStateException("test"); }));
    }
    @SuppressWarnings("ConstantConditions") // if(true) is needed for return type
    @Test void testNoCheckedThrowSupplier() {
        assertThrows(RuntimeException.class,      () -> ExceptionUtils.noCheckedThrow(() -> { if(true) throw new IOException("test"); return "abc"; }));
        assertThrows(IllegalStateException.class, () -> ExceptionUtils.noCheckedThrow(() -> { if(true) throw new IllegalStateException("test"); return "abc"; }));
    }
}
