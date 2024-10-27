package net.microstar.tools.watchdog;

import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.FileSystemDataStore;
import net.microstar.common.datastore.SqlDataStore;
import net.microstar.common.io.DeletedFileNotifier;
import net.microstar.common.util.DynamicReference;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static net.microstar.common.io.IOUtils.pathOf;
import static net.microstar.common.util.ExceptionUtils.noThrow;

/** <pre>
 * The Watchdog listens for availability of a given port and starts a Dispatcher
 * if the port is available. That is all. The Watchdog should remain relatively
 * simple so it is very reliable and makes sure there is always a Dispatcher running.
 * Without a Dispatcher the system is not reachable and requires local admin
 * maintenance which should be avoided.
 *
 * A few things that are worth mentioning:
 * - The Watchdog runs a loop where a Dispatcher is started when a port is available.
 * - The Watchdog logs to console and log file (watchdog.log). When it restarts itself
 *   the console output will be gone so logfile should be the place to be when looking
 *   for status output.
 * - The log file will be observed so that when another Watchdog logs to it, this
 *   Watchdog will immediately stop to prevent multiple Watchdogs from running.
 * - The Watchdog checks the current directory for new versions of itself.
 *   If a 'microstar-watchdog-[newerversion].jar' is found, it will start it.
 * - Watchdog wants to run from 'microstar-watchdog.jar' because that is the file that will
 *   be started by the system cron / scheduled-task. If the current jar is not that,
 *   it will copy the current jar to 'Watchdog.jar' and start that one.
 */
@SuppressWarnings({"InfiniteLoopStatement", "squid:S2189"}) // Watchdog runs forever until killed or replaced
public final class Watchdog {
    static boolean throwInsteadOfExit = false;
    private final Settings settings;
    private boolean noJarFound = false;
    public static BlockingDataStore jarsStore;

    public static void main(String... args) {
        Log.toFile(pathOf("./log/watchdog.log"));
        Log.info("Started WatchDog");
        jarsStore = createStoreFromArgs(args);
        MiniServer.init(args);
        VersionChecker.init(jarsStore, args);
        VersionChecker.check();
        exitIfDeleteFileIsDeleted();
        try {
            new Watchdog(Settings.of(args)).run();
            Log.info("Finished");
        } catch(final ExitException e) {
            Log.info("Exit with code {}", e.code);
            exit(e.code);
        } catch(final Exception e) {
            Log.error("Encountered unrecoverable error: {}{}", e.getClass().getSimpleName(), Optional.ofNullable(e.getMessage()).map(msg -> ": " + msg).orElse(""));
            for(final StackTraceElement ste : e.getStackTrace()) Log.dump(ste);
            Log.info("Exit");
            exit(10);
        }
    }

    @SuppressWarnings("CallToSystemExit")
    public static void exit(int code) {
        PidFile.del();
        if(throwInsteadOfExit) throw new ExitException(code); // to prevent exit() in unit tests
        System.exit(code);
    }


    private Watchdog(Settings args) {
        settings = args;
    }

    private void run() {
        Log.info("Watchdog V{} started using Java {} with PID {} by {}",
            Optional.of(VersionChecker.CURRENT_VERSION).filter(s->!s.isEmpty()).orElse("?"),
            Runtime.version().toString().split("\\+")[0],
            ProcessHandle.current().pid(),
            ProcessHandle.current().info().user().orElse("unknown"));
        Log.info("Checking if port {} is in use", settings.port);

        while(true) {
            exitIfAnotherWatchdogStarted();
            VersionChecker.check();
            if(isPortAvailable()) startDispatcher(); else noJarFound = false;
            sleep();
        }
    }
    private boolean isPortAvailable() {
        try {
            // If this happens while a Dispatcher is starting, there is the possibility that
            // the Dispatcher tries to open the port in just the millisecond that the port is
            // open here. Then the Dispatcher startup will fail. No way around that. A new
            // Dispatcher will be started from the Watchdog so this is not fatal.
            final ServerSocket ss = new ServerSocket(settings.port);
            ss.close();
            return true;
        } catch (final IOException e) {
            return false;
        }
    }
    private void sleep() { sleep(settings.interval); }
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException e) { // NOSONAR -- not rethrown but exit
            Log.info("Interrupted -- exit");
            throw new ExitException(10);
        }
    }
    private void startDispatcher() {
        final Optional<String> jarPath = settings.getDispatcherJarPath();
        if(jarPath.isEmpty()) {
            if(noJarFound) return;
            Log.error("No dispatcher jar found! -- waiting for it to appear");
            noJarFound = true;
            return;
        }
        noJarFound = false;
        try {
            Log.info("Starting dispatcher: {}", jarPath.orElse("?"));
            final List<String> javaCommand = settings.getJavaCommand(jarPath);
            Log.info("Start: {}", javaCommand.stream()
                .map(kv -> kv.replaceAll("(?i)(secret|password)=(.{1,3}).*$", "$1=$2<redacted>"))
                .toList());
            final ProcessBuilder procBuilder = new ProcessBuilder(javaCommand).redirectErrorStream(true);
            final Process proc = procBuilder.start();
            final BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            final boolean[] startingFinished = { false };

            new Thread(() -> {
                @Nullable String line;
                while ((line = noThrow(in::readLine).orElse(null)) != null) if (line.contains("started on port")) break;

                synchronized (startingFinished) {
                    startingFinished[0] = line != null;
                    startingFinished.notifyAll();
                }

                if(line == null) return;

                Log.info("Dispatcher started: {}", line);

                // Keep consuming process output or the process will block on filled output buffers
                while(noThrow(in::readLine).isPresent()) noOp();
            }).start();

            synchronized (startingFinished) {
                startingFinished.wait(Duration.ofSeconds(30).toMillis()); // NOSONAR -- waiting not in loop
                if(!startingFinished[0]) {
                    Log.error("Dispatcher failed to initialize");
                    if(proc.isAlive()) proc.destroy();
                }
            }

        } catch (final IOException e) {
            Log.error("Failed to start command: {}", e.getMessage());
        } catch (final InterruptedException e) { // NOSONAR -- re-interrupt
            Log.error("Waiting for starting command was interrupted");
        }
    }
    @SuppressWarnings("CallToSystemExit")
    private static void exitIfAnotherWatchdogStarted() {
        if(!PidFile.isOurs()) {
            Log.info("PID file changed -- exit");
            throw new ExitException(0);
        }
    }
    private static void exitIfDeleteFileIsDeleted() {
        final Path deleteFile = Path.of("DeleteToStopWatchdog");
        DeletedFileNotifier.forFile(deleteFile, "Running Watchdog will stop when this file is deleted", () -> {
            Log.info("Stopping because the {} file was deleted", deleteFile.getFileName());
            exit(0);
        });
    }
    @SuppressWarnings("EmptyMethod")
    private void noOp() { /* no operation */}
    private static BlockingDataStore createStoreFromArgs(String... argsIn) {
        // Uses compact datastore configuration (see BOOTSTRAP.md)
        @Nullable Map<String,String> args = null;
        for(final String arg : argsIn) {
            final String prefix = "var:microstar.dataStores.jars=@;";
            if(arg.startsWith(prefix)) {
                args = Arrays.stream(arg.substring(prefix.length()).split(";"))
                    .map(t->t.split("=",2))
                    .collect(Collectors.toMap(t->t[0], t->t[1]));
            }
        }
        DataStore store = new FileSystemDataStore(Path.of("."));
        if(args != null && args.containsKey("type")) {
            Log.info("Opening Datastore of type " + args.get("type"));
            if("filesystem".equals(args.get("type"))) store = new FileSystemDataStore(Path.of(Optional.ofNullable(args.get("root")).orElse(".")));
            else
            if("database".equals(args.get("type")))  store = new SqlDataStore(args.get("url"), args.get("section"), args.get("table"), args.get("user"), args.get("password"), 5, Duration.ofSeconds(10));
        }
        return BlockingDataStore.forStore(DynamicReference.of(store));
    }
}