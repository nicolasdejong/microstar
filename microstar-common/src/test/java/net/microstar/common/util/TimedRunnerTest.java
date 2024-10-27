package net.microstar.common.util;

import net.microstar.testing.FlakyTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;


@FlakyTest("Sensitive to slow machine")
class TimedRunnerTest {
    // Some time in the future this has to be adapted to work with a temporal abstraction
    // so that tests won't fail when the machine running the tests is busy.
    // (this isn't trivial, also because TimedRunner uses a ScheduledThreadPoolExecutor)
    // For now, to prevent this, delays are set a bit higher making this test slow.
    private static final int mul = 8; // the slower/irregular the machine, the higher this number (1: local, 5: VDI, 8: to be sure on Jenkins)

    private static final Map<String,Boolean> hasRun = Collections.synchronizedMap(new HashMap<>());
    private static final AtomicInteger     runIndex = new AtomicInteger(0);
    private static final List<Long>        runTimes = new ArrayList<>();
    private static final AtomicLong       startTime = new AtomicLong(0);

    @BeforeEach void setup() {
        hasRun.clear();
        runIndex.set(0);
        runTimes.clear();
        startTime.set(now());
    }
    @AfterEach void cleanup() {
        TimedRunner.cancelAll();
    }

    @Test void decoratorShouldDecorate() {
        final AtomicInteger n = new AtomicInteger(0);
        TimedRunner.runAfterDelay("a", Duration.ofMillis(10), () -> n.addAndGet(100));
        sleep(50);
        assertThat(n.get(), is(100));
        n.set(0);

        final UnaryOperator<Runnable> decorator = r -> () -> {
            n.addAndGet(10);
            try {
                r.run();
            } catch(final Exception e) {
                n.addAndGet(1000);
            }
            n.decrementAndGet();
        };
        TimedRunner.addDecorator(decorator);

        TimedRunner.runAfterDelay("a", Duration.ofMillis(10), () -> n.addAndGet(100));
        sleep(50);
        assertThat(n.get(), is(109));
        n.set(0);

        TimedRunner.runAfterDelay("a", Duration.ofMillis(10), () -> { throw new IllegalStateException("test"); });
        sleep(50);
        assertThat(n.get(), is(1009));
        n.set(0);

        TimedRunner.removeDecorator(decorator);
        TimedRunner.runAfterDelay("a", Duration.ofMillis(10), () -> n.addAndGet(100));
        sleep(50);
        assertThat(n.get(), is(100));
    }
    @Test void runAtTimeShouldRun() {
        final String id = "a";
        triggerRun(id, LocalDateTime.now().plus(150, ChronoUnit.MILLIS));
        assertDidNotRun(id);
        sleep(50);
        assertDidNotRun(id);
        sleep(250);
        assertDidRun(id);
        triggerRun(id, LocalDateTime.now().plus(2, ChronoUnit.SECONDS));
        sleep(1500);
        assertDidNotRun(id);
        sleep(1000);
        assertDidRun(id);
    }

    @Test void runAfterTimeShouldRun() {
        final String id = "a";
        triggerRun(id, 200);
        triggerRun(id, 100);
        assertDidNotRun(id);
        sleep(40);
        assertDidNotRun(id);
        sleep(120);
        assertDidRun(id);

        triggerRun(id, 200);
        assertDidNotRun(id);
        sleep(240);
        assertDidRun(id);

        triggerRun(id, 200);
        triggerRun(id, 20);
        sleep(40);
        assertDidRun(id);
    }
    @Test void runAfterTimeIdsShouldNotInterfere() {
        triggerRun("b", 150);
        triggerRun("d", 20);
        triggerRun("c", 250);
        triggerRun("a", 50);
        TimedRunner.cancel("d");

        sleep(80);
        assertDidRun("a");
        assertDidNotRun("b", "c", "d");
        sleep(100);
        assertDidRun("b");
        assertDidNotRun("c", "d");
        sleep(100);
        assertDidRun("c");
        assertDidNotRun("d");
    }
    @Test void runAfterTimeShouldNotRunWhenRemoved() {
        triggerRun("a", 60);
        TimedRunner.cancel("a");
        sleep(80);
        assertDidNotRun("a");
    }
    @Test void cancelShouldPreventRuns() {
        triggerRun("a", 60);
        sleep(10);
        TimedRunner.cancel("a");
        sleep(100);
        assertDidNotRun("a");
    }
    @Test void cancelAllShouldPreventRuns() {
        triggerRun("a", 60);
        triggerRun("b", 100);
        sleep(10);
        TimedRunner.cancelAll();
        sleep(100);
        assertDidNotRun("a", "b");
    }

    @Test void runPeriodicallyAtFixedRateShouldHaveCorrectTiming() {
        TimedRunner.runPeriodicallyAtFixedRate("a", Duration.ofMillis(mul * 100), getRunner());
        sleep(mul * 440);
        assertRunTimes(mul * 100, mul * 200, mul * 300, mul * 400);
    }
    @Test void runPeriodicallyAtFixedRateShouldHaveCorrectInitialDelay() {
        TimedRunner.runPeriodicallyAtFixedRate("a", Duration.ofMillis(mul * 300), Duration.ofMillis(mul * 100), getRunner());
        sleep(mul * 440);
        assertRunTimes(mul * 300, mul * 400);
    }
    @Test void runPeriodicallyAtFixedRateShouldSkipSlowRuns() {
        TimedRunner.runPeriodicallyAtFixedRate("a", Duration.ofMillis(mul * 100), getRunner(2));
        sleep(mul * 7 * 110);
        assertRunTimes(
            (mul * 100),
            2 * (mul * 100), // 2 is delayed and sleep through 3 and 4 (delay is 240)
            5 * (mul * 100),
            6 * (mul * 100),
            7 * (mul * 100)
        );
    }
    @Test void runPeriodicallyAtFixedDelayShouldHaveCorrectTiming() {
        TimedRunner.runPeriodicallyAtFixedDelay("a", Duration.ofMillis(mul * 100), getRunner(2, 5));
        sleep(mul * 1110);
        assertRunTimes(
            (mul * 100),
            2 * (mul * 100),
            3 * (mul * 100) +     RUN_DELAY_MS,
            4 * (mul * 100) +     RUN_DELAY_MS,
            5 * (mul * 100) +     RUN_DELAY_MS,
            6 * (mul * 100) + 2 * RUN_DELAY_MS
        );
    }
    @Test void runPeriodicallyAtFixedDelayShouldHaveCorrectInitialDelay() {
        TimedRunner.runPeriodicallyAtFixedDelay("a", Duration.ofMillis(mul * 600), Duration.ofMillis(mul * 100), getRunner());
        sleep(mul * (600+280));
        assertRunTimes(mul * 600, mul * 700, mul * 800);
    }

    private static final int RUN_DELAY_MS = mul * 240;
    private static final Duration RUN_DELAY = Duration.ofMillis(RUN_DELAY_MS);

    private static Runnable getRunner(Integer... delayIndices) { return getRunner(Stream.of(delayIndices).collect(Collectors.toList())); }
    private static Runnable getRunner(List<Integer> delayIndices) {
        return () -> {
            runTimes.add(now() - startTime.get());
            runIndex.incrementAndGet();
            if (delayIndices.contains(runIndex.get())) sleep(RUN_DELAY);
        };
    }
    private static void assertRunTimes(Integer... expectedRunTimes) {
        // This assertThat will always fail due to time jitter, so it is only called at failure
        final Runnable fail = () -> assertThat(runTimes, is(List.of(expectedRunTimes)));
        if(expectedRunTimes.length != runTimes.size()) fail.run();
        final int allowedJitterBelow = 10 * mul;
        final int allowedJitterAbove = 30 * mul; // depends on machine ... will occasionally fail until temporal abstraction
        for(int i=0; i<expectedRunTimes.length; i++) {
            if(runTimes.get(i) < expectedRunTimes[i] - allowedJitterBelow) fail.run();
            if(runTimes.get(i) > expectedRunTimes[i] + allowedJitterAbove) fail.run();
        }
    }

    private static void assertDidNotRun(String... ids) {
        for(final String id : ids) {
            assertThat(hasRun.computeIfAbsent(id, s -> false), is(false));
        }
    }
    private static void assertDidRun(String... ids) {
        for (final String id : ids) {
            assertThat(hasRun.computeIfAbsent(id, s -> false), is(true));
            resetRun(id);
        }
    }
    private static void storeRun(String id) { hasRun.put(id, true); }
    private static void resetRun(String id) { hasRun.put(id, false); }
    private static void triggerRun(String id, int ms) { TimedRunner.runAfterDelay(id, Duration.ofMillis(ms), () -> storeRun(id)); }
    private static void triggerRun(String id, LocalDateTime dt) { TimedRunner.runAtTime(id, dt, () -> storeRun(id)); }
    private static long now() { return System.currentTimeMillis(); }
}