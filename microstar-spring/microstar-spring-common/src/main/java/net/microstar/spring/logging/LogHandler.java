package net.microstar.spring.logging;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ExceptionUtils;
import net.microstar.common.util.StringUtils;
import net.microstar.common.util.ThreadBuilder;
import net.microstar.spring.settings.DynamicPropertiesRef;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import static net.microstar.common.io.IOUtils.pathOf;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.spring.logging.LogFilesUtil.mkdirsForFileReturnsExists;

@Slf4j
class LogHandler {
    private static           String  sanitizedReplacement = "<sanitized>";
    private static @Nullable Pattern sanitizePattern = null;
    private static final DynamicPropertiesRef<LoggingProperties> settings = DynamicPropertiesRef.of(LoggingProperties.class)
        .onChange(props -> {
            sanitizedReplacement = props.sanitizedReplacement;
            sanitizePattern = props.sanitizePattern.isEmpty() ? null : noThrow(() -> Pattern.compile(props.sanitizePattern)).orElse(null);
        })
        .callOnChangeHandlers();

    private final List<String>          messagesToWriteList = new ArrayList<>(100);
    private final ServiceId             serviceId;
    private final UUID                  instanceId;
    private final BlockingQueue<String> queue;
    private final Thread                thread;
    private final LogMaintenance maintenance;

    @Nullable         File              logFile = null;
    @Nullable private FileOutputStream  logFileStream = null;
    @Nullable private BiConsumer<String,Map<String,String>> eventsObserver = null;

    private int                         partNumber = 1;
    private boolean                     stop = false;


    LogHandler(ServiceId serviceId, UUID instanceId, BlockingQueue<String> queue) {
        this.serviceId = serviceId;
        this.instanceId = instanceId;
        this.queue = queue;

        maintenance = new LogMaintenance(serviceId, instanceId, () -> Optional.ofNullable(logFile).map(File::toPath), this::closeLogFile);

        thread = new ThreadBuilder(this::loopDrainQueue)
            .isDaemon(true)
            .name("LogHandler")
            .start();
    }

    /** Because the LogHandler is started before Spring, no dependency injection is available */
    void setServiceEventsObserver(BiConsumer<String,Map<String,String>> eventsObserver) {
        this.eventsObserver = eventsObserver;
    }

    void stop() {
        if(thread.isAlive()) {
            stop = true;
            try { thread.interrupt(); thread.join(); } catch (final InterruptedException ignored) {/**/} // NOSONAR interrupt
        }
    }

    private void loopDrainQueue() {
        long lastWriteTime = 0;
        while(!stop) {
            ExceptionUtils.noThrowMap(() -> Optional.ofNullable(
                queue.poll(settings.get().sleepBetweenWrites.toMillis(), TimeUnit.MILLISECONDS)), ex -> { stop = true; return Optional.<String>empty(); })
                .ifPresent(messagesToWriteList::add);

            queue.drainTo(messagesToWriteList);
            limitMessagesToWriteList();

            final long now = System.currentTimeMillis();
            if(lastWriteTime < now - settings.get().sleepBetweenWrites.toMillis()) {
                lastWriteTime = now;
                write();
            }

            // Maintenance should also be done when this instance is not logging
            // This instance may be maintainer and needs to prune old log files.
            maintenance();
        }
    }

    private void limitMessagesToWriteList() {
        if(messagesToWriteList.size() > LogFiles.LINE_CACHE_MAX_SIZE) messagesToWriteList.subList(0, messagesToWriteList.size() - LogFiles.LINE_CACHE_MAX_SIZE).clear();
    }

    // If anything goes wrong we can throw but that will just stop log gathering.
    // If anything goes wrong that probably has to do with configuration (or full storage).
    // Nothing we can do about it here. No one to notify. Who will see?
    // Perhaps later alternatives are added like mailing someone that can fix storage or config.

    private void write() {
        if (logFileStream == null) openLogFile();
        if (logFileStream == null) return; // happens when openLogFile fails
        if(messagesToWriteList.isEmpty()) {
            updateLastModified();
            return;
        }
        final String logText = takeTextToWrite();
        write(logText);

        // eventEmitter is null when logging before the eventEmitter is initialized
        if(eventsObserver != null) eventsObserver.accept("LOG", Map.of(
            "log", logText
        ));

        // The written text above may make the file longer than singleMaxSize but not by much
        // and the code is simpler and more performant if this check is not done when reducing
        // messagesToWriteList.
        splitLogFileIfTooLarge();
        LogFiles.triggerWriteEvent();
    }
    private void openLogFile() {
        final Path serviceLogDir = pathOf(settings.get().location, serviceId.name);
        IOUtils.makeSureDirectoryExists(serviceLogDir.resolve(LogFiles.OLD_NAME));
        logFile = new File(serviceLogDir.toFile(), LogFileInfo.of(serviceId, instanceId, partNumber).toFilename());
        log.info("logFile: " + logFile);
        if(!mkdirsForFileReturnsExists(logFile)) {
            log.error("Unable to create logging directory at " + logFile.getParentFile().getAbsolutePath());
        } else {
            logFileStream = noThrow(() -> new FileOutputStream(logFile, true))
                .orElseGet(() -> {
                    log.error("Unable to open for writing: " + logFile);
                    return logFileStream; // no change
                });
        }
        maintenance();
    }
    private void closeLogFile() {
        Optional.ofNullable(logFileStream)
            .ifPresent(stream -> noThrow(stream::close));
    }

    private void updateLastModified() {
        // For maintenance to see log files as 'current', they need to have
        // an 'age' (lastModified ago) of less than 2 x maintenance sleep
        final long minAge = settings.get().sleepBetweenMaintenance.toMillis();

        // update the timestamp of an existing file which makes it 'current'
        // for maintenance, meaning it won't move it to 'old' (or delete if history is disabled)
        if(logFile != null && (System.currentTimeMillis() - logFile.lastModified()) > minAge) {
            //noinspection ResultOfMethodCallIgnored
            logFile.setLastModified(System.currentTimeMillis()); // NOSONAR -- nothing to do on failure so no check
        }
    }
    private String takeTextToWrite() {
        final String textToWrite = messagesToWriteList.stream().reduce((all, msg) -> String.join("\n", all, msg)).orElse("");
        messagesToWriteList.clear();
        return textToWrite;
    }
    private void write(String textToWrite) {
        if(logFileStream == null) return; // never get here but compiler doesn't know
        noThrow(() -> {
                logFileStream.write(sanitize(textToWrite).getBytes(StandardCharsets.UTF_8));
                logFileStream.write('\n');
                logFileStream.flush();
            },
            ex -> log.error("Unable to write to log file: " + ex.getMessage()));
    }
    private void splitLogFileIfTooLarge() {
        final LoggingProperties props = settings.get();

        if(logFile != null && logFile.length() > props.singleMaxSize.getBytesLong()) {
            partNumber++;
            logFile = null; // next write will create a new log file with the increased part number
            if(logFileStream != null) { noThrow(() -> logFileStream.close()); logFileStream = null; }
        }
    }

    private void maintenance() {
        if(logFile != null) maintenance.performMaintenance();
    }

    boolean isHistoryMaintainer() {
        return maintenance.isHistoryMaintainer();
    }

    static String sanitize(String in) {
        String sanitized = in;
        if(MicroStarConstants.CLUSTER_SECRET.length() > 1) sanitized = sanitized.replace(MicroStarConstants.CLUSTER_SECRET, sanitizedReplacement);
        if(sanitizePattern != null) sanitized = StringUtils.replaceGroups(sanitized, sanitizePattern, sanitizedReplacement);
        return sanitized;
    }
}
