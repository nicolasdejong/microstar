package net.microstar.common.datastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.microstar.testing.TestUtils.sleep;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemDataStoreTest extends AbstractDataStoreTest { // most tests are in super
    @TempDir Path testDir;

    @Override
    DataStore createStore() {
        final FileSystemDataStore fsStore = new FileSystemDataStore(testDir);
        fsStore.fsDetector.setIgnoreAll(true); // timing of this detector (filesystem?) is too unreliable to keep tests stable.
        return fsStore;
    }

    @Test void testExternalChange() throws IOException {
        final String filename = "/externalFile.txt";
        final FileSystemDataStore fsStore = (FileSystemDataStore) store;
        final List<String> detectedChanges = new CopyOnWriteArrayList<>();
        fsStore.onChange(detectedChanges::addAll);
        fsStore.fsDetector.setIgnoreAll(false);

        final Path externalFile = fsStore.root.resolve(filename.substring(1));
        Files.writeString(externalFile, "123");

        // Depending on how busy the machine and/or IO is, this can be fast or slow
        for(int tries=10; tries-->0 && !detectedChanges.contains(filename);) sleep(500);

        assertTrue(detectedChanges.contains(filename));
    }
    @Test void testReplacingIllegalChars() {
        final String name = "a%b<c>d%20e:f";
        final String encoded = "a%37;b%60;c%62;d%37;20e%58;f";
        assertThat(FileSystemDataStore.replaceIllegalPathCharacters(name), is(encoded));
        assertThat(FileSystemDataStore.restoreIllegalPathCharacters(FileSystemDataStore.replaceIllegalPathCharacters(name)), is(name));
    }
}
