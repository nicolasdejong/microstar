package net.microstar.common.datastore;

import com.fasterxml.jackson.core.type.TypeReference;
import net.microstar.common.datastore.DataStore.Item;
import net.microstar.common.util.ByteSize;
import net.microstar.common.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.testing.TestUtils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class AbstractDataStoreTest {
    protected DataStore store;
    private Set<String> changedPaths;
    private Runnable removeOnChangeHandler;
    private static final Instant TIME2000 = LocalDateTime.of(2000, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC);

    abstract DataStore createStore();

    @BeforeEach
    void setup() throws ExecutionException, InterruptedException {
        final Set<String> newChangedPaths = ConcurrentHashMap.newKeySet(); // thread safe set
        store = createStore();
        ((AbstractDataStore)store).setChangeDebounceDuration(Duration.ZERO);
        store.store("root.txt", "Root text file").get();
        store.store("root.size", ByteSize.ofBytes(1025)).get();
        store.store("root.list", List.of(1, 2, 3)).get();
        store.store("/1/file1_1.txt", "text 1_3").get();
        store.store("/1/file1_2.txt", "text 1_2").get();
        store.store("/1/1/file1_1_1.txt", "text 1_1_1").get();
        store.store("/1/1/file1_1_2.txt", "text 1_1_2").get();
        store.store("/1/2/file1_2_1.txt", "text 1_2_1").get();
        store.store("/1/2/deeper/deep.txt", "text deep").get();
        store.store("/2/1/file2_1_1.txt", "text 2_1_1").get();
        store.store("/3/bytes.data", new byte[] { 1, 2, 3, 4, 5, 6 }).get();

        // This onChange is called asynchronously from the store so cannot be asserted
        // immediately after a test. Therefore waitForCondition() is used for it.
        removeOnChangeHandler = store.onChange(changes -> {
            if(changes.stream().anyMatch(Objects::isNull)) throw new IllegalStateException("Changes contain null!: " + changes);
            newChangedPaths.addAll(changes); // use closure variable instead of class variable async bleed-out
        });
        changedPaths = newChangedPaths;
    }

    @AfterEach
    void cleanup() {
        removeOnChangeHandler.run();
        store.getCloseRunner().run();
    }

    @Test void list() throws ExecutionException, InterruptedException {
        assertThat(list("", true),      is(List.of("1/1/file1_1_1.txt", "1/1/file1_1_2.txt", "1/2/deeper/deep.txt", "1/2/file1_2_1.txt", "1/file1_1.txt", "1/file1_2.txt", "2/1/file2_1_1.txt", "3/bytes.data", "root.list", "root.size", "root.txt")));
        assertThat(list("/"),           is(List.of("1/", "2/", "3/", "root.list", "root.size", "root.txt")));
        assertThat(list("1/"),          is(List.of("1/", "2/", "file1_1.txt", "file1_2.txt")));
        assertThat(list("1/", true),    is(List.of("1/file1_1_1.txt", "1/file1_1_2.txt", "2/deeper/deep.txt", "2/file1_2_1.txt", "file1_1.txt", "file1_2.txt")));
        assertThat(list("1/2/", true),  is(List.of("deeper/deep.txt", "file1_2_1.txt")));
        assertThat(list("/foo/", true), is(Collections.emptyList()));

        assertThat(sizesOf(store.list("/", true).get()), is(List.of(
            "1/1/file1_1_1.txt:12",
            "1/1/file1_1_2.txt:12",
            "1/2/deeper/deep.txt:11",
            "1/2/file1_2_1.txt:12",
            "1/file1_1.txt:10",
            "1/file1_2.txt:10",
            "2/1/file2_1_1.txt:12",
            "3/bytes.data:6",
            "root.list:7",
            "root.size:6",
            "root.txt:16"
        )));
        assertThat(sizesOf(store.list("/").get()), is(List.of(
            "1/:67",
            "2/:12",
            "3/:6",
            "root.list:7",
            "root.size:6",
            "root.txt:16"
        )));
        assertThat(sizesOf(store.list("/1/").get()), is(List.of(
            "1/:24",
            "2/:23",
            "file1_1.txt:10",
            "file1_2.txt:10"
        )));
        assertThat(sizesOf(store.list("/3/").get()), is(List.of(
            "bytes.data:6"
        )));
    }
    @Test void getType() throws ExecutionException, InterruptedException {
        assertThat(store.get("root.txt", String.class).get().orElseThrow(), is("Root text file"));
        assertThat(store.get("/root.size", ByteSize.class).get().orElseThrow(), is(ByteSize.ofBytes(1025)));
        assertThat(store.get("/1/2/file1_2_1.txt", String.class).get().orElseThrow(), is("text 1_2_1"));
    }
    @Test void getComplexType() throws ExecutionException, InterruptedException {
        assertThat(store.get("/root.list", new TypeReference<List<Integer>>() {}).get().orElseThrow(), is(List.of(1, 2, 3)));
    }
    @Test void getBytes() throws ExecutionException, InterruptedException {
        assertThat(store.get("/3/bytes.data", byte[].class).get().orElseThrow(), is(new byte[] { 1, 2, 3, 4, 5, 6}));
    }
    @Test void exists() throws ExecutionException, InterruptedException {
        assertThat(store.exists("root.txt").get(), is(true));
        assertThat(store.exists("nonexisting.txt").get(), is(false));
        assertThat(store.exists("/1/").get(), is(true));
        assertThat(store.exists("/1").get(), is(true));
        store.store("/4%$4_:4/abc", "dummy").get();
        assertThat(store.exists("4%$4_:4/").get(), is(true));
    }
    @Test void remove() throws ExecutionException, InterruptedException {
        assertThat(store.remove("root.txt").thenApply(v -> list("", false)).get(), is(List.of("1/", "2/", "3/", "root.list", "root.size")));
        assertThat(store.remove("1/2/").thenApply(v -> list("1", false)).get(), is(List.of("1/", "file1_1.txt", "file1_2.txt")));
        assertThat(sorted(changedPaths), is(List.of("/1/2/deeper/deep.txt", "/1/2/file1_2_1.txt", "/root.txt")));
    }
    @Test void moveFile() throws ExecutionException, InterruptedException {
        assertThat(store.move("root.txt", "root_moved.txt").thenApply(v -> list("")).get(), is(List.of("1/", "2/", "3/", "root.list", "root.size", "root_moved.txt")));
        assertThat(store.move("root.list", "/1/root.list").thenApply(v -> list("/1/")).get(), is(List.of("1/", "2/", "file1_1.txt", "file1_2.txt", "root.list")));
        assertThat(sorted(changedPaths), is(List.of("/1/root.list", "/root.list", "/root.txt", "/root_moved.txt")));
    }
    @Test void moveDir() throws ExecutionException, InterruptedException {
        assertThat(store.move("/1/", "/1moved/" ).thenApply(v -> list("/", true)).get(), is(List.of(
            "1moved/1/file1_1_1.txt",
            "1moved/1/file1_1_2.txt",
            "1moved/2/deeper/deep.txt",
            "1moved/2/file1_2_1.txt",
            "1moved/file1_1.txt",
            "1moved/file1_2.txt",
            "2/1/file2_1_1.txt",
            "3/bytes.data",
            "root.list",
            "root.size",
            "root.txt"
        )));
        assertThat(sorted(changedPaths) ,is(List.of(
            "/1/1/file1_1_1.txt",
            "/1/1/file1_1_2.txt",
            "/1/2/deeper/deep.txt",
            "/1/2/file1_2_1.txt",
            "/1/file1_1.txt",
            "/1/file1_2.txt",
            "/1moved/1/file1_1_1.txt",
            "/1moved/1/file1_1_2.txt",
            "/1moved/2/deeper/deep.txt",
            "/1moved/2/file1_2_1.txt",
            "/1moved/file1_1.txt",
            "/1moved/file1_2.txt"
        )));
        changedPaths.clear();
        assertThat(store.move("/1moved/2/", "/1moved/2moved/" ).thenApply(v -> list("/", true)).get(), is(List.of(
            "1moved/1/file1_1_1.txt",
            "1moved/1/file1_1_2.txt",
            "1moved/2moved/deeper/deep.txt",
            "1moved/2moved/file1_2_1.txt",
            "1moved/file1_1.txt",
            "1moved/file1_2.txt",
            "2/1/file2_1_1.txt",
            "3/bytes.data",
            "root.list",
            "root.size",
            "root.txt"
        )));
        assertThat(sorted(changedPaths), is(List.of(
            "/1moved/2/deeper/deep.txt",
            "/1moved/2/file1_2_1.txt",
            "/1moved/2moved/deeper/deep.txt",
            "/1moved/2moved/file1_2_1.txt"
        )));
        changedPaths.clear();
        assertThat(store.move("/1moved/2moved/", "/4/" ).thenApply(v -> list("/", true)).get(), is(List.of(
            "1moved/1/file1_1_1.txt",
            "1moved/1/file1_1_2.txt",
            "1moved/file1_1.txt",
            "1moved/file1_2.txt",
            "2/1/file2_1_1.txt",
            "3/bytes.data",
            "4/deeper/deep.txt",
            "4/file1_2_1.txt",
            "root.list",
            "root.size",
            "root.txt"
        )));
        assertThat(sorted(changedPaths), is(List.of(
            "/1moved/2moved/deeper/deep.txt",
            "/1moved/2moved/file1_2_1.txt",
            "/4/deeper/deep.txt",
            "/4/file1_2_1.txt"
        )));
    }
    @Test void write() throws ExecutionException, InterruptedException {
        assertThat(store.write("dir/newFile.txt", "foobar").thenComposeAsync(b -> store.readString("dir/newFile.txt")).get().orElse(""), is("foobar"));
        assertThat(changedPaths, is(Set.of("/dir/newFile.txt")));

        assertThat(store.write("dir/newFile2.txt", "foobar", TIME2000).thenComposeAsync(b -> store.getLastModified("dir/newFile2.txt")).get().orElseThrow(), is(TIME2000));
    }
    @Test void writeStreamed() throws ExecutionException, InterruptedException {
        assertThat(store.write("dir/newFile.txt", new ByteArrayInputStream("foobar".getBytes(StandardCharsets.UTF_8)))
            .thenComposeAsync(b -> store.readString("dir/newFile.txt")).get().orElse(""), is("foobar"));
        assertThat(changedPaths, is(Set.of("/dir/newFile.txt")));

        assertThat(store.write("dir/newFile2.txt", new ByteArrayInputStream("foobar".getBytes(StandardCharsets.UTF_8)), TIME2000, done -> {})
            .thenComposeAsync(b -> store.getLastModified("dir/newFile2.txt")).get().orElseThrow(), is(TIME2000));
    }
    @Test void touchNonExisting() throws ExecutionException, InterruptedException {
        final String pathName = "/touch/newFile.txt";
        assertThat(store.touch(pathName).thenComposeAsync(b -> store.listNames("/touch")).get(), is(List.of("newFile.txt")));
        assertThat(changedPaths, is(Set.of(pathName)));

        final String pathName2 = "/touch/newFile2.txt";
        assertThat(store.touch(pathName2, TIME2000).thenComposeAsync(b -> store.getLastModified(pathName2)).get().orElseThrow(), is(TIME2000));
    }
    @Test void touchExisting() throws ExecutionException, InterruptedException {
        final String pathName = "/2/1/file2_1_1.txt";
        final Instant time1 = store.getLastModified(pathName).get().orElseThrow();
        Utils.sleep(Duration.ofMillis(10)); // so the timestamps are not the same
        store.touch(pathName).get();
        final Instant time2 = store.getLastModified(pathName).get().orElseThrow();
        assertTrue(time2.isAfter(time1));
        assertThat(changedPaths, is(Set.of(pathName)));
        assertThat(store.touch(pathName, TIME2000).thenComposeAsync(b -> store.getLastModified(pathName)).get().orElseThrow(), is(TIME2000));
    }
    @Test void isDir() {
        assertThat(store.isDir("1/"), is(true));
        assertThat(store.isDir("root.size"), is(false));
    }
    @Test void normalizePath() {
        assertThat(store.normalizePath(""), is("/"));
        assertThat(store.normalizePath("/"), is("/"));
        assertThat(store.normalizePath("/a/"), is("/a/"));
        assertThat(store.normalizePath("/a/b"), is("/a/b"));
        assertThat(store.normalizePath("/a/b/"), is("/a/b/"));
        assertThat(store.normalizePath("a\\b"), is("/a/b"));
        assertThat(store.normalizePath("\\a\\b"), is("/a/b"));
        assertThat(store.normalizePath("a/b/c/../c/./d"), is("/a/b/c/d"));
        assertThat(store.normalizePath("a/b/c/../c/././././d"), is("/a/b/c/d"));
        assertThat(store.normalizePath("a/b/c/d/../../../k/../z"), is("/a/z"));
    }
    @Test void getParent() {
        assertThat(store.getParent("a/b/c"), is("/a/b/"));
        assertThat(store.getParent("/a/b/c"), is("/a/b/"));
        assertThat(store.getParent("a/b/c/"), is("/a/b/"));
        assertThat(store.getParent("/a/b/c/"), is("/a/b/"));
        assertThat(store.getParent("a"), is("/"));
        assertThat(store.getParent("/a"), is("/"));
        assertThat(store.getParent("/"), is("/"));
        assertThat(store.getParent(""), is("/"));
    }
    @Test void listChangedNamesSince() throws ExecutionException, InterruptedException {
        // If these tests fail periodically, multiply the 5 numbers in millis.
        sleep(250);
        store.write("first/a", "aaa").get();
        store.write("second/b", "bbb").get();
        assertThat(store.listChangedNamesSince(Duration.ofMillis(100)).get(), is(List.of("/second/b", "/first/a")));
        sleep(250);
        store.write("third/c", "ccc").get();
        assertThat(store.listChangedNamesSince(Duration.ofMillis(100)).get(), is(List.of("/third/c")));
        assertThat(store.listChangedNamesSince(Duration.ofMillis(500)).get(), is(List.of("/third/c", "/second/b", "/first/a")));
    }

    @Test void shouldBeAbleToReadFromStream() throws ExecutionException, InterruptedException, IOException {
        final String resourceName = "toStream.txt";
        final String data = "Some test data.".repeat(10);
        store.write(resourceName, data).get();
        try(final InputStream inStream = store.readStream(resourceName).get().orElseThrow()) {
            final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            inStream.transferTo(outStream);
            assertThat(outStream.toString(), is(data));
        }
    }
    @Test void shouldBeAbleToWriteToStream() throws ExecutionException, InterruptedException, IOException {
        final String resourceName = "/writeToStreamTest.txt";
        final String data = "Some test data.".repeat(10);
        try(final ByteArrayInputStream inStream = new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8))) {
            store.write(resourceName, inStream).get();
        }
        assertThat(store.readString(resourceName).get().orElseThrow(), is(data));
        assertThat(changedPaths, is(Set.of(resourceName)));
    }
    @Test void streamShouldBeClosedAfterUse() throws ExecutionException, InterruptedException {
        final String resourceName = "file.txt";
        final AtomicBoolean wasClosed = new AtomicBoolean(false);
        final InputStream inStream = new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)) {
            public void close() throws IOException {
                super.close();
                wasClosed.set(true);
            }
        };
        store.write(resourceName, inStream, Instant.now(), p -> {}).get();
        assertThat(wasClosed.get(), is(true));
    }

    private List<String> list(String path) { return list(path, false); }
    private List<String> list(String path, boolean recursive) {
        return noThrow(() -> sorted(pathsOf(store.list(path, recursive).get()))).orElse(Collections.emptyList());
    }
    private List<String> pathsOf(List<Item> items) {
        return items.stream().map(item -> item.path).toList();
    }
    private List<String> sizesOf(List<Item> items) {
        return items.stream().map(item -> item.path + ":" + item.size).toList();
    }

    private static <T> List<T> sorted(Collection<T> collection) {
        return collection.stream().sorted().toList();
    }
}
