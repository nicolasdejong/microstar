package net.microstar.spring.logging;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.testing.FlakyTest;
import net.microstar.testing.SkipWhenNoSlowTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static java.util.function.Predicate.not;
import static net.microstar.common.io.IOUtils.pathOf;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("ConcatenationWithEmptyString")
@Slf4j
class LogFilesTest {
    private final List<LogFiles> instances = new ArrayList<>();
    // initialized by JUnit
    @TempDir private Path logDir;

    @BeforeEach void init() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.microstar.location", logDir.toFile().getAbsolutePath(),
            "logging.microstar.singleMaxSize", "100B",
            "logging.microstar.sleepBetweenWrites", "200ms",
            "logging.microstar.sleepBetweenMaintenance", "400ms",
            "logging.microstar.history.enabled", "true",
            "logging.microstar.history.maxSize", "1MB",
            "logging.microstar.history.maxAge", "4s"
        )));
    }
    @AfterEach void cleanup() {
        instances.forEach(LogFiles::stop);
        instances.clear();
        LogFiles.clear();
        DynamicPropertiesManager.clearAllState();
        sleep(500); // Sleep a bit so everything is shut down when JUnit deletes the files (and they are not still locked)
    }

    private LogFiles startNewInstance(ServiceId serviceId, UUID instanceId) {
        final LogFiles instance = LogFiles.getNewInstance().start(serviceId, instanceId);
        instances.add(instance);
        return instance;
    }
    private static void waitForWrite() {
        LogFiles.waitForWriteEvent();
    }
    private static void waitForMaintenance() {
        LogFiles.waitForMaintenanceEvent();
    }
    private static void waitForCopyToOld() {
        waitForMaintenance();
        waitForMaintenance(); // time between to-old copies is twice the maintenance time
        waitForMaintenance(); // one extra in case the time is too close leading to race conditions
    }

    @Test void logMessagesShouldPeriodicallyBeStored() throws IOException {
        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));
        logFiles.log("message 1");
        waitForWrite();
        logFiles.log("message 2"); // sometimes adding message 2 happens just when writing so wait before AND after
        waitForWrite();

        final Path logFile = IOUtils.list(logDir.resolve("service")).stream()
            .filter(path -> path.toFile().getName().matches("^\\d+.*$"))
            .findFirst().orElseThrow();

        final String fileContents = Files.readString(logFile);
        assertThat(fileContents, is("message 1\nmessage 2\n"));

        logFiles.log("message 3");
        waitForWrite();

        final String fileContents2 = Files.readString(logFile);
        assertThat(fileContents2, is("message 1\nmessage 2\nmessage 3\n"));
    }

    @Test void logFilePathsShouldBeCorrect() {
        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));
        logFiles.log("message");
        waitForWrite();

        assertThat(IOUtils.list(logDir).size(), is(1)); // dir with one directory: for this service
        assertThat(IOUtils.list(logDir).get(0).endsWith("service"), is(true));
        assertThat(IOUtils.list(logDir.resolve("service")).size(), is(3)); // log, maintainer file, old/
    }

    @SkipWhenNoSlowTests
    @Test void largeLogShouldBeSplitUp() {
        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));
        DynamicPropertiesManager.setProperty("logging.microstar.sleepBetweenMaintenance", "5s");

        // singleMaxSize is set to 100 (see above)
        logFiles.log("1".repeat(120));
        waitForWrite();
        logFiles.log("2".repeat(120));
        waitForWrite();
        logFiles.log("3".repeat(20));
        waitForWrite();

        // There should now be three files (part 1, 2 and 3 where 3 is 'live')

        final Path maintainerFile = getMaintainerFile();
        final List<String> names = IOUtils.list(logDir.resolve("service")).stream()
            .filter(not(Files::isDirectory))
            .filter(not(maintainerFile::equals))
            .map(path -> path.toFile().getName())
            .toList();

        assertThat(names.size(), is(3));
        assertThat(names.toString(), names.stream().anyMatch(name -> name.contains("_1_.log")), is(true));
        assertThat(names.toString(), names.stream().anyMatch(name -> name.contains("_2_.log")), is(true));
        assertThat(names.toString(), names.stream().anyMatch(name -> name.contains("_3_.log")), is(true));
    }

    @FlakyTest // fails often on Jenkins.
    @Test void onlyOneRunningInstanceShouldDoMaintenance() throws IOException {
        final UUID uuid1 = new UUID(0x1_0000,1);
        final UUID uuid2 = new UUID(0x2_0000,2);
        final UUID uuid3 = new UUID(0x3_0000,3);

        // Act as if there are multiple serviceGroups and versions of a service running.
        // Only one should do maintenance.

        // service-1 becomes maintainer
        final LogFiles logFiles1 = startNewInstance(ServiceId.of("main", "service", "1"), uuid1);
        logFiles1.log("message"); waitForWrite();

        final Path maintainerFile1 = getMaintainerFile();
        final String[] mfParts1 = Files.readString(maintainerFile1).split("/");
        assertThat(mfParts1[0], is(uuid1.toString()));
        assertThat(mfParts1[1], is("1"));
        assertThat(logFiles1.isHistoryMaintainer(), is(true));

        // service-3 becomes maintainer (higher version)
        final LogFiles logFiles3 = startNewInstance(ServiceId.of("main", "service", "3"), uuid3);
        logFiles3.log("message"); waitForWrite();

        final Path maintainerFile3 = getMaintainerFile();
        final String[] mfParts3 = Files.readString(maintainerFile3).split("/");
        assertThat(mfParts3[0], is(uuid3.toString()));
        assertThat(mfParts3[1], is("3"));
        assertThat(logFiles1.isHistoryMaintainer(), is(false));
        assertThat(logFiles3.isHistoryMaintainer(), is(true));

        // service-3 remains maintainer (service-2 has a lower version)
        final LogFiles logFiles2 = startNewInstance(ServiceId.of("main", "service", "2"), uuid2);
        logFiles2.log("message"); waitForWrite();

        final Path maintainerFile2 = getMaintainerFile();
        final String[] mfParts2 = Files.readString(maintainerFile2).split("/");
        assertThat(mfParts2[0], is(uuid3.toString()));
        assertThat(mfParts2[1], is("3"));
        assertThat(logFiles1.isHistoryMaintainer(), is(false));
        assertThat(logFiles2.isHistoryMaintainer(), is(false));
        assertThat(logFiles3.isHistoryMaintainer(), is(true));
    }

    @SkipWhenNoSlowTests
    @Test void maintenanceShouldRelocateOnLogDirChange() {
        final Path logDir1 = logDir.resolve("dir1");
        final Path logDir2 = logDir.resolve("dir2");
        final Path serviceDir1 = logDir1.resolve("service");
        final Path serviceDir2 = logDir2.resolve("service");
        DynamicPropertiesManager.setProperty("logging.microstar.location", logDir1.toFile().getAbsolutePath());
        DynamicPropertiesManager.setProperty("logging.microstar.sleepBetweenMaintenance", "5s");

        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));
        logFiles.log("message");
        waitForWrite();
        assertThat(Files.exists(logDir1.resolve("service")), is(true));
        assertThat("Now: " + getFilenames(serviceDir1), IOUtils.list(serviceDir1).size(), is(3)); // logfile, maintenance file, old/

        DynamicPropertiesManager.setProperty("logging.microstar.location", logDir2.toFile().getAbsolutePath());
        waitForMaintenance();

        assertThat(Files.exists(serviceDir2), is(true));
        assertThat("Now: " + getFilenames(serviceDir2), IOUtils.list(serviceDir2).size(), is(3));
    }

    @SkipWhenNoSlowTests
    @Test void oldLogsShouldBeRemovedWhenTooOld() {
        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));

        DynamicPropertiesManager.setProperty("logging.microstar.history.maxAge", "4s"); // prune time resolution is 1s
        DynamicPropertiesManager.setProperty("logging.microstar.singleMaxSize", "100B");

        final long t0 = System.currentTimeMillis();

        // singleMaxSize is set to 100 (see above)
        logFiles.log("1".repeat(110));
        waitForWrite();
        sleep(1000);
        logFiles.log("1".repeat(110));
        waitForWrite();
        logFiles.log("1".repeat(10));

        waitForMaintenance();

        assertThat("" + getFilenames("service"),     getDirCount("service"),     is(4)); // second part + current part + maintenance + old/
        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(1)); // first part

        waitForCopyToOld();

        assertThat("" + getFilenames("service"),     getDirCount("service"),     is(3)); // current part + maintenance + old/
        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(2)); // first part + second part

        // Wait until maxAge
        final long td = System.currentTimeMillis() - t0;
        final Duration maxAge = DynamicPropertiesManager.getProperty("logging.microstar.history.maxAge", Duration.class).orElseThrow();
        if(td < maxAge.toMillis()) sleep(maxAge.toMillis() - td);

        // next maintenance will remove oldest: first part
        waitForMaintenance();

        assertThat("" + getFilenames("service"),     getDirCount("service"),     is(3)); // current part + maintenance + old/
        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(1)); // second part

        // Between writing first part and second part there was a sleep of 1000.
        // Again sleep as long and the second part should be deleted as well.
        sleep(1000);
        waitForMaintenance();

        assertThat("" + getFilenames("service"),     getDirCount("service"),     is(3)); // current part + maintenance + old/
        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(0)); // second part removed
    }

    @SkipWhenNoSlowTests
    @Test void oldLogsShouldBeRemovedWhenOverSizeLimit() {
        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));
        DynamicPropertiesManager.setProperty("logging.microstar.history.maxSize", "6KB");

        // create 10 files of 1KB
        for(int i=1; i<=10; i++) {
            logFiles.log("1".repeat(1024));
            waitForWrite();
        }
        waitForCopyToOld(); // This will move 9 of those 10 files to old, then remove oldest until 6KB is left

        final long totalSize = getDirSize("service") + getDirSize("service/old");
        assertThat(totalSize + " > 6KB (" + (6 * 1024) + ")", totalSize <= 6 * 1024, is(true));
    }

    @SkipWhenNoSlowTests
    @Test void allOldLogsShouldBeRemovedWhenHistoryIsDisabled() {
        final LogFiles logFiles = startNewInstance(ServiceId.of("main", "service", "1"), new UUID(0x1_0000,1));

        // singleMaxSize is set to 100 (see above)
        logFiles.log("1".repeat(100));
        waitForWrite();
        logFiles.log("2".repeat(50));
        waitForCopyToOld();

        assertThat("" + getFilenames("service"), getDirCount("service"), is(3)); // log, maintainer file, /old
        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(1)); // "1" log

        // Disable history, which should lead to a deletion of all files in /old
        DynamicPropertiesManager.setProperty("logging.microstar.history.enabled", false);
        waitForMaintenance();
        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(0));
        assertThat("" + getFilenames("service"), getDirCount("service"), is(2)); // minus /old

        logFiles.log("3".repeat(100)); // this should lead to "2" becoming old which now means being deleted
        waitForCopyToOld();

        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(0));
        assertThat("" + getFilenames("service"), getDirCount("service"), is(2)); // one added, one removed

        // Enable history, which should lead creation of /old
        DynamicPropertiesManager.setProperty("logging.microstar.history.enabled", true);
        logFiles.log("4".repeat(100));
        waitForCopyToOld();

        assertThat("" + getFilenames("service/old"), getDirCount("service/old"), is(1)); // just added log of "3"
        assertThat("" + getFilenames("service"), getDirCount("service"), is(3)); // plus /old
    }

    private Path getMaintainerFile() {
        return IOUtils.list(logDir.resolve("service")).stream()
            .filter(path -> "maintainer".equals(path.toFile().getName()))
            .findFirst()
            .orElseThrow();
    }
    private int getDirCount(String... more) {
        return noThrow(() -> IOUtils.list(logDir.resolve(more.length == 0 ? "service" : String.join("/", more))).size()).orElse(0);
    }
    private long getDirSize(String... more) {
        return noThrow(() -> (int)IOUtils.list(logDir.resolve(more.length == 0 ? "service" : String.join("/", more))).stream()
            .filter(path -> !"maintainer".equals(path.toFile().getName()))
            .filter(path -> path.toFile().isFile())
            .mapToLong(path -> path.toFile().length())
            .sum()).orElse(0);
    }
    private List<String> getFilenames(String... more) {
        return getFilenames(pathOf(logDir.toAbsolutePath().toString(), more.length == 0 ? new String[]{"service"} : more));
    }
    private static List<String> getFilenames(Path dir) {
        //noinspection StringConcatenationMissingWhitespace
        return noThrow(() ->IOUtils.list(dir).stream()
            .sorted()
            .map(path -> path.toFile().getName() + "(" + IOUtils.sizeOf(path) + "B/" + (System.currentTimeMillis() - path.toFile().lastModified()) + "ms)")
            .toList()
        ).orElse(Collections.emptyList());
    }
}