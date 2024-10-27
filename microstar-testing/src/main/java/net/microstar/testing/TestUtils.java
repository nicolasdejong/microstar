package net.microstar.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;

public final class TestUtils {
    private TestUtils() {}
    public static final long CONDITION_TIMEOUT_MS = 2500;
    public static final long CONDITION_LOOP_SLEEP_MS = 25;

    public static void waitUntilCondition(BooleanSupplier conditionToWaitFor) { waitUntilCondition(conditionToWaitFor, () -> {}); }
    public static void waitUntilCondition(BooleanSupplier conditionToWaitFor, Runnable onFail) {
        if(conditionToWaitFor.getAsBoolean()) return;
        final long timeout = System.currentTimeMillis() + CONDITION_TIMEOUT_MS;
        while( System.currentTimeMillis() < timeout && !conditionToWaitFor.getAsBoolean()) sleep(CONDITION_LOOP_SLEEP_MS);
        if(!conditionToWaitFor.getAsBoolean()) {
            onFail.run();
        }
    }

    public static class TestInterruptedException extends RuntimeException {
        public TestInterruptedException() { super(); }
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch(final InterruptedException e) { // NOSONAR -- rethrown as a TestInterruptedException
            throw new TestInterruptedException();
        }
    }
    public static void sleep(Duration d) { sleep(d.toMillis()); }
}
