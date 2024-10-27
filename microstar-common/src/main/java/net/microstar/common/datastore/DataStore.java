package net.microstar.common.datastore;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.SemanticStringComparator;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/** Interface for simple storage implementations.
  * Paths are strings separated by slashes.
  * A path that ends with a slash is a folder.<p>
  *
  * It is mandatory that all implementations of DataStore are thread-safe
  * (preferably stateless) as they will be used concurrently by multiple classes.
  **/
public interface DataStore {
    // This was removed: PATH_DELIM = "/" because it will always be slash and the
    // code is much less readable when using PATH_DELIM, especially in regexes.
    // This is the exception where code readability trumps coding standards.

    @EqualsAndHashCode(cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
    @RequiredArgsConstructor
    @ToString
    class Item {
        public final String path;
        public final Instant time;
        public final int count;
        public final long size;

        public static Item join(Item a, Item b) {
            return new Item(a.path, a.time.isAfter(b.time) ? a.time : b.time, a.count + b.count, a.size + b.size);
        }
        public String getFilename() { return path.replaceAll("^.*/([^/]+)$", "$1"); }
        public String getFilename(String relativeTo) {
            final String rel = normalizePathDefault(relativeTo);
            return path.startsWith(rel) ? path.substring(rel.length()) : "";
        }
    }
    Comparator<Item> ITEM_COMPARATOR = Comparator.comparing(item -> item.path, SemanticStringComparator.IGNORING_CASE);

    // Close is implemented as a runnable so no hard
    // reference to the store needs to be kept.
    default Runnable                                   getCloseRunner() { return () -> {}; }
    default CompletableFuture<List<Item>>              list() { return list("", false); }
    default CompletableFuture<List<Item>>              list(String path) { return list(path, false); }
            CompletableFuture<List<Item>>              list(String path, boolean recursive);
    <T>     CompletableFuture<Optional<T>>             get(String path, Class<T> type);
    <T>     CompletableFuture<Optional<T>>             get(String path, TypeReference<T> type);
            CompletableFuture<Optional<Instant>>       getLastModified(String path);
            CompletableFuture<Boolean>                 exists(String path);
    <T>     CompletableFuture<Boolean>                 store(String path, T data);
            CompletableFuture<Boolean>                 remove(String path);
            CompletableFuture<Boolean>                 move(String fromPath, String toPath);

            CompletableFuture<Optional<InputStream>> readStream(String path);
            CompletableFuture<Optional<byte[]>>      read(String path);
    default CompletableFuture<Optional<String>>      readString(String path) { return read(path).thenApply(bytes -> bytes.map(b->new String(b, StandardCharsets.UTF_8))); }
    default CompletableFuture<Boolean>               write(String path, byte[] data) { return write(path, data, Instant.now()); }
            CompletableFuture<Boolean>               write(String path, byte[] data, Instant time);
    default CompletableFuture<Boolean>               write(String path, String data) { return write(path, data.getBytes(StandardCharsets.UTF_8)); }
    default CompletableFuture<Boolean>               write(String path, String data, Instant time) { return write(path, data.getBytes(StandardCharsets.UTF_8), time); }
    default CompletableFuture<Boolean>               write(String path, InputStream source) { return write(path, source, sizeDone -> {}); }
    default CompletableFuture<Boolean>               write(String path, InputStream source, LongConsumer progress) { return write(path, source, Instant.now(), progress); }
            CompletableFuture<Boolean>               write(String path, InputStream source, Instant time, LongConsumer progress);
            CompletableFuture<Boolean>               touch(String path, Instant time);
    default CompletableFuture<Boolean>               touch(String path) { return touch(path, Instant.now()); }

    default CompletableFuture<List<String>>          listNames(String path) { return listNames(path, false); }
    default CompletableFuture<List<String>>          listNames(String path, boolean recursive) {
        return list(path, recursive).thenApply(items -> items.stream().map(item -> item.path).sorted().toList());
    }
            CompletableFuture<List<String>>          listChangedNamesSince(Duration duration);

    default Runnable                                 onChange(Consumer<List<String>> changeHandler) { return onChange("/", changeHandler); }
            Runnable                                 onChange(String path, Consumer<List<String>> changeHandler);
            Runnable                                 onClose(Consumer<DataStore> closingStoreHandler);


    default boolean isDir(String path) { return path.isEmpty() || path.endsWith("/"); }
    default boolean isDir(Item item)   { return isDir(item.path); }
    default String  getParent(String path) { return getParentDefault(path); }
    default String normalizePath(Object... pathParts) { return normalizePathDefault(pathParts); }

    static String getParentDefault(String path) {
        return normalizePathDefault(path + "/../");
    }

    static String normalizePathDefault(Object... pathParts) {
        final String path = IOUtils.concatPath(pathParts);
        final boolean endsWithSlash = path.endsWith("/") || path.endsWith("/.") || path.endsWith("/..") || path.isEmpty();
        String p = ("/" + path.replace("\\","/"))
            .replaceAll("/++", "/")
            .replaceFirst("^/?(\\.\\.?/)+", "/"); // NOSONAR -- regex ^(../)+ for long path
        String p0;
        do {
            p0 = p; p = p
                .replaceAll("/[^/]+?/\\.\\.(/|$)", "/")   // /anything/../ -> /
                .replaceAll("/\\.(/|$)", "/")             // /./ -> /
                .replaceAll("/{2,}", "/")                 // // -> /
            ;
        } while(!p0.equals(p));
        return endsWithSlash ? p.replaceFirst("/+$", "/")
                             : p.replaceFirst("/+$", "");
    }
}
