package net.microstar.spring.logging;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ExceptionUtils;
import net.microstar.common.util.VersionComparator;
import net.microstar.spring.logging.LoggingProperties.History;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.settings.DynamicPropertyRef;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.fasterxml.jackson.databind.util.ClassUtil.getRootCause;
import static java.util.function.Predicate.not;
import static net.microstar.common.io.IOUtils.pathOf;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.pipe;
import static net.microstar.spring.logging.LogFiles.OLD_NAME;
import static net.microstar.spring.logging.LogFilesUtil.mkdirsForFileReturnsExists;

@Slf4j
class LogMaintenance {
    private static final String MAINTAINER_NAME = "maintainer";
    public final DynamicPropertiesRef<LoggingProperties> settingsRef = DynamicPropertiesRef.of(LoggingProperties.class);
    private final DynamicPropertyRef<String>             locationRef = DynamicPropertyRef.of("logging.microstar.location").withDefault("");
    private final DynamicPropertyRef<History>            historyRef = DynamicPropertyRef.of("logging.microstar.history", History.class);
    private final ServiceId serviceId;
    private final UUID instanceId;
    private final Supplier<Optional<Path>> getLogFile;
    private final Runnable closeLogFile;

    private String usedLocation;
    private long nextMaintenanceTime = 0;

    LogMaintenance(ServiceId serviceId, UUID instanceId, Supplier<Optional<Path>> getLogFile, Runnable closeLogFile) {
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.getLogFile = getLogFile;
        this.closeLogFile = closeLogFile;
        usedLocation = settingsRef.get().location;
        locationRef.onChange(this::relocatedTo);
        historyRef.onChange(this::performMaintenanceNow);
    }

    void performMaintenance() {
        final long now = System.currentTimeMillis();
        if(now < nextMaintenanceTime) return;
        performMaintenanceNow();
    }

    void performMaintenanceNow() {
        nextMaintenanceTime = System.currentTimeMillis() + settingsRef.get().sleepBetweenMaintenance.toMillis();
        if(!isHistoryMaintainer()) return; // there may be multiple instances while only one of them is maintainer

        synchronized (settingsRef) {
            if(!Files.exists(getOldDir())) IOUtils.makeSureDirectoryExists(getOldDir());
            moveOldFilesToOld();

            final History historyProps = settingsRef.get().history;
            if (historyProps.enabled) {
                // Get size of log dir and subtract that from maxSize so we know how large the old/ dir can be
                final long currentSize = IOUtils.list(getLogDir()).stream()
                    .filter(not(Files::isDirectory))
                    .mapToLong(p -> noThrow(() -> Files.size(p)).orElse(0L))
                    .sum();

                pipe(IOUtils.list(getOldDir()),
                    oldLogFiles -> LogFilesUtil.pruneOnAge(oldLogFiles, historyProps.maxAge),
                    oldLogFiles -> LogFilesUtil.pruneOnTotalSize(oldLogFiles, historyProps.maxSize.minus(currentSize))
                );
            } else {
                IOUtils.delTree(getOldDir());
            }
        }
        LogFiles.triggerMaintenanceEvent();
    }

    private void moveOldFilesToOld() {
        final Path logDir = getLogDir();
        final long maxLastModified = System.currentTimeMillis() - settingsRef.get().sleepBetweenMaintenance.toMillis() * 2;
        noThrow(() -> IOUtils.list(logDir).stream()).orElseGet(Stream::of)
            .map(Path::toFile)
            .filter(File::isFile)
            .filter(oldLogFile -> !MAINTAINER_NAME.equals(oldLogFile.getName()))
            .filter(oldLogFile -> oldLogFile.lastModified() < maxLastModified) // exclude 'currently logging to' files
            .forEach(oldLogFile ->
                // In some very occasional circumstances the move fails because
                // the to-directory can't be created, probably due to a race condition.
                // Overcome this by retrying the move once more.
                noThrow(() -> IOUtils.move(oldLogFile.toPath(), getOldDir().resolve(oldLogFile.getName())), ex ->
                    // retry the move
                    IOUtils.move(oldLogFile.toPath(), getOldDir().resolve(oldLogFile.getName()))
                )
            );
    }
    private void relocatedTo(String newLocation) {
        final Optional<Path> logFileOpt = getLogFile();
        if(logFileOpt.isEmpty()) return;

        final Path   currentLogFile = logFileOpt.get();
        final LogFileInfo   logInfo = LogFileInfo.of(currentLogFile);
        final Path       newLogFile = pathOf(newLocation, serviceId.name, LogFileInfo.of(serviceId, instanceId, logInfo.part).toFilename());
        final Path        newLogDir = newLogFile.getParent();
        final Path        oldLogDir = getLogDir();

        if(newLogDir.equals(oldLogDir)) return;
        log.info("Detected log target change to " + newLogDir.getParent().toAbsolutePath());

        if(!mkdirsForFileReturnsExists(newLogDir.resolve(OLD_NAME).toFile())) {
            log.error("Unable to create relocated folder: " + newLogDir.toAbsolutePath());
            return;
        }

        // Close current log file (and move it) so the next write will open it again at the new location
        closeLogFile();
        synchronized (settingsRef) {
            usedLocation = newLocation;
            IOUtils.move(currentLogFile, newLogFile);
            writeMaintainerFile();
        }

        // Move old log files to new dir
        if(isHistoryMaintainer()) {
            noThrow(() -> IOUtils.move(oldLogDir, newLogDir), exception -> log.error("Unable to relocate logs to " + newLogDir));
            noThrow(() -> Files.deleteIfExists(oldLogDir.resolve(MAINTAINER_NAME)));
        }
    }


    boolean isHistoryMaintainer() {
        final Path maintainerFile = getMaintainerFile();
        if(Files.exists(maintainerFile)) {
            final boolean wasRecentlyChanged    = LogFilesUtil.wasFileTouchedSinceAgo(maintainerFile, settingsRef.get().sleepBetweenMaintenance.multipliedBy(2));
            final String  fileContents          = noThrow(() -> Files.readString(maintainerFile, StandardCharsets.UTF_8)).orElse(""); // set to empty if unable to read (probably when another is writing to it)
            final String  maintainerInstanceId  = fileContents.split("/")[0];
            final String  maintainerVersion     = (fileContents + "/0").split("/")[1]; // add slash to prevent any out-of-bounds
            final boolean fileIsForThisInstance = maintainerInstanceId.equals(instanceId.toString());
            final boolean thisVersionIsHigher   = !fileContents.isEmpty() && VersionComparator.OLDEST_TO_NEWEST.compare(serviceId.version, maintainerVersion) > 0;

            if(wasRecentlyChanged && !thisVersionIsHigher && !fileIsForThisInstance) return false; // another instance is maintainer
        }
        randomSleep(); // to prevent collisions with other VMs running in the same cadence
        return ExceptionUtils.noThrowMap(() -> { writeMaintainerFile(); return true;}, ex -> false);
    }
    private Path getMaintainerFile() {
        return getLogDir().resolve(MAINTAINER_NAME);
    }
    private void writeMaintainerFile() {
        final Path maintainerFile = getMaintainerFile();
        final String maintainerText = instanceId + "/" + serviceId.version;

        if(!mkdirsForFileReturnsExists(maintainerFile.toFile())) {
            log.error("No dir for " + maintainerFile);
            throw new IllegalStateException("No log dir");
        }
        writeLocked(maintainerFile, maintainerText);
    }
    private void writeLocked(Path file, String text) {
        // Multiple VMs may be running, one for each instance of the service
        try (final FileOutputStream out = new FileOutputStream(file.toFile());
             final FileChannel channel = out.getChannel();
             final FileLock lock = channel.lock()) { // lock access to prevent overlapping writes
            if(lock.isValid()) out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch(final Exception ex) {
            if(getRootCause(ex) instanceof FileLockInterruptionException) return; // not fatal
            if(ex instanceof OverlappingFileLockException) {
                randomSleep(); // to prevent collisions with other VMs running in the same cadence
                isHistoryMaintainer();
                return;
            }
            ex.printStackTrace(); // extra info for debugging -- remove if this (almost) never happens anymore
            log.error("Unable to write to " + file + ": " + Optional.ofNullable(ex.getMessage()).orElse(ex.getClass().getSimpleName()), ex);
            throw new IllegalStateException("Failed to write");
        }
    }

    private Path getLogDir() { synchronized (settingsRef) { return pathOf(usedLocation, serviceId.name); } }
    private Path getOldDir() { return getLogDir().resolve(OLD_NAME); }

    private Optional<Path> getLogFile() {
        return getLogFile.get();
    }
    private void closeLogFile() {
        closeLogFile.run();
    }
    private static final Random random = new Random();
    private static void randomSleep() {
        final int min = 10;
        final int max = 50;
        try {
            //noinspection UnsecureRandomNumberGeneration
            Thread.sleep(random.nextInt(min, max + 1));
        } catch(final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
