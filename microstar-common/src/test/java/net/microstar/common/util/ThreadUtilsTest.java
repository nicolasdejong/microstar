package net.microstar.common.util;

import net.microstar.testing.FlakyTest;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

@FlakyTest("Sensitive to clock jitter")
class ThreadUtilsTest {
    // Some time in the future this has to be adapted to work with a temporal abstraction
    // so that tests won't fail when the machine running the tests is busy.
    // (this isn't trivial, also because TimedRunner uses a ScheduledThreadPoolExecutor)
    // For now, to prevent this, delays are set a bit higher making this test slow.
    //
    // The slower/irregular the machine, the higher this number (1: local, 10: VDI, 15: to be sure on Jenkins)
    // The high multiplication factor makes this test very slow (>1m). Therefore, the factor was lowered and this
    // test was annotated as flaky test, meaning a separate flag is needed (-DincludeFlakyTests) to run.
    private static final int mul = 2;

    private static long resetToCurrentTimeAndReturnDelta(long[] value) {
        final long now = System.currentTimeMillis();
        final long delta = value[0] <= 0 ? now : now - value[0];
        value[0] = now;
        return delta;
    }

    @Test void debounce() {
        final Duration debounceTime = Duration.ofMillis(mul * 100);
        final long[] lastCallTime = { 0 };
        final int[] callCount = { 0 };
        final List<Long> timesSinceLastCall = new ArrayList<>();
        final Runnable runner = () -> {
            callCount[0]++;
            final long timeSinceLastCall = resetToCurrentTimeAndReturnDelta(lastCallTime);
            timesSinceLastCall.add(timeSinceLastCall);
        };

        for(int i=0; i<20; i++) {
            ThreadUtils.debounce(debounceTime, runner);
            sleep(i == 10 ? mul * 150 : mul * 50);
        }
        assertThat(callCount[0], is(1));
        sleep(debounceTime.plusMillis(mul * 200));
        assertThat(callCount[0], is(2));

        timesSinceLastCall.forEach(timeSinceLastCall -> assertThat("debounce didn't work", timeSinceLastCall, greaterThan(debounceTime.toMillis())));
    }
    @Test void debounceShouldNotStarve() {
        final Duration debounceTime = Duration.ofMillis(mul * 100);
        final Duration maxDelay = Duration.ofMillis(mul * 500);
        final int[] callCount = { 0 };
        final Runnable runner = () -> callCount[0]++;

        for(int i=0; i<21; i++) {
            ThreadUtils.debounce(debounceTime, maxDelay, runner);
            sleep(mul * 50);
        }
        assertThat(callCount[0], is(2));
    }

    @Test void debounceFuture() {
        final AtomicInteger sourceNumber = new AtomicInteger(0);
        final List<Integer> targetNumbers = new ArrayList<>();
        final Supplier<CompletableFuture<Integer>> supplier = () -> CompletableFuture.completedFuture(sourceNumber.incrementAndGet());

        for(int i=0; i<4+2; i++) {
            ThreadUtils.debounceFuture(Duration.ofMillis(250), supplier).thenAccept(num -> {
                synchronized(targetNumbers) { targetNumbers.add(num); }
            });
            sleep(i == 3 ? 300 : 10);
        }
        sleep(300);
        assertThat(targetNumbers, is(List.of(1,1,1,1, 2,2)));
    }

    final AtomicInteger num = new AtomicInteger(0);
    private void increaseNumThrottled() {
        ThreadUtils.throttle(Duration.ofMillis(mul * 100), num::incrementAndGet);
    }

    @Test void throttle() {
        num.set(0);
        for(int i=0; i<50; i++) {
            increaseNumThrottled();
            sleep(mul * 20);
        }
        assertThat(num.get(), isApproximately(10,2));
    }

    @Test void throttleReturnValueOfLastRun() {
        num.set(0);
        final String id = "a";
        final Duration throttleTime = Duration.ofMillis(mul * 100);
        final Supplier<Integer> toRun = () -> {
            num.incrementAndGet();
            return num.get();
        };
        final List<Integer> results = new ArrayList<>();
        for(int i=0; i<25; i++) {
            results.add(ThreadUtils.throttle(id, throttleTime, true, toRun));
            sleep(mul * 40);
        }
        assertThat(num.get(), isApproximately(10, 2));
        assertThat(
            results.stream().map(Object::toString).collect(Collectors.joining(",")),
            is("1,1,1,2,2,2,3,3,3,4,4,4,5,5,5,6,6,6,7,7,7,8,8,8,9")
        );
    }

    private static Matcher<Integer> isBetween(int lowInclusive, int hiInclusive) {
        return is(both(greaterThan(lowInclusive-1)).and(lessThan(hiInclusive+1)));
    }
    private static Matcher<Integer> isApproximately(int target, int allowedDeviation) {
        return isBetween(target - allowedDeviation, target + allowedDeviation);
    }
}