package net.microstar.common.util;

import net.microstar.common.exceptions.WrappedException;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Thread pool for small short-lived tasks. */
public final class Threads {
    private Threads() {}
    private static final int DEFAULT_CORE_POOL_SIZE = 10;
    private static final Duration DEFAULT_THREAD_KEEP_ALIVE_DURATION = Duration.ofMinutes(1);
    private static final AtomicReference<ExecutorService> exeRef = new AtomicReference<>(null);
    private static Consumer<Exception> exceptionHandler = Throwable::printStackTrace;


    public static void setDefaultExceptionHandler(@Nullable Consumer<Exception> handler) {
        exceptionHandler = handler == null ? Throwable::printStackTrace : handler;
    }

    public static void execute(Runnable toRun) {
        execute(Duration.ZERO, toRun);
    }
    public static void execute(Duration initialDelay, Runnable toRun) { execute(initialDelay, toRun, exceptionHandler); }
    public static void execute(Duration initialDelay, Runnable toRun, Consumer<Exception> useExceptionHandler) {
        getExecutor().execute(() -> {
            try {
                if (!initialDelay.isZero()) sleep(initialDelay);
                toRun.run();
            } catch(final Exception cause) {
                useExceptionHandler.accept(cause);
            }
        });
    }
    /** Call the runner periodically with delay time between. When runFirst is true, there is no delay before the first run */
    public static Future<Void> executePeriodically(Duration delay, boolean runFirst, Runnable toRun) { return executePeriodically(delay, runFirst, toRun, Throwable::printStackTrace); }
    public static Future<Void> executePeriodically(Duration delay, boolean runFirst, Runnable toRun, Consumer<Exception> useExceptionHandler) { // NOSONAR -- inner class
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicBoolean done = new AtomicBoolean(false);
        final Future<Void> future = new Future<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) {
                if(done.get()) return false;
                synchronized(stop) { stop.set(true); stop.notifyAll(); }
                return true;
            }
            @Override public boolean isCancelled() { return stop.get(); }
            @Override public boolean isDone() { return done.get(); }
            @Override public Void get() throws InterruptedException, ExecutionException {
                synchronized(done) { while(!done.get()) done.wait(); }
                return null;
            }
            @Override public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
                final long endTime = System.currentTimeMillis() + unit.toMillis(timeout);
                synchronized(done) { while(!done.get() && System.currentTimeMillis() < endTime) done.wait(1000); }
                return null;
            }
        };
        getExecutor().submit(() -> {
            try {
                if(runFirst) toRun.run();
                while(!stop.get()) {
                    synchronized (stop) { stop.wait(delay.toMillis()); }
                    if(!stop.get()) toRun.run();
                }
            } catch(final InterruptedException e) { // NOSONAR re-interrupt
                stop.set(true);
            } catch(final Exception cause) {
                useExceptionHandler.accept(cause);
            } finally {
                synchronized(done) {
                    done.set(true);
                    done.notifyAll();
                }
            }
        });
        return future;
    }

    public static ExecutorService getExecutor() {
        ExecutorService executorService = exeRef.get(); // no sync needed for read
        if(executorService == null) {
            synchronized (exeRef) { // sync needed for write
                executorService = exeRef.get(); // check if there was no concurrent write
                if(executorService == null) {
                    // TODO in JDK21: Executors.newVirtualThreadPerTaskExecutor()
                    executorService = new ThreadPoolExecutor(DEFAULT_CORE_POOL_SIZE, Integer.MAX_VALUE,
                        DEFAULT_THREAD_KEEP_ALIVE_DURATION.toMillis(), TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        new ThreadFactory() {
                            private int count = 1;
                            public Thread newThread(Runnable r) {
                                return new ThreadBuilder(r).name("Threads.pool-" + count++).build();
                            }
                        });

                    exeRef.set(executorService);
                }
            }
        }
        return executorService;
    }
    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException ex) { // NOSONAR -- before anything is running
            throw new WrappedException(new InterruptedException("Initial thread delay interrupted"));
        }
    }
}
