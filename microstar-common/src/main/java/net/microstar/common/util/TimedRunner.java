package net.microstar.common.util;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static net.microstar.common.util.Utils.sleep;

public final class TimedRunner {
    private static final int MINIMAL_RUNNING_THREAD_COUNT = 2;
    private static final Map<String,ScheduledFuture<?>> idToFuture = new HashMap<>();
    private static final Set<String> runningIds = new HashSet<>();
    private static final ScheduledThreadPoolExecutor executors;
    private static final AtomicReference<List<UnaryOperator<Runnable>>> decorators = new AtomicReference<>(new ArrayList<>());
    private TimedRunner() {}

    public static final UnaryOperator<Runnable> CATCH_ALL = r -> () -> {
        try {
            r.run();
        } catch(final Throwable catchAll) { // NOSONAR -- nothing to be done
            // poof, it's gone
            // What else can be done at this point?
            catchAll.printStackTrace();
        }
    };

    static {
        executors = new ScheduledThreadPoolExecutor(MINIMAL_RUNNING_THREAD_COUNT, runnable -> {
            final Thread thread = new Thread(null, runnable, "TimedRunner.subThread", 0);
            thread.setPriority(Thread.NORM_PRIORITY);
            thread.setDaemon(false);
            return thread;
        });
        executors.setRemoveOnCancelPolicy(true);
        addDecorator(CATCH_ALL);
    }

    public static void addDecorator(UnaryOperator<Runnable> threadDecorator) {
        alterDecorators(list -> list.add(0, threadDecorator));
    }
    public static void removeDecorator(UnaryOperator<Runnable> threadDecorator) {
        alterDecorators(list -> list.remove(threadDecorator));
    }
    private static void alterDecorators(Consumer<List<UnaryOperator<Runnable>>> alterer) {
        synchronized (decorators) {
            final List<UnaryOperator<Runnable>> listCopy = new ArrayList<>(decorators.get());
            alterer.accept(listCopy);
            decorators.set(listCopy);
        }
    }
    private static Runnable decorate(Runnable toRun) {
        Runnable decorated = toRun;
        for(final UnaryOperator<Runnable> decorator : decorators.get()) {
            decorated = decorator.apply(decorated);
        }
        return decorated;
    }

    /** Returns true if currently running (so id already set), false otherwise */
    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    private static boolean setRunning(String id) {
        synchronized(runningIds) {
            if(runningIds.contains(id)) return true;
            runningIds.add(id);
            return false;
        }
    }
    private static void finishedRunning(String id) {
        synchronized (runningIds) {
            runningIds.remove(id);
        }
    }


    /** Prevents running a previously scheduled run for given id */
    public static void cancel(String id) { cancel(id, /*wait=*/false, /*interrupt=*/false); }
    public static void cancel(String id, boolean wait, boolean interruptRunning) {
        synchronized(idToFuture) {
            Optional.ofNullable(idToFuture.remove(id))
                .ifPresent(future -> {
                    future.cancel(interruptRunning);
                    while(wait && !future.isDone()) sleep(25);
                });
        }
    }
    /** Removes all scheduled runners */
    public static void cancelAll() {
        synchronized(idToFuture) {
            idToFuture.values().forEach(future -> future.cancel(false));
            idToFuture.clear();
        }
    }

    /** Run at given time. If time is before now this call is ignored */
    public static void runAtTime(String id, LocalDateTime timeToRun, Runnable toRun) {
        final long currentTimeMillis = 1000L * LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) + now() % 1000;
        final long timeToRunMillis   = 1000L * timeToRun.toEpochSecond(ZoneOffset.UTC) + timeToRun.getNano()/1_000_000 % 1000;
        runAfterDelay(id, Duration.ofMillis(timeToRunMillis - currentTimeMillis), toRun);
    }

    /** Run after a given delay */
    public static void runAfterDelay(String id, Duration delay, Runnable toRun) {
        synchronized(idToFuture) {
            cancel(id);
            idToFuture.put(id, executors.schedule(decorate(toRun), delay.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    /** Run periodically, after an initial delay, with given time between run starts. If a run
      * takes longer than timeBetweenRunStarts, runs will be skipped. This way runs are always starting
      * at predictable times.<p>
      *
      * This is different from the executors.scheduleAtFixedRate() which will run multiple times when
      * a run takes longer than timeBetweenRunStarts (so that algorithm has a fixed number of runs
      * in time divided by timeBetweenRunStarts while this algorithm has the start time always at
      * startTime modulo timeBetweenRunStarts).<p>
      */
    public static void runPeriodicallyAtFixedRate(String id, Duration initialDelay, Duration timeBetweenRunStarts, Runnable toRun) {
        final Runnable decoratedToRun = decorate(toRun);
        final Runnable runIfNotRunning = () -> {
            if(setRunning(id)) return;
            // Execute instead of run directly because the 'scheduleAtFixedRate' calls should return as fast as possible
            executors.execute(() -> {
                try {
                    // If this takes longer than timeBetweenRunStarts, the 'if(setRunning)' call above will prevent parallel runs
                    decoratedToRun.run();
                } finally {
                    finishedRunning(id);
                }
            });
        };
        synchronized(idToFuture) {
            cancel(id);
            idToFuture.put(id, executors.scheduleAtFixedRate(runIfNotRunning, initialDelay.toMillis(), timeBetweenRunStarts.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    /** Calls {@link #}runPeriodicallyAtFixedRate(String,Duration,Duration,Runnable)} with initial delay equal to timeBetweenRunStarts */
    public static void runPeriodicallyAtFixedRate(String id, Duration timeBetweenRunStarts, Runnable toRun) {
        runPeriodicallyAtFixedRate(id, timeBetweenRunStarts, timeBetweenRunStarts, toRun);
    }

    /** Run periodically, after given initial delay, with a delay between each run (so time between end of one run and start of next run). */
    public static void runPeriodicallyAtFixedDelay(String id, Duration initialDelay, Duration delayBetweenRuns, Runnable toRun) {
        final Runnable decoratedToRun = decorate(toRun);
        synchronized(idToFuture) {
            cancel(id);
            idToFuture.put(id, executors.scheduleWithFixedDelay(decoratedToRun, initialDelay.toMillis(), delayBetweenRuns.toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    /** Calls {@link #}runPeriodicallyAtFixedDelay(String,Duration,Duration,Runnable)} with initial delay equal to delayBetweenRuns */
    public static void runPeriodicallyAtFixedDelay(String id, Duration delayBetweenRuns, Runnable toRun) {
        runPeriodicallyAtFixedDelay(id, delayBetweenRuns, delayBetweenRuns, toRun);
    }

    private static long now() { return System.currentTimeMillis(); }
}
