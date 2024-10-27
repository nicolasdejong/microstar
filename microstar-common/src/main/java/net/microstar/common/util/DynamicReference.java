package net.microstar.common.util;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Like a limited AtomicReference with callback on change.
  * Not a subclass of AtomicReference because all methods there are final
  * but still thread-safe because an AtomicReference is used to store the value.
  */
@SuppressWarnings("unchecked") // methods returning 'this' get an extends, so 'this' is correct with subclasses
public class DynamicReference<T> {
    protected final AtomicReference<T>  value = new AtomicReference<>();
    protected @Nullable T               defaultValue = null;
    protected @Nullable Supplier<T>     defaultValueSupplier = null;
    private @Nullable Consumer<T>     changeHandler = null;
    private @Nullable BiConsumer<T,T> changeOldToNewHandler = null;
    private boolean                   setDefaultIfNoValue = false;

    /** Create a new VolatileValue without value (get() will return null) */
    public DynamicReference() {}

    /** Create a new VolatileValue with given value */
    public DynamicReference(@Nullable T val) {
        value.set(val);
    }

    public static <T> DynamicReference<T> of(@Nullable T val) { return new DynamicReference<>(val); }

    public int hashCode() {
        return Objects.hash(value.get());
    }
    public boolean equals(Object other) {
        return other instanceof DynamicReference<?> ref
            && Objects.equals(value.get(), ref.value.get());
    }

    /** Set the given value. If given value is different from current value, the change
     * handler (if set) will be called.
     */
    public <S extends DynamicReference<T>> S set(@Nullable T val) { return set(val, false); }

    /** Set the given value. If given value is different from current value or forceChangeEvent
      * is set to true, the change handler (if set) will be called.
      */
    public <S extends DynamicReference<T>> S set(@Nullable T val, boolean forceChangedEvent) {
        final @Nullable T old = value.get(); // don't call get() -- it has side effects
        value.set(val);
        if(forceChangedEvent || !Objects.equals(old, val)) changed(old, val);
        return (S)this;
    }

    /** Returns the currently set value, which can be null. If no value is currently set
      * (value == null) the default value will be returned. If setDefaultIfNoValue is set
      * to true, the returned default value will be set before returning.<p>
      *
      * To use a get without side effects, call getOptional().
      */
    public @Nullable T get() {
        @Nullable T val = value.get();
        if(val == null) {
            boolean callChanged = false;
            synchronized(value) {
                // Value may have been changed between the call to value.get() and now, so check again while in synchronized
                @Nullable T syncVal = value.get();
                if(syncVal == null) {
                    val = getDefault();
                    if (setDefaultIfNoValue) {
                        // set() cannot be called as it calls get() for the old value.
                        value.set(val);
                        callChanged = val != null;
                    }
                } else {
                    val = syncVal;
                }
            }
            if(callChanged) changed(null, val);
        }
        return val;
    }

    /** Returns the default value. No side effects here (defaultValueSupplier, if set, may have).
      * Call set(getDefault()) to set the default value. This is called from get() when
      * current value == null.
      */
    private @Nullable T getDefault() {
        @Nullable T currentDefaultValue = defaultValueSupplier != null ? defaultValueSupplier.get() : null;
        if(currentDefaultValue == null) currentDefaultValue = defaultValue;
        return currentDefaultValue;
    }

    /** Returns the currently set value or empty if no value is set. Default is not used. */
    public Optional<T> getOptional() {
        final T val = value.get();
        return val == null ? Optional.empty() : Optional.of(val);
    }

    /** Set a default value to be used when the set value is null. */
    public <S extends DynamicReference<T>> S setDefault(@Nullable T defaultValue) {
        this.defaultValue = defaultValue;
        return (S)this;
    }

    /** Set a value supplier to be used when the set value is null. Overrides default value. */
    public <S extends DynamicReference<T>> S setDefault(@Nullable Supplier<T> defaultValueSupplier) {
        this.defaultValueSupplier = defaultValueSupplier;
        return (S) this;
    }

    /** Set to true if a set(getDefault()) should be performed when a get() is
      * called on a current null value. False by default.
      */
    public <S extends DynamicReference<T>> S setDefaultIfNoValue(boolean set) {
        this.setDefaultIfNoValue = set;
        return (S)this;
    }

    /** Set a consumer of a value to be called when the value is changed. This replaces any previously set change handler.
      * Note that the change handler may be called with a null value when unset or in case of lazy loading where the
      * actual value is non-null which will only be set once get() is called.
      */
    public <S extends DynamicReference<T>> S whenChanged(Consumer<T> changeHandler) {
        this.changeHandler = changeHandler;
        return (S)this;
    }

    /** Set a consumer of the previous and new value to be called when the value is changed. This replaces any previously set change handler.
      * Note that the change handler may be called with a null value when unset or in case of lazy loading where the
      * actual value is non-null which will only be set once get() is called.
      */
    public <S extends DynamicReference<T>> S whenChanged(BiConsumer<T,T> changeHandler) {
        this.changeOldToNewHandler = changeHandler;
        return (S)this;
    }

    protected void changed(@Nullable T oldValue, @Nullable T newValue) {
        if (changeHandler != null) changeHandler.accept(newValue);
        if (changeOldToNewHandler != null) changeOldToNewHandler.accept(oldValue, newValue);
    }
}
