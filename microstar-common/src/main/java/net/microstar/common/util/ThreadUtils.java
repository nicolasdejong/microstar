package net.microstar.common.util;

import lombok.RequiredArgsConstructor;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

@SuppressWarnings({"unchecked", "unused"})
public final class ThreadUtils {
    private static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(100);
    private static final Duration DEFAULT_THROTTLE = Duration.ofMillis(100);
    private ThreadUtils() {}
    private static final class ThrottleInfo {
        private final @Nullable Object lastResult;
        private final long lastRunTime;
        private final long shelfLife;
        private ThrottleInfo(@Nullable Object lastResult, long lastRunTime, long shelfLife) {
            this.lastResult = lastResult;
            this.lastRunTime = lastRunTime;
            this.shelfLife = shelfLife;
        }
    }
    private static final class DebounceInfo {
        private final long lastRunTime;
        private final long maxDelay;
        private DebounceInfo(long lastRunTime, long maxDelay) {
            this.lastRunTime = lastRunTime;
            this.maxDelay = maxDelay;
        }
    }
    private static final Map<String,ThrottleInfo> throttles = new HashMap<>();
    private static final Map<String,DebounceInfo> debounces = new HashMap<>();

    static {
        TimedRunner.runPeriodicallyAtFixedDelay("pruneThrottles", Duration.ofMinutes(1), ThreadUtils::pruneThrottles);
        TimedRunner.runPeriodicallyAtFixedDelay("pruneDebounces", Duration.ofMinutes(1), ThreadUtils::pruneDebounces);
    }

    /** Remove throttle infos over their shelf life which otherwise would prevent garbage collection of lastResult */
    private static void pruneThrottles() {
        synchronized (throttles) {
            final long now = System.currentTimeMillis();
            new HashSet<>(throttles.keySet()).forEach(key -> {
               final ThrottleInfo ti = throttles.get(key);
               if(ti.lastRunTime + ti.shelfLife > now) throttles.remove(key);
            });
        }
    }
    /** Remove debounce infos that exist longer than their max delay of debounced */
    private static void pruneDebounces() {
        synchronized (debounces) {
            final long now = System.currentTimeMillis();
            new HashSet<>(debounces.keySet()).forEach(key -> {
                final DebounceInfo di = debounces.get(key);
                if(di.lastRunTime + di.maxDelay > now) debounces.remove(key);
            });
        }
    }

    // debounce -> don't run until <time> has passed since the last call
    // throttle -> don't run until <time> has passed since the last run

    /** Don't call runner until 100ms has passed since the debounce was called. Uses caller location as id. */
    public static void debounce(Runnable runner) {
        debounce(Reflection.getCallerId(ThreadUtils.class), DEFAULT_DEBOUNCE, runner);
    }

    /** Don't call runner until debounceTime has passed since the debounce was called. Uses called location as id. */
    public static void debounce(Duration debounceTime, Runnable runner) {
        debounce(Reflection.getCallerId(ThreadUtils.class), debounceTime, runner);
    }

    /** Don't call runner until debounceTime has passed since the debounce was called unless that
      * takes longer than maxDelay. The maxDelay was added to prevent starvation in cases where the
      * debounce is called continuously which would otherwise lead to the runner never be called.
      * A maxDelay basically makes this a debounced throttle. Uses caller location as id.
      */
    public static void debounce(Duration debounceTime, Duration maxDelay, Runnable runner) {
        debounce(Reflection.getCallerId(ThreadUtils.class), debounceTime, maxDelay, runner);
    }

    /** Don't call runner until debounceTime has passed since the debounce was called. */
    public static void debounce(String id, Duration debounceTime, Runnable runner) {
        TimedRunner.runAfterDelay(id, debounceTime, runner);
    }

    /** Don't call runner until debounceTime has passed since the debounce was called, unless that
      * takes longer than maxDelay. The maxDelay was added to prevent starvation in cases where the
      * debounce is called continuously which would otherwise lead to the runner never be called.
      * A maxDelay basically makes this a debounced throttle.
      */
    public static void debounce(String id, Duration debounceTime, Duration maxDelay, Runnable runner) {
        final long now = System.currentTimeMillis();
        final boolean needsToRunNow;
        synchronized(debounces) {
            final DebounceInfo info = debounces.computeIfAbsent(id, u -> new DebounceInfo(now, maxDelay.toMillis()));
            needsToRunNow = info.maxDelay > 0 && now - info.lastRunTime > info.maxDelay;
            if (needsToRunNow) {
                TimedRunner.cancel(id);
                debounces.put(id, new DebounceInfo(now, maxDelay.toMillis()));
            }
        }
        if(needsToRunNow) {
            runner.run(); // run outside synchronized
        } else {
            TimedRunner.runAfterDelay(id, debounceTime, () -> {
                runner.run();
                debounces.put(id, new DebounceInfo(System.currentTimeMillis(), maxDelay.toMillis()));
            });
        }
    }

    /** Cancels the debounce of earlier call with given id */
    public static void cancelDebounce(String id) {
        TimedRunner.cancel(id);
    }

    private static final class ThreadInfo {
        private boolean inDebounceFuture;
    }
    private static final ThreadLocal<ThreadInfo> threadInfo = ThreadLocal.withInitial(ThreadInfo::new);

    /** Don't call supplier until debounceTimeMs has passed since last call. Requires futures
      * to complete
      */
    public static <T> CompletableFuture<T> debounceFuture(Duration debounceTime, Supplier<CompletableFuture<T>> toCall) {
        @RequiredArgsConstructor
        final class FutureInfo {
            public final CompletableFuture<?> future;
            public final long creationTime;
            private static long maxDebounceTimeMs = 0;
            private static final Map<Supplier<?>, FutureInfo> debouncingFutures = new HashMap<>();
            private static void remove(Supplier<?> supplier) {
                synchronized (debouncingFutures) {
                    debouncingFutures.remove(supplier);
                }
            }
            private static void prune() {
                final long cutoffTime = System.currentTimeMillis() - maxDebounceTimeMs;
                synchronized (debouncingFutures) {
                    CollectionUtils.removeIf(debouncingFutures, inf -> inf.creationTime < cutoffTime);
                }
            }
        }
        final long debounceTimeMs = debounceTime.toMillis();
        if(debounceTimeMs > FutureInfo.maxDebounceTimeMs) FutureInfo.maxDebounceTimeMs = debounceTimeMs;

        synchronized (FutureInfo.debouncingFutures) {
            // Don't use computeIfAbsent() because the toCall.get() may do a recursive call
            if(!FutureInfo.debouncingFutures.containsKey(toCall)) {
                // Dummy futureInfo to prevent endless recursion. When toCall happens to call this same
                // debounceFuture(..), the condition above will prevent the code from entering this block again.
                //noinspection OverwrittenKey
                FutureInfo.debouncingFutures.put(toCall, new FutureInfo(new CompletableFuture<T>() {}, System.currentTimeMillis()));

                // Now create the actual future, that calls toCall to get the future (which may call this debounce before returning it)
                //noinspection OverwrittenKey
                FutureInfo.debouncingFutures.put(toCall, new FutureInfo(toCall.get()
                    .whenComplete((result,ex) -> Threads.execute(Duration.ofMillis(debounceTimeMs), ()-> { FutureInfo.remove(toCall); FutureInfo.prune(); })),
                    System.currentTimeMillis()));
            }
            //noinspection unchecked
            return (CompletableFuture<T>)FutureInfo.debouncingFutures.get(toCall).future;
        }
    }

    /** Don't call supplier if it ran in the last 100ms, The id will be the location of
      * the caller.<br><br>
      *
      * Alias for {@link #throttle(String, Duration, boolean, Supplier)} where id is the callerId,
      * duration 100ms, addRunTimeToThrottleTime=true and supplier runs the runner.
      */
    public static void throttle(Runnable runner) {
        throttle(Reflection.getCallerId(), DEFAULT_THROTTLE, true, () -> { runner.run(); return null; });
    }

    /** Don't call supplier if it ran in the last throttleTime, The id will be the location of
      * the caller.<br><br>
      *
      * Alias for {@link #throttle(String, Duration, boolean, Supplier)} where id is the callerId,
      * addRunTimeToThrottleTime=true and supplier runs the runner.
      */
    public static void throttle(Duration throttleTime, Runnable runner) {
        throttle(Reflection.getCallerId(), throttleTime, true, () -> { runner.run(); return null; });
    }

    /** Don't call runner if it ran in the last throttleTime for the given id.<br><br>
      *
      * Alias for {@link #throttle(String, Duration, boolean, Supplier)} where
      * addRunTimeToThrottleTime=true and supplier runs the runner.
      */
    public static void throttle(String id, Duration throttleTime, Runnable runner) {
        throttle(id, throttleTime, true, () -> { runner.run(); return null; });
    }

    /** Don't call supplier if it ran in the last throttleTime, The id will be the location of
      * the caller.<br><br>
      *
      * Alias for {@link #throttle(String, Duration, boolean, Supplier)} where id is the callerId
      * and addRunTimeToThrottleTime=true.
      */
    public static @Nullable <T> T throttle(Duration throttleTime, Supplier<T> supplier) {
        return throttle(Reflection.getCallerId(), throttleTime, true, supplier);
    }

    /** Don't call supplier if it ran in the last throttleTime, The id will be the location of
      * the caller.<br><br>
      *
      * Alias for {@link #throttle(String, Duration, boolean, Supplier)} where id is the callerId.
      */
    public static @Nullable <T> T throttle(Duration throttleTime, boolean addRunTimeToThrottleTime, Supplier<T> supplier) {
        return throttle(Reflection.getCallerId(), throttleTime, addRunTimeToThrottleTime, supplier);
    }

    /** Don't call runnable if it ran in the last throttleTime<br><br>
      *
      * Alias for {@link #throttle(String, Duration, boolean, Supplier)} where id runner is a void supplier.
      */
    public static void throttle(String id, Duration throttleTime, boolean addRunTimeToThrottleTime, Runnable runner) {
        throttle(id, throttleTime, addRunTimeToThrottleTime, () -> { runner.run(); return null; });
    }

    /** Don't call supplier if it ran in the last throttleTime
      *
      * @param id              Unique identifier for this throttle
      * @param throttleTime    Time until the supplier can be called again
      * @param addRunTimeToThrottleTime  True to increase throttleTime by the time of the supplier
     *                         (which may be different each run). This difference
      *                        is only noticeable when the supplier can be slow.
      * @param supplier        Supplier to run throttled
      * @param <T>             Type of the result of the supplier that will be returned by this call as well
      * @return                Returns the last result of a call to the supplier. This result is cached
      *                        for throttled calls so won't be garbage collected until a next call to
      *                        the supplier.
      */
    public static @Nullable <T> T throttle(String id, Duration throttleTime, boolean addRunTimeToThrottleTime, Supplier<T> supplier) {
        final long callTime = System.currentTimeMillis();
        synchronized(throttles) {
            final ThrottleInfo throttleInfo = throttles.get(id);
            if(throttleInfo != null && callTime - throttleInfo.lastRunTime < throttleTime.toMillis()) return (T)throttleInfo.lastResult;
        }
        @Nullable T result = null;
        try {
            result = supplier.get();
        } finally {
            synchronized (throttles) {
                throttles.put(id, new ThrottleInfo(result, addRunTimeToThrottleTime ? System.currentTimeMillis() : callTime, throttleTime.toMillis()));
            }
        }
        return result;
    }

    /** Throttled version of given consumer, which accepts once per given duration.
      * Note that the last calls probably will be ignored as they fall between the last
      * accept and the next time an accept would occur. Workaround is to call the
      * toThrottle.accept() once at the end of processing.
      */
    public static <T,C extends Consumer<T>> C throttle(C toThrottle, Duration throttleTime) {
        //noinspection unchecked
        return (C)new Consumer<T>() {
            private long lastProgressTime = 0;
            private final long throttleMs = throttleTime.toMillis();

            @Override public void accept(T t) {
                final long now = System.currentTimeMillis();
                if(now - lastProgressTime > throttleMs) {
                    lastProgressTime = now;
                    toThrottle.accept(t);
                }
            }
            @Override public Consumer<T> andThen(Consumer<? super T> after) {
                return toThrottle.andThen(after);
            }
        };
    }
    /** @see #throttle(C,Duration) */
    public static <T,C extends Consumer<T>> C throttleLC(LongConsumer toThrottle, Duration throttleTime) {
        final Consumer<Long> longConsumer = new Consumer<>() {
            @Override public void accept(Long value) { toThrottle.accept(value); }
            @Override public Consumer<Long> andThen(Consumer<? super Long> after) {
                return (Long t) -> { accept(t); after.accept(t); };
            }
        };
        return (C)throttle(longConsumer, throttleTime);
    }
}
