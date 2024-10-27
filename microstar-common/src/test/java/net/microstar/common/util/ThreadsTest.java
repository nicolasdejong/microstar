package net.microstar.common.util;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ThreadsTest {

    @Test void tasksShouldBeExecuted() {
        final AtomicInteger count = new AtomicInteger(0);
        final Runnable increaseCount = count::incrementAndGet;

        for (int i = 0; i < 10; i++) Threads.execute(increaseCount);
        for(int i=0; i<100 && count.get() != 10; i++) sleep(Duration.ofMillis(50));
        assertThat(count.get(), is(10));
    }

    @Test void thereShouldBeNoThreadMaximum() {
        final Runnable waitTwoSeconds = () -> sleep(Duration.ofSeconds(2));

        assertDoesNotThrow(() -> {
            for (int i = 0; i < 100; i++) Threads.execute(waitTwoSeconds);
        });
    }

    @Test void aCustomExceptionHandlerShouldHandleExceptions() {
        final AtomicInteger exCount = new AtomicInteger(0);
        Threads.setDefaultExceptionHandler(ex -> exCount.incrementAndGet());
        Threads.execute(() -> { throw new IllegalArgumentException("whatever"); });
        for(int i=0; i<100 && exCount.get() != 1; i++) sleep(Duration.ofMillis(50));
        assertThat(exCount.get(), is(1));
    }

    @Test void threadShouldRunPeriodically() {
        final AtomicInteger count = new AtomicInteger(0);
        final Future<Void> future = Threads.executePeriodically(Duration.ofMillis(10), true, count::incrementAndGet);
        Utils.sleep(100);
        future.cancel(false);
        final int endCount = count.get();
        assertThat(count.get(), is(Matchers.greaterThan(0))); // precision depends on how busy the machine is
        assertThat(count.get(), is(Matchers.lessThan(12)));
        Utils.sleep(20);
        assertThat(future.isDone(), is(true));
        assertThat(count.get(), is(endCount)); // really stopped?
    }
}