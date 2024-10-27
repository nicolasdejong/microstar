package net.microstar.spring.settings;

import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.Reflection;
import org.springframework.core.ParameterizedTypeReference;

import javax.annotation.Nullable;
import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/**
 * Shortcut to adding an AtomicReference field to a specific dynamic property.
 * Whenever new configuration is received the property will be replaced by an
 * updated value when the related property was changed. The onChange listeners
 * will be called then. This class is thread-safe.<p><br/>
 *
 * This class will be garbage collected if no longer referenced (like all Java
 * objects), even if an onChange handler was added. So {@code DynamicPropertyRef.of(...).onChange(...)}
 * will probably do nothing (there is a small chance the onChange handler will
 * be called before the garbage collector is called) unless assigned to a variable
 * that stays in scope.<p><br/>
 *
 * Usage:<br/><pre>{@code
 *   DynamicPropertyRef<String> someValue = DynamicPropertyRef.of("path.to.some.value");
 *   someValue.get()
 * }</pre><br/>
 *
 *  If you prefer not to add {@code .get()} every time, there is an alternative that doesn't require {@code DynamicPropertiesRef}:<p><br/>
 *
 *  <pre>{@code
 *   Optional<String> someValue = DynamicPropertiesManager.getProperty("path.to.some.value");
 *  }</pre><br/>
 *
 *  or in a bean method parameter, use {@code @Value("${some.path:someDefault}")}.
 */
public final class DynamicPropertyRef<T> implements DynamicPropertyListener<T> {
    private final ParameterizedTypeReference<T> type;
    @Nullable
    private final T defaultValue;
    private final String propertyPath;
    private final AtomicReference<T> ref = new AtomicReference<>();
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    /** Instances to use if an instance for an empty property is requested.
      * If no such instance is found, nor is a builder or a default constructor found,
      * the get() will throw. This mapping can be altered by calling setDefaultInstance().
      */
    private static final AtomicReference<Map<Class<?>, Object>> typeToDefault = new AtomicReference<>(Map.of(
        Map.class, Collections.emptyMap(),
        List.class, Collections.emptyList(),
        Set.class, Collections.emptySet()
    ));


    /** Create a String property reference for the given property path.
      * The {@link #get()} call will throw a NoSuchElementException when no
      * value exists for the given property path. Call {@link #withDefault(Object)}
      * ta to prevent that or use {@link #getOptional()} instead.
      */
    public static DynamicPropertyRef<String> of(String propertyPath) {
        return of(propertyPath, String.class);
    }

    /** Create a reference of a specific type for the given property path.
      * The content of the property String will (if possible) be converted
      * to the requested type when {@link #get()} is called. The {@link #get()}
      * call will throw a NoSuchElementException when no value exists for the
      * given property path. Call {@link #withDefault(Object)} ta to prevent
      * that or use {@link #getOptional()} instead.
      */
    public static <S> DynamicPropertyRef<S> of(String propertyPath, Class<S> type) {
        return new DynamicPropertyRef<>(ParameterizedTypeReference.forType(type), null, propertyPath);
    }

    /** Create a reference of a specific type for the given property path.
      * The content of the property String will (if possible) be converted
      * to the requested type when {@link #get()} is called. The {@link #get()}
      * call will throw a NoSuchElementException when no value exists for the
      * given property path. Call {@link #withDefault(Object)} ta to prevent
      * that or use {@link #getOptional()} instead.
      */
    public static <S> DynamicPropertyRef<S> of(String propertyPath, ParameterizedTypeReference<S> type) {
        return new DynamicPropertyRef<>(type, null, propertyPath);
    }

    /** Create a copy of this with a given default that will be returned by get()
      * when no value exists for the property path given at construction. If no
      * (or a null) default is given and no value exists for the property path,
      * {@link #get()} will throw a NoSuchElementException and
      * {@link #getOptional()} will return empty when called.
      */
    public DynamicPropertyRef<T> withDefault(@Nullable T defaultValueToSet) {
        final DynamicPropertyRef<T> copy = new DynamicPropertyRef<>(type, defaultValueToSet, propertyPath);
        copy.listeners.addAll(listeners);
        return copy;
    }

    /** Returns the value of the property this reference refers to or, if no value
      * exists for that property return the first of:<pre>
      * - a set default
      * - a set global default for the class of this property (see setDefaultInstance)
      * - a newly created instance from the builder of the type of the property (T)
      * - a newly created instance from the default constructor of the type of the property (T)
      * - throw a NoSuchElementException.
      * </pre>
      * If no default should be returned, use getOptional() instead.
      */
    public T get() {
        return getOptional()
            .or(() -> {
                if(type.getType() instanceof Class<?> c) return Optional.of(getDefaultInstanceOf(c));
                if(type.getType() instanceof ParameterizedType paramType && paramType.getRawType() instanceof Class<?> c) return Optional.of(getDefaultInstanceOf(c));
                return Optional.empty();
            })
            .orElseThrow(this::notFoundException);
    }

    /** Returns the value of the property this reference refers to or, if no value
      * exists for that property, returns the given defaultValue.
      */
    public T get(T defaultIfNoValue) {
        return getOptional().orElse(defaultIfNoValue);
    }

    /** Returns an optional to the value of the property this reference refers to or,
      * if no value exists for that property return the default or, if no default is
      * set, return empty.
      */
    public Optional<T> getOptional() {
        return Optional.ofNullable(ref.get()).or(() -> Optional.ofNullable(defaultValue));
    }

    /** Returns the value as string of the property this reference refers to or,
      * if no value is set for the property, the default or, if no default is set
      * an empty string.
      */
    public String toString() { return getOptional().map(Object::toString).orElse(""); }

    /** The given handler will be called whenever the value of this dynamic property changes */
    public DynamicPropertyRef<T> onChange(Consumer<T> handler) {
        listeners.add(handler);
        return this;
    }

    /** The given handler will be called whenever the value of this dynamic property changes */
    public DynamicPropertyRef<T> onChange(Runnable handler) {
        listeners.add(unused -> handler.run());
        return this;
    }

    public DynamicPropertyRef<T> callOnChangeHandlers() { callListeners(); return this; }

    /** When called, the Spring application will be restarted when the value of this dynamic property changes */
    public DynamicPropertyRef<T> changeShouldLeadToRestart() {
        DynamicPropertiesManager.addPropertyThatRequiresRestart(propertyPath);
        return this;
    }

    @Override
    public void changed(T newValue, Set<String> changedKeys) {
        update();
    }
    @Override
    public void cleared() {
        update();
    }


    private DynamicPropertyRef(ParameterizedTypeReference<T> type, @Nullable T defaultValue, String propertyPath) {
        this.type = type;
        this.defaultValue = defaultValue;
        this.propertyPath = propertyPath;
        update();
        DynamicPropertiesManager.addChangeListenerFor(propertyPath, getMainTypeClass(), this);
    }
    private void update() {
        @Nullable final T newValue = DynamicPropertiesManager.getProperty(propertyPath, getMainTypeClass()).orElse(null);
        ref.set(newValue);
        callListeners();
    }
    private void callListeners() {
        listeners.forEach(listener -> listener.accept(get()));
    }

    /** Set a default instance to use if no value is found for a property and get()
      * is called. The get() will try this mapping, then try a builder of the class,
      * then try a default constructor. If all fails it will throw. To remove a default,
      * set instance to null.
      */
    public static void setDefaultInstance(Class<?> clazz, @Nullable Object instance) {
        synchronized (typeToDefault) {
            ImmutableUtil.updateMapRef(typeToDefault, map -> {
                if (instance == null) map.remove(clazz);
                else map.put(clazz, instance);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private T getDefaultInstanceOf(Class<?> type) {
        // Special case: there should no defaults be for String (which has a default constructor)
        if(!typeToDefault.get().containsKey(String.class) && String.class.isAssignableFrom(type)) throw notFoundException();

        return Optional.<T>empty()

            // Try configured default
            .or(() -> Optional.ofNullable((T)typeToDefault.get().get(type)))

            // Try instanceof configured default
            .or(() ->
                (Optional<T>) typeToDefault.get().entrySet().stream()
                    .filter(e -> e.getKey().isAssignableFrom(type))
                    .map(Map.Entry::getValue)
                    .findFirst())

            // Try builder()
            .or(() -> (Optional<T>)noThrow(() -> type.getMethod("builder"))
                .flatMap(b->noThrow(() -> b.invoke(null)))
                .flatMap(b->noThrow(() -> b.getClass().getMethod("build").invoke(b))))

            // Try default constructor
            .or(() -> (Optional<T>)noThrow(() -> type.getConstructor()) // NOSONAR: false positive c::getConstructor is ambiguous
                .flatMap(con->noThrow(() -> con.newInstance()))) // NOSONAR: false positive con::getNewInstance is ambiguous

            // Fail
            .orElseThrow(this::notFoundException);
    }

    private NoSuchElementException notFoundException() {
        return new NoSuchElementException("No value and no default provided for " + propertyPath + " used by " + Reflection.getCallerClass(DynamicPropertyRef.class));
    }


    @SuppressWarnings("unchecked")
    Class<T> getMainTypeClass() {
        if(type.getType() instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return (Class<T>)c;
        if(type.getType() instanceof Class<?> c) return (Class<T>)c;
        throw new IllegalArgumentException("Type not supported: " + type.getType());
    }
}
