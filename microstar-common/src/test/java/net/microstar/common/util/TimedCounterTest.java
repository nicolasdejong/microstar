package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class TimedCounterTest {
    private final long[] currentTime = { 0 };
    private void addTime(long t) { currentTime[0] += t; }

    @SuppressWarnings("OverlyLongMethod") // step-by-step of filling the counter
    @Test void shouldSumCorrectly() { // NOSONAR -- number of asserts
        final TimedCounter counter = new TimedCounter(Duration.ofMillis(400), Duration.ofMillis(100)) {
            protected long now() { return currentTime[0]; }
        };
        // 4 buckets, each for 100ms -> [0, 0, 0, 0]

        assertThat(counter.sum(), is(0));

        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(0));

        counter.increase(); // t=0 [1, 0, 0, 0]

        assertThat(counter.sum(), is(1));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(1));

        counter.increase(); // t=0 [2, 0, 0, 0]

        assertThat(counter.sum(), is(2));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(2));

        counter.increase(); // t=50 [3, 0, 0, 0]

        assertThat(counter.sum(), is(3));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(3));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(3));

        addTime(100); // 100
        counter.increase(); // t=100 [3, 1, 0, 0]

        assertThat(counter.sum(), is(4));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(4));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(1));

        addTime(199); // 299
        counter.increase(); // t=299 [3, 1, 1, 0]

        assertThat(counter.sum(), is(5));
        assertThat(counter.sumSinceLast(Duration.ofMillis(500)), is(5));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(2));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(1));

        addTime(100); // 399 [3, 1, 1, 0]

        assertThat(counter.sum(), is(5));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(1));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(0));

        addTime(100); // 499 [1, 1, 0, 0]

        assertThat(counter.sum(), is(2));
        assertThat(counter.sumSinceLast(Duration.ofMillis(300)), is(1));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(0));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(0));

        counter.increase(3); // t=499 [1, 1, 0, 3]

        assertThat(counter.sum(), is(5));
        assertThat(counter.sumSinceLast(Duration.ofMillis(300)), is(4));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(3));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(3));

        addTime(100);
        counter.increase(2); // t=599 [1, 0, 3, 2]

        assertThat(counter.sum(), is(6));
        assertThat(counter.sumSinceLast(Duration.ofMillis(300)), is(5));
        assertThat(counter.sumSinceLast(Duration.ofMillis(200)), is(5));
        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(2));

        addTime(300); // [2, 0, 0, 0]
        assertThat(counter.sum(), is(2));

        addTime(1000);
        assertThat(counter.sum(), is(0));
    }

    @Test void sumShouldTakeFractionOfOldestBucket() {
        final TimedCounter counter = new TimedCounter(Duration.ofMillis(400), Duration.ofMillis(100)) {
            protected long now() { return currentTime[0]; }
        };
        counter.increase(100);
        addTime(150);
        counter.increase(1);

        // t=150 [100, 1, 0, 0] so 50..149=100 and 0..49=1
        //
        // Getting for the last 100ms and current time 150ms means 100% of 1 and 50% of 100

        assertThat(counter.sumSinceLast(Duration.ofMillis(100)), is(51));
    }
}
