package net.microstar.common.datastore;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** DataStore that throws on any call, to be used when misconfigured */
@RequiredArgsConstructor
public class FailingDataStore implements DataStore {
    private final String name;

    private RuntimeException fail() { return new IllegalStateException("DataStore '" + name + "' is misconfigured and cannot function."); }

    @Override public     CompletableFuture<List<Item>>              list(String path, boolean recursive)    { throw fail(); }
    @Override public <T> CompletableFuture<Optional<T>>             get(String path, Class<T> type)         { throw fail(); }
    @Override public <T> CompletableFuture<Optional<T>>             get(String path, TypeReference<T> type) { throw fail(); }
    @Override public     CompletableFuture<Optional<Instant>>       getLastModified(String path) { throw fail(); }
    @Override public     CompletableFuture<Boolean>                 exists(String path) { throw fail(); }
    @Override public <T> CompletableFuture<Boolean>                 store(String path, T data)              { throw fail(); }
    @Override public     CompletableFuture<Boolean>                 remove(String path)                     { throw fail(); }
    @Override public     CompletableFuture<Boolean>                 move(String fromPath, String toPath)    { throw fail(); }
    @Override public     CompletableFuture<Optional<byte[]>>        read(String path)                       { throw fail(); }
    @Override public     CompletableFuture<Boolean>                 write(String path, byte[] data, Instant time)  { throw fail(); }
    @Override public     CompletableFuture<Optional<InputStream>>   readStream(String path) { throw fail(); }
    @Override public     CompletableFuture<Boolean>                 write(String path, InputStream source, Instant time, LongConsumer progress) { throw fail(); }
    @Override public     CompletableFuture<Boolean>                 touch(String path, Instant time) { throw fail(); }
    @Override public     CompletableFuture<List<String>>            listChangedNamesSince(Duration duration) { throw fail(); }
    @Override public     Runnable                                   onChange(String path, Consumer<List<String>> changeHandler) { return () -> {}; }
    @Override public     Runnable                                   onClose(Consumer<DataStore> closingStoreHandler) { return () -> {}; }
}
