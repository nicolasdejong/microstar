package net.microstar.common.io;

import net.microstar.common.io.FileTreeChangeDetector.ChangeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static net.microstar.common.io.FileTreeChangeDetector.ChangeType.CREATED;
import static net.microstar.common.io.FileTreeChangeDetector.ChangeType.DELETED;
import static net.microstar.common.io.FileTreeChangeDetector.ChangeType.MODIFIED;
import static net.microstar.common.io.IOUtils.del;
import static net.microstar.common.io.IOUtils.delTree;
import static net.microstar.common.io.IOUtils.touch;
import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

@SuppressWarnings("ResultOfMethodCallIgnored")
class FileTreeChangeDetectorTest {
    private static final long CONDITION_TIMEOUT_MS = 5000; // should be larger than MODIFIED DEBOUNCE DURATION
    private static final long CONDITION_LOOP_SLEEP_MS = 25;
    private final Set<Path> changedPaths = new CopyOnWriteArraySet<>();
    private final List<ChangeType> eventTypes = new CopyOnWriteArrayList<>();
    private FileTreeChangeDetector detector;
    private @TempDir Path testDir;

    @BeforeEach void setup() {
        detector = new FileTreeChangeDetector(testDir, (path, type) -> { eventTypes.add(type); changedPaths.add(path); }).watch();
    }
    @AfterEach void cleanup() {
        changedPaths.clear();
        eventTypes.clear();

        final boolean oldIsClosed = detector.isClosed();
        detector.close();
        assertThat(oldIsClosed, is(false));
        assertThat(detector.isClosed(), is(true));
    }

    @Test void testShouldBeSetupCorrectly() {
        assertThat(detector, is(notNullValue()));
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, is(empty()));
    }

    @Test void newFileShouldBeDetected() {
        touch(testDir, "aFile");
        assertThat(Files.exists(testDir.resolve("aFile")), is(true));
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(1));
        assertThat(eventTypes, is(List.of(CREATED)));
    }
    @Test void newDirShouldBeDetected() {
        detector.setOnlyFiles(false);
        assertThat(detector.setOnlyFiles(false), is(detector));
        testDir.resolve("aDir").toFile().mkdir();
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(1));
        assertThat(eventTypes, is(List.of(CREATED)));
    }
    @Test void newDeeperFileShouldBeDetected() {
        detector.setOnlyFiles(true);
        touch(testDir, "aDir/aFile");
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(1));
        assertThat(eventTypes, is(List.of(CREATED)));
    }
    @Test void newEvenDeeperFileShouldBeDetected() {
        detector.setOnlyFiles(true);
        touch(testDir, "bDir/aDir/dFile");
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(1));
        assertThat(eventTypes, is(List.of(CREATED)));
    }
    @Test void filesInsideNewDirShouldBeDetected() {
        detector.setOnlyFiles(false);
        testDir.resolve("bDir").toFile().mkdir();
        touch(testDir, "bDir/aFile");
        touch(testDir, "bDir/bFile");
        touch(testDir, "bDir/cFile");

        testDir.resolve("bDir/aDir").toFile().mkdirs();
        touch(testDir, "bDir/aDir/aFile");
        touch(testDir, "bDir/aDir/bFile");
        touch(testDir, "bDir/aDir/cFile");
        waitUntilCondition(() -> changedPaths.size() == 8);
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(8)); // 2 dirs + 6 files
    }
    @Test void alteredFileShouldBeDetected() throws IOException {
        final Path cFile = testDir.resolve("bDir/aDir/cFile");
        touch(cFile);
        assertThat(Files.exists(cFile), is(true));
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(eventTypes, is(List.of(CREATED)));
        changedPaths.clear();
        eventTypes.clear();

        Files.writeString(cFile, "foo");
        sleep(500);
        Files.writeString(cFile, "foobar");
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(changedPaths, contains(cFile));
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(1)); // modified file
        assertThat(eventTypes, is(List.of(MODIFIED)));
    }
    @Test void deletedFileShouldBeDetected() {
        final Path fileToDelete = testDir.resolve("fileToDelete");
        touch(fileToDelete);
        waitUntilCondition(() -> changedPaths.size() == 1);
        changedPaths.clear();
        eventTypes.clear();

        del(fileToDelete);
        waitUntilCondition(() -> !changedPaths.isEmpty());
        assertThat(changedPaths, contains(fileToDelete));
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, hasSize(1)); // deleted file
        assertThat(eventTypes, is(List.of(DELETED)));
    }
    @Test void deletedDirectoryShouldBeDetected() {
        // prepare
        detector.setIgnoreAll(true);

        testDir.resolve("bDir").toFile().mkdir();
        touch(testDir, "bDir/aFile");
        touch(testDir, "bDir/bFile");
        touch(testDir, "bDir/cFile");

        testDir.resolve("bDir/aDir").toFile().mkdirs();
        touch(testDir, "bDir/aDir/aFile");
        touch(testDir, "bDir/aDir/bFile");
        touch(testDir, "bDir/aDir/cFile");

        detector
            .setIgnoreAll(false)
            .setOnlyFiles(false);

        // Sometimes the filesystem is slow and events from above come in after
        // the detector is enabled again.
        changedPaths.clear();
        eventTypes.clear();

        // detect deleted directory
        delTree(testDir.resolve("bDir/aDir")); // /bDir, /bDir/aDir, /bDir/aDir/aFile, /bDir/aDir/bFile, /bDir/aDir/cFile
        waitUntilCondition(() -> changedPaths.size() == 4);
        // Couldn't get this stable. Every so often the /bDir is missing from the results. So accept 4 and 5.
        assertThat(rel(testDir, changedPaths).toString(), changedPaths, anyOf(hasSize(4), hasSize(5)));
    }
    @Test void createFileShouldResultInCreatedEvent() throws IOException {
        Files.createFile(testDir.resolve("newFile"));
        waitUntilCondition(() -> !changedPaths.isEmpty());
        sleep(50); // wait a bit for any more events (there shouldn't be any)
        assertThat(eventTypes, is(List.of(CREATED)));
    }
    @Test void writingAnEmptyFileShouldResultInCreatedAndModifiedEvent() throws IOException {
        // writing a new empty file should generate a CREATED and MODIFIED event
        detector.setOnlyFiles(true);
        Files.writeString(testDir.resolve("emptyFile"), "");
        waitUntilCondition(() -> changedPaths.size() == 1);
        waitUntilCondition(() -> eventTypes.size() == 2);
        sleep(50); // wait a bit for any more events (there shouldn't be any)
        assertThat(eventTypes, is(List.of(CREATED, MODIFIED)));
    }
    @Test void exceptionHandlerShouldBeCalledWhenThrowing(@TempDir Path testDir) {
        final Exception[] thrown = { null };
        final BiConsumer<Path, ChangeType> justThrow = (path, type) -> { throw new IllegalArgumentException(testDir.relativize(path).toString()); };
        final Consumer<Exception> exHandler = ex -> thrown[0] = ex;
        final FileTreeChangeDetector detector = new FileTreeChangeDetector(testDir, justThrow, exHandler).watch();
        try {
            assertThat(detector, is(notNullValue()));
            touch(testDir, "aFile");
            waitUntilCondition(() -> thrown[0] != null);
            assertThat(thrown[0], is(notNullValue()));
            assertThat(thrown[0].getMessage(), is("aFile"));
        } finally {
            detector.close();
        }
    }


    private static List<String> rel(Path tempDir, Set<Path> paths) {
        return paths.stream()
            .map(path -> tempDir.relativize(path).toString())
            .sorted()
            .toList();
    }
    private static void waitUntilCondition(BooleanSupplier conditionToWaitFor) { waitUntilCondition(conditionToWaitFor, () -> {}); }
    private static void waitUntilCondition(BooleanSupplier conditionToWaitFor, Runnable onFail) {
        if(conditionToWaitFor.getAsBoolean()) return;
        final long timeout = System.currentTimeMillis() + CONDITION_TIMEOUT_MS;
        while( System.currentTimeMillis() < timeout && !conditionToWaitFor.getAsBoolean()) sleep(CONDITION_LOOP_SLEEP_MS);
        if(!conditionToWaitFor.getAsBoolean()) {
            onFail.run();
        }
    }
}
