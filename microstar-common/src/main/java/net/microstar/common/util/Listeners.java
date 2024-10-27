package net.microstar.common.util;

import lombok.RequiredArgsConstructor;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static net.microstar.common.util.ThreadUtils.debounce;
import static net.microstar.common.util.Utils.is;

/** Utility class for classes that want to support listeners which can optionally be debounced.
  * When extra mapping or filtering needs to be performed, the call() method can be overloaded.<p>
  *
  * V is type of consumer callback, D is optional extra data that can be used when overloading
  * the call() method.
  */
public class Listeners<V,D> {
    private static final Duration DEFAULT_DEBOUNCE_DURATION = Duration.ofMillis(100);
    private static final Duration DEFAULT_DEBOUNCE_MAX_DURATION = Duration.ofMillis(1000);
    private final String debounceId = UUID.randomUUID().toString();
    private final Map<String, Tuple<Consumer<V>, D>> listenersMap;
    private final Duration debounceDuration;
    private final Duration debounceMaxDuration;

    public Listeners() { this(new HashMap<>(), Duration.ZERO, Duration.ofHours(1)); }
    public Listeners(Duration debounceDuration) { this(new HashMap<>(), debounceDuration, Duration.ofHours(1)); }
    public Listeners(Duration debounceDuration, Duration debounceMaxDuration) { this(new HashMap<>(), debounceDuration, debounceMaxDuration); }
    public Listeners(Map<String, Tuple<Consumer<V>, D>> listenersMapToCopy, Duration debounceDuration, Duration debounceMaxDuration) {
        this.listenersMap = new ConcurrentHashMap<>(listenersMapToCopy);
        this.debounceDuration = debounceDuration;
        this.debounceMaxDuration = debounceMaxDuration;
    }

    public Listeners<V,D> withDebounce() {
        return withDebounce(DEFAULT_DEBOUNCE_DURATION);
    }
    public Listeners<V,D> withDebounce(Duration duration) {
        return new Listeners<>(listenersMap, duration, Duration.ofHours(1));
    }
    public Listeners<V,D> withThrottledDebounce() {
        return new Listeners<>(listenersMap, DEFAULT_DEBOUNCE_DURATION, DEFAULT_DEBOUNCE_MAX_DURATION);
    }
    public Listeners<V,D> withThrottledDebounce(Duration debounceDuration) {
        return new Listeners<>(listenersMap, debounceDuration, debounceDuration.multipliedBy(5));
    }
    public Listeners<V,D> withThrottledDebounce(Duration debounceDuration, Duration throttleDuration) {
        return new Listeners<>(listenersMap, debounceDuration, Utils.requireCondition("Throttle must be longer than debounce", throttleDuration, td -> is(td).smallerThan(debounceDuration)));
    }

    @RequiredArgsConstructor
    public static final class Tuple<A,B> {
        public final A a;
        public final @Nullable B b;
    }

    public Runnable add(Consumer<V> changeHandler) { return add(null, changeHandler); }
    public Runnable add(@Nullable D initialData, Consumer<V> changeHandler) {
        final String listenerKey = UUID.randomUUID().toString();
        listenersMap.put(listenerKey, new Tuple<>(changeHandler, initialData));
        return () -> listenersMap.remove(listenerKey);
    }
    public final void call(V callValue) { call(callValue, null); }
    public final void call(V callValue, @Nullable D callData) {
        final Runnable toRun = () -> listenersMap.values().forEach(entry -> call(entry.a, entry.b, callValue, callData));
        if(debounceDuration.isZero()) toRun.run();
        else debounce(debounceId, debounceDuration, debounceMaxDuration, toRun);
    }

    /** Overload this method to use the 'initialData' parameter given at add() time.
      *
      * @param listener     Consumer to be called
      * @param initialData  Data provided when the consumer was added, or null
      *                     when just a consumer was added without data.
      * @param callValue    Value provided in the call(V) call.
      * @param callData     Data provided in the call(V,D) call, or null if
      *                     not provided.
      */
    protected void call(Consumer<V> listener, @Nullable D initialData, V callValue, @Nullable D callData) { // NOSONAR -- 'extraData' exists for overloading
        listener.accept(callValue);
    }
}
