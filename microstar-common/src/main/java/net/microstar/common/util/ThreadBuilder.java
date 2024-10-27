package net.microstar.common.util;

import net.microstar.common.exceptions.WrappedException;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.function.BiConsumer;

/** Simple builder for long-lived Threads. Short-lived threads should be used from Threads, which uses a ThreadPool */
@SuppressWarnings({"unused", "CallToPrintStackTrace", "NonBooleanMethodNameMayNotStartWithQuestion"})
// these suppression are because it is a builder
public class ThreadBuilder {
    private static final String DEFAULT_THREAD_NAME = "BuiltThread-";
    @Nullable
    private ThreadGroup threadGroup = null; // NOSONAR -- just supporting Thread constructors
    @Nullable
    private String   name = null;
    private int      priority = Thread.NORM_PRIORITY;
    private boolean  isDaemon = false;
    private Duration initialDelay = Duration.ZERO;
    private Thread.UncaughtExceptionHandler exceptionHandler = (thread, ex) -> ex.printStackTrace(); // NOSONAR -- print is fallback
    private Runnable runnable = () -> {};
    private Runnable runnableBefore = () -> {};
    private Runnable runnableAfter = () -> {};
    private int      threadIndex = 1;

    public ThreadBuilder threadGroup(@Nullable ThreadGroup group) { this.threadGroup = group; return this; } // NOSONAR -- just supporting Thread constructors
    public ThreadBuilder toRun(Runnable toRun)        { this.runnable = toRun; return this; }
    public ThreadBuilder toRunBefore(Runnable toRun)  { this.runnableBefore = toRun; return this; }
    public ThreadBuilder toRunAfter(Runnable toRun)   { this.runnableAfter = toRun; return this; }
    public ThreadBuilder initialDelay(Duration delay) { this.initialDelay = delay; return this; }
    public ThreadBuilder name(String setName)         { this.name = setName; return this; }
    public ThreadBuilder priority(int setPriority)    { this.priority = setPriority; return this; }
    public ThreadBuilder isDaemon(boolean set)        { this.isDaemon = set; return this; }
    public ThreadBuilder setUncaughtExceptionHandler(BiConsumer<Thread,Throwable> handler) {
        exceptionHandler = handler::accept;
        return this;
    }

    public ThreadBuilder() {}
    public ThreadBuilder(Runnable toRun) {
        runnable = toRun;
    }

    public Thread run(Runnable toRun) {
        final Thread thread = build(toRun);
        thread.start();
        return thread;
    }
    public Thread start() {
        return run(runnable);
    }
    public Thread build() {
        return build(runnable);
    }

    private Runnable createRunnableFor(Runnable toRun) {
        return () -> {
            if(!initialDelay.isZero()) {
                try {
                    Thread.sleep(initialDelay.toMillis());
                } catch (final InterruptedException ex) { // NOSONAR -- before anything is running
                    throw new WrappedException(new InterruptedException("Initial thread delay interrupted"));
                }
            }
            try {
                runnableBefore.run();
                toRun.run();
            } finally {
                runnableAfter.run();
            }
        };
    }

    private Thread build(Runnable toRun) {
        final Thread thread = new Thread(threadGroup, createRunnableFor(toRun));
        thread.setName(name == null ? DEFAULT_THREAD_NAME + threadIndex++ : name);
        thread.setPriority(priority);
        thread.setDaemon(isDaemon);
        thread.setUncaughtExceptionHandler(exceptionHandler);
        return thread;
    }
}
