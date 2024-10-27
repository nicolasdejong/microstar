package net.microstar.spring.settings;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Updatable reference to an instance of dynamic properties (which should be annotated by {@link DynamicProperties}).
 * Whenever new configuration is received, the properties (which are immutable) will
 * be replaced by an updated instance when there are any changes for the given class.
 * This class is backed by an AtomicReference so is thread-safe.<p><br/>
 *
 * This class will be garbage collected if no longer referenced (like all Java
 * objects), even if an onChange handler was added. So {@code DynamicPropertiesRef.of(...).onChange(...)}
 * will probably do nothing (there is a small chance the onChange handler will
 * be called before the garbage collector is called) unless assigned to a variable
 * that stays in scope.<p><br/>
 *
 * Usage:<br/><pre>{@code
 *   DynamicPropertiesRef<SomeProperties> propsRef = DynamicPropertiesRef.of(SomeProperties.class);
 *   ...
 *   final SomeProperties props = propsRef.get();
 * }</pre><br/>
 *
 * An alternative is to set the properties directly:
 *
 *  <pre>{@code
 *   volatile SomeProperties props = DynamicPropertiesManager.getInstance(SomeProperties.class);
 *   DynamicPropertiesRef<SomeProperties> propsRef = DynamicPropertiesRef.of(SomeProperties.class)
 *     .onChange((newValue,changedKey) -> props = newValue);
 *  }</pre><br/>
 *
 *  but that has the same risks as the @RefreshScope from Spring in that the code may be
 *  getting fields from the properties while the properties change. For example getting a
 *  user and password from properties and the properties change just after the user is copied
 *  from it (e.g. the array length was changed or the map was changed and this key no longer
 *  exists) after which the password is different or no longer exists which leads to wrong
 *  values and may even lead to a crash (NullPointer or OutOfBounds).<p><br/>
 *
 *  It is safer to use {@code DynamicPropertiesRef.get()} at the beginning of
 *  the method to get the properties and use those:
 *  <pre>{@code final SomeProperties props = propsRef.get();}</pre><br/>
 *
 *  When the rug is changed while you walk over it, you may fall on your face.
 */
public final class DynamicPropertiesRef<T> implements DynamicPropertyListener<T> {
    private final Class<T> propertiesType;
    private final AtomicReference<T> ref = new AtomicReference<>();
    private final List<BiConsumer<T,Set<String>>> listeners = new CopyOnWriteArrayList<>();

    private DynamicPropertiesRef(Class<T> propertiesType) {
        this.propertiesType = propertiesType;
        ref.set(DynamicPropertiesManager.getInstanceOf(propertiesType, this));
    }

    @Override
    public String toString() {
        return String.valueOf(ref.get());
    }

    @Override
    public void changed(T newValue, Set<String> changedKeys) {
        ref.set(newValue);
        callListenersFor(newValue, changedKeys);
    }

    @Override
    public void cleared() {
        ref.set(DynamicPropertiesManager.getInstanceOf(propertiesType, this));
    }

    /** Get a reference to an instance of a DynamicProperties class */
    public static <S> DynamicPropertiesRef<S> of(Class<S> propertiesType) {
        return new DynamicPropertiesRef<>(propertiesType);
    }

    /**
     * Get the current instance of T. The call will give another instance
     * when changes in the configuration have been observed. Use onChange
     * to know when there are changes.
     */
    public T get() {
        return ref.get();
    }

    /**
     * Coll given consumer whenever one or more attributes of T have changed.
     *
     * @param handler  Handler that will be called with a new instance of T
     *                 and the names of the fields that changed (relative to
     *                 the DynamicProperties path of T).
     * @return this
     */
    public DynamicPropertiesRef<T> onChange(BiConsumer<T, Set<String>> handler) {
        synchronized (listeners) { listeners.add(handler); }
        return this;
    }
    /**
     * Coll given consumer whenever one or more attributes of T have changed.
     *
     * @param handler  Handler that will be called with a new instance of T
     * @return this
     */
    public DynamicPropertiesRef<T> onChange(Consumer<T> handler) {
        return onChange((newSettings, changes) -> handler.accept(newSettings));
    }

    /**
     * Coll given consumer whenever one or more attributes of T have changed.
     *
     * @param handler  Handler that will be called on changes.
     * @return this
     */
    public DynamicPropertiesRef<T> onChange(Runnable handler) {
        return onChange((newSettings, changes) -> handler.run());
    }

    /**
     * Calls previously set onChange handlers when this ref holds a value.
     * @return this
     */
    public DynamicPropertiesRef<T> callOnChangeHandlers() {
        if(ref.get() != null) callListenersFor(ref.get(), Collections.emptySet());
        return this;
    }

    /**
     * Remove previously added onChange listeners
     * @return this
     */
    public DynamicPropertiesRef<T> removeChangeListeners() {
        synchronized (listeners) { listeners.clear(); }
        return this;
    }

    private void callListenersFor(T newRef, Set<String> changedKeys) {
        listeners.forEach(listener -> listener.accept(newRef, changedKeys));
    }
}
