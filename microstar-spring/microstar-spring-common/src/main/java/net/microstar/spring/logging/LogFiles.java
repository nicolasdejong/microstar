package net.microstar.spring.logging;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;

import javax.annotation.Nullable;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/** <pre>
 * Manages log files for a single service. Has a lifecycle that is unrelated
 * to Spring as it starts when the application starts and stops when the application
 * stops -- continuing on when Spring restarts.
 *
 * There are three tasks:
 *  - handle incoming log messages and store them in the latest log file
 *  - split log file when getting larger than configured singleMaxSize
 *  - delete log old log files depending on configured maxAge and maxSize (maintenance)
 *
 * The log file is stored in a directory that has the service name (not group and version).
 *
 * Example: {logDir}/someService/
 *
 * The name of the log consists of:
 *  - datetime of log start (e.g. 20220630112233)
 *  - part-number in case the log file gets larger than configured singleMaxSize (1..)
 *  - first part of instanceId (e.g. bd204deaa1a8)
 *  - service version
 *  - service group
 *
 *  Example: 20220630112233_main_3.12_bd204deaa1a8_1.log
 *           ^              ^    ^        ^        ^
 *           datetime      group version  instance part
 *
 *  Several instances of the service (groups, versions, instances) may be running.
 *  A 'maintenance'  file will be created that contains the instanceId of the service
 *  that is doing maintenance (deleting old log files and relocating them if log dir
 *  location changes). For a service, there is only *one* instance doing maintenance.
 *  Maintenance will be done on all old log files of services with the same name.
 *
 *  Log files that are not actively written to will be move to the old/ directory
 *  (so {logDir}/someService/old). 'Current' files will be periodically touched
 *  (which doesn't write but updates the lastModified) so the maintainer knows what
 *  files to move to old (whose lastModified is longer ago).
 */
@Slf4j
public final class LogFiles {
    private LogFiles() {}
    private static final AtomicReference<LogFiles> lastInstance = new AtomicReference<>(new LogFiles());


    /** When an illegal log location is provided the log messages can't be written and will
      * accumulate in messagesToWriteList. To prevent too much memory use, limit its size here.
      */
    static final int LINE_CACHE_MAX_SIZE = 10_000;
    static final String OLD_NAME = "old";

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private       Optional<LogHandler> handler = Optional.empty();


    public static LogFiles getInstance() {
        @Nullable final LogFiles instance = lastInstance.get();
        if(instance != null) return instance;
        synchronized(lastInstance) {
            if(lastInstance.get() != null) lastInstance.set(new LogFiles());
        }
        return lastInstance.get();
    }

    /** This can be called before start() */
    public void log(String message) {
        queue.add(message);
    }

    public LogFiles start(ServiceId serviceId, UUID instanceId) {
        stop();
        handler = Optional.of(new LogHandler(serviceId, instanceId, queue));
        return this;
    }

    /** Because the logging is started before Spring, no dependency injection is available */
    public void setServiceEventsObserver(BiConsumer<String, Map<String,String>> eventsObserver) {
        handler.ifPresent(logHandler -> logHandler.setServiceEventsObserver(eventsObserver));
    }

    public boolean isHistoryMaintainer() {
        return handler.map(LogHandler::isHistoryMaintainer).orElse(false);
    }

    /** Returns the path to the current log file. Temporary implementation as this
      * is not future-proof. The data should be streamed instead and may come from
      * file-system as well as from other sources like database.
      */
    public Optional<Path> getCurrentPath() {
        return handler
            .map(h -> h.logFile)
            .filter(File::exists)
            .map(File::toPath);
    }

    // These next three are only needed for testing concurrency

           void     stop()           { handler.ifPresent(LogHandler::stop); handler = Optional.empty(); }
    static LogFiles getNewInstance() { return new LogFiles(); }
    static void     clear()          { lastInstance.set(new LogFiles()); triggerAll(); }


    // Below is only used by tests (to prevent having timing issues with sleep())

    private static final long WAIT_TIMEOUT = 1000;
    private static final AtomicInteger eventWriteObject = new AtomicInteger(0);
    private static final AtomicInteger eventMaintenanceObject = new AtomicInteger(0);
    private static void trigger(AtomicInteger eventObj) {
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized(eventObj) { eventObj.incrementAndGet(); eventObj.notifyAll(); } // NOSONAR -- eventObj is local only
    }
    private static void triggerAll() {
        trigger(eventWriteObject);
        trigger(eventMaintenanceObject);
    }
    private static void waitFor(AtomicInteger eventObj) {
        final int iBefore = eventObj.get();
        noThrow(() -> {
            synchronized(eventObj) { // NOSONAR -- eventObj is local only
                int tries = 5;
                while (eventObj.get() == iBefore && tries --> 0) {
                    eventObj.wait(WAIT_TIMEOUT);
                }
            }
        });
    }

    static void triggerWriteEvent() { trigger(eventWriteObject); }
    static void triggerMaintenanceEvent() { trigger(eventMaintenanceObject); }

    static void waitForWriteEvent() { waitFor(eventWriteObject); }
    static void waitForMaintenanceEvent() { waitFor(eventMaintenanceObject); }
}
