package net.microstar.common.util;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class GeneratedReferenceTest {

    @Test void getShouldCallTheGenerator() {
        final GeneratedReference<String> ref = new GeneratedReference<>(() -> "abc");
        assertThat(ref.get(), is("abc"));
    }
    @Test void resetShouldLeadToCallToGenerator() {
        final int[] count = { 0 };
        final GeneratedReference<String> ref = new GeneratedReference<>(() -> count[0]++ == 0 ? "abc" : "def");
        assertThat(ref.get(), is("abc"));
        ref.reset();
        assertThat(ref.get(), is("def"));
    }

    @RepeatedTest(5)
    void generatorShouldBeCalledOnlyOnce() {
        final int[] count = { 0 };
        final GeneratedReference<String> ref = new GeneratedReference<>(() -> { count[0]++; return "abc"; });
        testConcurrentAccess(ref::get);

        if(count[0] > 1) fail("Generator called multiple times! (" + count[0] + ")");
    }

    @SuppressWarnings("MethodWithMultipleLoops")
    private static void testConcurrentAccess(Runnable toRun) {
        final Thread[] threads = new Thread[100]; // Adding threads above this doesn't increase the chance of collisions
        final int[] startCount = { 0 };
        int maxSleep = 100;

        for(int i=0; i<threads.length; i++) threads[i] = new Thread(() -> {
            startCount[0]++;
            // Significantly increase the chance of calling toRun concurrently is for
            // all threads to wait for the GO! signal before calling toRun.
            synchronized (threads) {
                try { threads.wait(1000); } catch (final InterruptedException ignored) {}
            }
            toRun.run();
        });
        for (final Thread thread : threads) thread.start();
        while(maxSleep --> 0 && startCount[0] < threads.length) sleep(1); // wait until all threads are waiting for the GO! signal
        synchronized (threads) { threads.notifyAll(); } // GO!
        for (final Thread thread : threads) { // wait until all are done
            try { thread.join(); } catch (final InterruptedException ignored) {}
        }
        if(maxSleep <= 0) System.err.println("Not all threads started!");
    }
}