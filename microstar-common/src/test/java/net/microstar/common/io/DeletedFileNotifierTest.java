package net.microstar.common.io;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class DeletedFileNotifierTest {
    private static final String PREFIX = DeletedFileNotifierTest.class.getSimpleName() + "_";
    private static String name(String n) { return PREFIX + n; }
    @TempDir Path TEMP_DIR;
    Path TEST_FILE = Path.of(name("TestName"));
    Path TEST_FILE_DEEP = Path.of(name("testFolder/TestName"));

    @AfterEach @BeforeEach void cleanup() {
        DeletedFileNotifier.stopWatchingAll();
        TEST_FILE = TEMP_DIR.resolve(name("TestName"));
        TEST_FILE_DEEP = TEMP_DIR.resolve(name("testFolder/TestName"));
    }

    @Test void fileShouldBeCreatedWhenNonExistent() {
        assertThat(Files.exists(TEST_FILE), is(false));
        DeletedFileNotifier.forFile(TEST_FILE, "Description", () -> {});
        assertThat(Files.exists(TEST_FILE), is(true));
        assertThat(noThrow(() -> Files.readString(TEST_FILE)).orElse(""), is("Description"));
    }
    @Test void shouldBeNotifiedWhenFileIsDeleted() throws IOException {
        final AtomicInteger notifyCount = new AtomicInteger(0);
        DeletedFileNotifier.forFile(TEST_FILE, "Description", notifyCount::incrementAndGet);
        sleep(500); // Let thread start
        Files.delete(TEST_FILE);
        waitForCondition(() -> notifyCount.get() > 0);
        assertThat(notifyCount.get(), is(1));
    }
    @Test void shouldBeNotifiedWhenDeeperFileIsDeleted() throws IOException {
        final AtomicInteger notifyCount = new AtomicInteger(0);
        DeletedFileNotifier.forFile(TEST_FILE_DEEP, "Description", notifyCount::incrementAndGet);
        sleep(500); // Let thread start
        Files.delete(TEST_FILE_DEEP);
        waitForCondition(() -> notifyCount.get() > 0);
        assertThat(notifyCount.get(), is(1));
    }
    @Test void watcherShouldStopWatchingFileWhenAskedTo() {
        final AtomicInteger notifyCount = new AtomicInteger(0);
        DeletedFileNotifier.forFile(TEST_FILE, "Description", notifyCount::incrementAndGet);
        sleep(500); // Let thread start
        DeletedFileNotifier.stopWatchingFile(name("TestName"));
        waitForCondition(Duration.ofSeconds(2), Duration.ofMillis(100), () -> notifyCount.get() > 0, () -> {});
        assertThat(notifyCount.get(), is(0));
    }
    @Test void watcherShouldStopWatchingAllFilesWhenAskedTo() {
        final AtomicInteger notifyCount = new AtomicInteger(0);
        DeletedFileNotifier.forFile(TEST_FILE, "Description", notifyCount::incrementAndGet);
        DeletedFileNotifier.forFile(TEST_FILE_DEEP, "Description", notifyCount::incrementAndGet);
        sleep(500); // Let threads start
        DeletedFileNotifier.stopWatchingAll();
        waitForCondition(Duration.ofSeconds(2), Duration.ofMillis(100), () -> notifyCount.get() > 0, () -> {});
        assertThat(notifyCount.get(), is(0));
    }

    private static void waitForCondition(Supplier<Boolean> conditionChecker) {
        waitForCondition(Duration.ofSeconds(5), conditionChecker);
    }
    private static void waitForCondition(Duration maxWait, Supplier<Boolean> conditionChecker) {
        waitForCondition(maxWait, Duration.ofMillis(10), conditionChecker);
    }
    private static void waitForCondition(Duration maxWait, Duration interval, Supplier<Boolean> conditionChecker) {
        waitForCondition(maxWait, interval, conditionChecker, () -> { throw new IllegalStateException("Timeout for condition"); });
    }
    private static void waitForCondition(Duration maxWait, Duration interval, Supplier<Boolean> conditionChecker, Runnable onTimeout) {
        final long eol = System.currentTimeMillis() + maxWait.toMillis();
        while(!conditionChecker.get()) {
            sleep(interval);
            if(System.currentTimeMillis() > eol) { onTimeout.run(); break; }
        }
    }
}