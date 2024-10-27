package net.microstar.common.util;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.function.Supplier;

/** Dynamic reference that throws when get() is called when the value is null
  * and no non-null default value is available. Constructing this with a null
  * value and a default value supplier can be used for lazy loading a value.
  */
@SuppressWarnings("unchecked") // methods returning 'this' get an extends, so 'this' is correct with subclasses
public class DynamicReferenceNotNull<T> extends DynamicReference<T> {
    @SuppressWarnings("this-escape")
    public DynamicReferenceNotNull(@Nullable T value) {
        super(value);
        setDefaultIfNoValue(true);
    }
    @SuppressWarnings("this-escape")
    public DynamicReferenceNotNull(DynamicReference<T> toCopy) {
        //noinspection ConstantValue
        super(toCopy == null ? null : toCopy.getOptional().orElse(null));
        setDefaultIfNoValue(true);
    }

    public static <T> DynamicReferenceNotNull<T> of(@Nullable T value) { return new DynamicReferenceNotNull<>(value); }
    public static <T> DynamicReferenceNotNull<T> of(DynamicReference<T> toCopy) { return new DynamicReferenceNotNull<>(toCopy); }
    public static <T> DynamicReferenceNotNull<T> empty() { return new DynamicReferenceNotNull<>((T)null); }

    /** Returns the currently set value, which is *never* null. */
    @Override @Nonnull
    public T get() { // NOSONAR -- override omits the @Nullable annotation
        // super.get() will call set(getDefault()) if current value is null.
        return validate(super.get());
    }

    /** Set the given value. If given value is different from current value and not null, the change
      * handler (if set) will be called.
      */
    @Override
    public <S extends DynamicReference<T>> S set(@Nullable T val) {
        final @Nullable T old = value.get(); // get() has side-effects: it lazily loads using any set supplier. value.get() returns the actual value.
        // val is allowed to be null if a default value or supplier exists
        if(defaultValueSupplier == null && defaultValue == null) validate(val);
        value.set(val);
        if(!Objects.equals(old, val)) changed(old, val); // old and val may be null
        return (S)this;
    }

    /** Set a value supplier to be used when the set value is null. Overrides default value. */
    public <S extends DynamicReference<T>> S setDefault(@Nullable Supplier<T> defaultValueSupplier) {
        super.defaultValueSupplier = () -> validate(defaultValueSupplier.get());
        return (S) this;
    }

    @Nonnull
    private static <T> T validate(@Nullable T value) {
        return Objects.requireNonNull(value, "Value for DynamicReferenceNotNull is not allowed to be null");
    }
}
