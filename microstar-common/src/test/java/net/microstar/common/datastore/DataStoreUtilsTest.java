package net.microstar.common.datastore;

import net.microstar.common.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataStoreUtilsTest {

    @Test void shouldCopyFromFileToDataStore(@TempDir Path tempDir) throws ExecutionException, InterruptedException {
        final String filename = "file.txt";
        final String content = "some content";
        final DataStore store = new MemoryDataStore();
        final Path file = tempDir.resolve(filename);
        IOUtils.writeString(file, content);

        assertTrue(store.readString(filename).get().isEmpty());

        DataStoreUtils.copy(file, store, filename).get();
        assertThat(store.readString(filename).get().orElse(""), is(content));
    }
    @Test void shouldCopyFromDirToDataStore(@TempDir Path tempDir) throws ExecutionException, InterruptedException {
        final DataStore store = new MemoryDataStore();
        final List<String> names = List.of(
            "file1.txt",
            "file2.txt",
            "dir1/file11.txt",
            "dir1/file12.txt",
            "dir1/dir11/file111.txt",
            "dir2/file21.txt"
        );
        names.forEach(name -> IOUtils.writeString(tempDir.resolve(name), name));

        DataStoreUtils.copy(tempDir, store, "").get();

        names.forEach(name -> assertThat(name, noThrow(() -> store.readString(name).get().orElse("")).orElse(""), is(name)));
    }
    @Test void shouldCopyFromDataStoreToFile(@TempDir Path tempDir) throws ExecutionException, InterruptedException, IOException {
        final String filename = "file.txt";
        final String content = "some content";
        final DataStore store = new MemoryDataStore();
        final Path targetFile = tempDir.resolve(filename);

        store.write(filename, content).get();

        DataStoreUtils.copy(store, filename, targetFile).get();

        assertThat(Files.readString(targetFile), is(content));

        // try to copy over existing while overwrite=false
        assertThrows(ExecutionException.class, () -> DataStoreUtils.copy(store, filename, targetFile).get()); // NOSONAR -- Test won't work without the get()

        // copy over existing while overwrite=true
        assertDoesNotThrow(() -> DataStoreUtils.copy(store, filename, targetFile, /*overwrite=*/true).get());
    }
    @Test void shouldCopyFromDataStoreToFiles(@TempDir Path tempDir) throws ExecutionException, InterruptedException {
        final DataStore store = new MemoryDataStore();
        final List<String> names = List.of(
            "file1.txt",
            "file2.txt",
            "dir1/file11.txt",
            "dir1/file12.txt",
            "dir1/dir11/file111.txt",
            "dir2/file21.txt"
        );
        names.forEach(name -> noThrow(() -> store.write(name, name).get()));

        DataStoreUtils.copy(store, "/", tempDir).get();

        names.forEach(name -> assertThat(name, noThrow(() -> Files.readString(tempDir.resolve(name))).orElse(""), is(name)));
    }
}