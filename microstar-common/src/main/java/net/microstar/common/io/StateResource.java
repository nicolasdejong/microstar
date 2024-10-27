package net.microstar.common.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.ObjectMapping;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.util.DynamicReference;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.Threads;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ExceptionUtils.rethrow;

/** Class to store a pojo class instance in a file, like properties.
  * Make sure the data is @Jacksonized and fields are public
  * Like StateFile but for DataStore instead of File system.
  */
@Slf4j
public class StateResource<T> {
    private final ObjectMapper mapper = ObjectMapping.get();
    private final DynamicReference<DataStore> storeRef;
    private final String file;
    private final Optional<Class<T>> type;
    private final Optional<TypeReference<T>> typeRef;
    private Optional<T> defaultValue = Optional.empty();
    @RequiredArgsConstructor
    private static class State<T> {
        final Object sync;
        final long knownLastModified;
        final Optional<Class<T>> type;
        final Optional<TypeReference<T>> typeRef;
        final Optional<T> value;
    }
    private static final Map<String, State<?>> pathToState = new ConcurrentHashMap<>();
    private final Consumer<List<String>> onStoreChange;


    public StateResource(DynamicReferenceNotNull<DataStore> storeRef, String file, TypeReference<T> valueTypeRef) {
        this.storeRef = storeRef;
        this.file = DataStore.normalizePathDefault(file);
        this.type = Optional.empty();
        this.typeRef = Optional.of(valueTypeRef);
        this.onStoreChange = keys -> { if(keys.contains(this.file)) Threads.execute(this::updatePathToState); };
        pathToState.computeIfAbsent(this.file, key -> new State<>(new Object(), 0, this.type, typeRef, Optional.empty()));
        storeRef.getOptional().ifPresent(store -> store.onChange(onStoreChange));
        storeRef.whenChanged(store -> storeRef.get().onChange(onStoreChange));
    }
    public StateResource(DynamicReferenceNotNull<DataStore> storeRef, String file, Class<T> type) {
        this.storeRef = storeRef;
        this.file = DataStore.normalizePathDefault(file);
        this.type = Optional.of(type);
        this.typeRef = Optional.empty();
        this.onStoreChange = keys -> { if(keys.contains(this.file)) Threads.execute(this::updatePathToState); };
        pathToState.computeIfAbsent(this.file, key -> new State<>(new Object(), 0, this.type, typeRef, Optional.empty()));
        storeRef.getOptional().ifPresent(store -> store.onChange(onStoreChange));
        storeRef.whenChanged(store -> storeRef.get().onChange(onStoreChange)); // store may be nulll due to lazy loading
    }

    private DataStore getStore() {
        return Optional.ofNullable(storeRef.get()).orElseThrow(() -> new IllegalStateException("No DataStore for StateResource " + file));
    }

    /** Called when the store is updated. The store update can be external (like the filesystem was updated
      * by someone else) or internal, when a write() was done.
      */
    private void updatePathToState() {
        synchronized(getSync()) {
            pathToState.put(file, read());
        }
    }

    public StateResource<T> setDefault(@Nullable T def) {
        defaultValue = Optional.ofNullable(def);
        return this;
    }

    /** Read the file and returns as given type, or throws if no file */
    public T get() {
        synchronized(getSync()) {
            return getOptional().orElseThrow(() -> new IllegalStateException("Failed to read " + type)); // throws when no default set
        }
    }
    /** Read the file and returns as given type, or empty if no file */
    public Optional<T> getOptional() {
        synchronized(getSync()) {
            //noinspection unchecked
            return Optional.of(pathToState.get(file))
                .filter(valueInfo -> valueInfo.knownLastModified == getLastModified())
                .filter(state -> state.value.isPresent())
                .map(state -> (T) state.value.get())
                .or(() -> noThrow(this::read).map(state -> { pathToState.put(file, state); return state.value; }).orElseGet(() -> defaultValue));
        }
    }

    /** Writes the given value to file, ignoring any current values; use update() for concurrent update */
    public T set(T newValue) {
        synchronized(getSync()) {
            write(newValue);
            // The write() will lead to a store update, which leads to an async call to updatePathState() which will do a read().
            // So the next line isn't really necessary but leaving it out would mean that doing a getOptional() would return
            // the *old* value before the async update had an opportunity to set the new pathToState. This makes the next
            // line mandatory.
            pathToState.put(file, new State<>(getSync(), getLastModified(), type, typeRef, Optional.of(newValue)));
            return newValue;
        }
    }

    /** Writes the given value to store */
    public void update(UnaryOperator<T> update) {
        synchronized(getSync()) {
            set(update.apply(get()));
        }
    }

    private Object getSync() {
        return pathToState.get(file).sync;
    }

    private long getLastModified() {
        return noThrow(() -> getStore().getLastModified(file).get()).orElse(Optional.empty())
            .map(Instant::getEpochSecond)
            .orElse(0L);
    }

    private void write(T obj) {
        try {
            final String json = mapper.writeValueAsString(obj);
            if("{}".equals(json) && !(obj instanceof Collection<?> col && col.isEmpty()) && !(obj instanceof Map<?,?> map && map.isEmpty())) {
                log.warn("Object json is {}. Are all fields of type {} public? obj={}", json, obj.getClass(), obj);
            }
            rethrow(() -> getStore().write(file, json).get(), ex -> new IllegalStateException(ex.getMessage(), ex));
        } catch (final Exception cause) {
            throw new IllegalStateException("Failed to write " + obj.getClass(), cause);
        }
    }
    private State<T> read() {
        final long lastModified = getLastModified();
        final Optional<String> json = noCheckedThrow(() -> getStore().readString(file).get()).filter(js -> !js.isBlank());

        return new State<>(getSync(), lastModified, type, typeRef, json.map(this::fromJson).or(() -> defaultValue));
    }
    private T fromJson(String json) {
        try {
            return type.isPresent() ? mapper.readValue(json, type.get())
                                    : mapper.readValue(json, typeRef.orElseThrow());
        } catch (final JsonProcessingException cause) {
            throw new IllegalStateException("Error reading " + file + ": Unable to map json string to type " + type + ". json: " + json, cause);
        }
    }
}
