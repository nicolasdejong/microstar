package net.microstar.common.datastore;

import com.fasterxml.jackson.core.type.TypeReference;
import net.microstar.common.conversions.ObjectMapping;
import net.microstar.common.util.Listeners;
import net.microstar.common.util.Threads;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static net.microstar.common.util.ExceptionUtils.rethrow;

public abstract class AbstractDataStore implements DataStore {
    public static class ConversionException extends DataStoreException {
        public ConversionException(String message, Exception cause) { super(message, cause); }
    }
    private static final Duration DEFAULT_CHANGE_DEBOUNCE_DURATION = Duration.ofSeconds(1);
    private       Listeners<List<String>,String> changeListeners = new Changes();
    private final Listeners<DataStore,Void> closeListeners = new Listeners<>();

    // Keeping changes is cheaper than a recursive listing starting at root.
    // Price is that this list is not accurate when the service running this
    // is restarted, which leads to an empty list. If that price is too high
    // the data can be written to disk between restarts. For now this is ok.
    private final LinkedList<Change> recentChanges = new LinkedList<>();
    private static final int RECENT_CHANGES_MAX_COUNT = 1000;

    private static class Change {
        public final String path;
        public final Instant timestamp;
        public Change(String path) {
            this.path = path;
            timestamp = Instant.now();
        }
    }

    public void setChangeDebounceDuration(@Nullable Duration set) { changeListeners = changeListeners.withDebounce(set == null ? DEFAULT_CHANGE_DEBOUNCE_DURATION : set); }
    public void setChangeThrottledDebounceDuration(Duration set, Duration max) { changeListeners = changeListeners.withThrottledDebounce(set, max); }

    protected <T> T convert(String json, Class<T> type) { return convert(json, typeReferenceFrom(type)); }

    protected <T> T convert(String json, TypeReference<T> type) {
        return rethrow(() -> ObjectMapping.get().readValue(json, type), e -> new ConversionException("Conversion from json to " + type + " failed", e));
    }

    protected String toJson(Object data) {
        return rethrow(() -> ObjectMapping.get().writeValueAsString(data), e -> new ConversionException("Conversion to json failed for: " + data, e));
    }

    @Override
    public Runnable getCloseRunner() { return this::closed; }

    @Override
    public <T> CompletableFuture<Optional<T>> get(String path, Class<T> type) {
        return read(path).thenApply(bytes -> bytes.map(b -> type == byte[].class ? (T)b : convert(new String(b, StandardCharsets.UTF_8), type)));
    }

    @Override
    public <T> CompletableFuture<Optional<T>> get(String path, TypeReference<T> type) {
        return read(path).thenApply(bytes -> bytes.map(b -> convert(new String(b, StandardCharsets.UTF_8), type)));
    }

    @Override
    public <T> CompletableFuture<Boolean> store(String path, T data) {
        return remove(path).thenComposeAsync(b -> write(path, data instanceof byte[] bytes ? bytes : toJson(data).getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public Runnable onChange(String path, Consumer<List<String>> changeHandler) {
        return changeListeners.add(path, changeHandler);
    }

    @Override
    public Runnable onClose(Consumer<DataStore> closingStoreHandler) {
        return closeListeners.add(closingStoreHandler);
    }

    @Override
    public CompletableFuture<List<String>> listChangedNamesSince(Duration duration) {
        final Instant since = Instant.now().minusMillis(duration.toMillis());
        return supplyAsync(() -> {
            synchronized (recentChanges) {
                final List<String> result = new ArrayList<>();
                final Iterator<Change> iterator = recentChanges.descendingIterator();
                while(iterator.hasNext()) { // actually, it is hasPrevious() as it is descending
                    final Change change = iterator.next(); // previous()
                    if(change.timestamp.isBefore(since)) break;
                    result.add(change.path);
                }
                return result;
            }
        });
    }

    public void changed(String... paths) {
        changed(Arrays.asList(paths));
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public final <C extends Collection<String>> void changed(C... paths) {
        final List<String> pathsList = Arrays.stream(paths).flatMap(Collection::stream).toList();
        addChanged(pathsList);
        changeListeners.call(pathsList);
    }

    private void addChanged(List<String> paths) {
        synchronized (recentChanges) {
            paths.forEach(path -> {
                recentChanges.add(new Change(path));
                if (recentChanges.size() > RECENT_CHANGES_MAX_COUNT) recentChanges.removeFirst();
            });
        }
    }

    protected void closed() {
        closeListeners.call(this);
    }

    protected static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier) {
        return CompletableFuture.supplyAsync(supplier, Threads.getExecutor());
    }

    public static <T> TypeReference<T> typeReferenceFrom(Class<T> type) {
        return new TypeReference<T>(){
            @Override
            public Type getType() {
                return type;
            }
        };
    }

    private static class Changes extends Listeners<List<String>,String> {
        public Changes() {
            super(Duration.ofSeconds(1), Duration.ofSeconds(5));
        }
        protected void call(Consumer<List<String>> listener, @Nullable String basePath, List<String> paths) { // NOSONAR -- extra exists for overloading
            if(basePath == null) return; // basePath is not nullable but the interface requires it
            final List<String> filteredPaths = paths.stream().filter(p -> p.startsWith(basePath)).toList();
            if (!filteredPaths.isEmpty()) listener.accept(filteredPaths);
        }
    }
}
