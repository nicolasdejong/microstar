package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ListenersTest {

    @Test void shouldCallListener() {
        final AtomicInteger callCount = new AtomicInteger(0);
        final Listeners<String,Void> listeners = new Listeners<>();
        listeners.add(value -> {
            switch(callCount.incrementAndGet()) {
                case 1: assertThat(value, is("a")); break;
                case 2: assertThat(value, is("b")); break;
                default: fail("Unexpected call count: " + callCount.get());
            }
        });
        listeners.call("a");
        listeners.call("b");
        assertThat(callCount.get(), is(2));
    }
    @Test void shouldUseExtraData() {
        final Listeners<String,Integer> listeners = new Listeners<>() {
            protected void call(Consumer<String> listener, @Nullable Integer extra, String value, @Nullable Integer current) {
                listener.accept(value + extra);
            }
        };
        listeners.add(123, value -> {
            assertThat(value, is("foo123"));
        });
        listeners.call("foo");
    }
    @Test void shouldDebounce() {
        final AtomicInteger callCount = new AtomicInteger(0);
        final Listeners<String,Void> listeners = new Listeners<String,Void>().withDebounce(Duration.ofMillis(100));
        listeners.add(value -> callCount.incrementAndGet());

        // Debounce calls the listener <time> after last call. This is hard to test
        // because the machine that is testing this may be slow. The debounce is used
        // from ThreadUtils where the debounce is already unit-tested.
        // Here just a basic test is added to make sure the listeners use the debounce correctly.
        listeners.call("value");
        listeners.call("value");
        listeners.call("value");
        listeners.call("value");
        Utils.sleep(500); // long because machine may be slow
        assertThat(callCount.get(), is(1));
    }
}