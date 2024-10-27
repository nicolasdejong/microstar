package net.microstar.common.io;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.MoreConversionsModule;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/** Class to store a data class instance in a file. Make sure the data is @Jacksonized and fields are public */
@Slf4j
public class StateFile<T> {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new GuavaModule())
        .registerModule(new MoreConversionsModule());
    private final Path file;
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
    private static final Map<Path, State<?>> pathToState = new ConcurrentHashMap<>();

    public StateFile(Path file, TypeReference<T> valueTypeRef) {
        this.file = file;
        this.type = Optional.empty();
        this.typeRef = Optional.of(valueTypeRef);
        pathToState.computeIfAbsent(file, key -> new State<>(new Object(), 0, type, typeRef, Optional.empty()));
    }
    public StateFile(Path file, Class<T> type) {
        this.file = file;
        this.type = Optional.of(type);
        this.typeRef = Optional.empty();
        pathToState.computeIfAbsent(file, key -> new State<>(new Object(), 0, this.type, typeRef, Optional.empty()));
    }

    public StateFile<T> setDefault(@Nullable T def) {
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
                .filter(valueInfo -> valueInfo.knownLastModified == file.toFile().lastModified())
                .filter(state -> state.value.isPresent())
                .map(state -> (T) state.value.get())
                .or(() -> noThrow(() -> read().value).orElseGet(() -> defaultValue));
        }
    }

    /** Writes the given value to file, ignoring any current values; use update() for concurrent update */
    public T set(T newValue) {
        synchronized(getSync()) {
            write(newValue);
            pathToState.put(file, new State<>(getSync(), file.toFile().lastModified(), type, typeRef, Optional.of(newValue)));
            return newValue;
        }
    }

    /** Writes the given value to file */
    public void update(UnaryOperator<T> update) {
        synchronized(getSync()) {
            set(update.apply(get()));
        }
    }

    private Object getSync() {
        return pathToState.get(file).sync;
    }

    private void write(T obj) {
        try {
            final String json = mapper.writeValueAsString(obj);
            if("{}".equals(json) && !(obj instanceof Collection<?> col && col.isEmpty()) && !(obj instanceof Map<?,?> map && map.isEmpty())) {
                log.warn("Object json is {}. Are all fields of type {} public? obj={}", json, obj.getClass(), obj);
            }
            if(!Files.exists(file.getParent())) Files.createDirectories(file.getParent());
            if(!Files.exists(file.getParent())) throw new IOException("Unable to create directory: " + file.getParent().toAbsolutePath());
            Files.writeString(file, json);
        } catch (final IOException cause) {
            throw new IllegalStateException("Failed to write " + obj.getClass(), cause);
        }
    }
    private State<T> read() {
        final long lastModified = file.toFile().lastModified();
        final Optional<String> json = noThrow(() -> Files.readString(file)).filter(js -> !js.isBlank());

        return new State<>(getSync(), lastModified, type, typeRef, json.map(this::fromJson).or(() -> defaultValue));
    }
    private T fromJson(String json) {
        try {
            return type.isPresent() ? mapper.readValue(json, type.get())
                                    : mapper.readValue(json, typeRef.orElseThrow());
        } catch (final JsonProcessingException cause) {
            throw new IllegalStateException("Error reading " + file.toFile().getName() + ": Unable to map json string to type " + type + ". json: " + json, cause);
        }
    }
}
