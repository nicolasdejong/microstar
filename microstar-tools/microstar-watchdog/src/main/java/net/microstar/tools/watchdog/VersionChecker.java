package net.microstar.tools.watchdog;

import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.FileSystemDataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReference;
import net.microstar.common.util.StringUtils;
import net.microstar.common.util.VersionComparator;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;

/**
 *  Check for new versions and start one if found.
 *  Also make sure this is running from microstar-watchdog.jar.
 *  If not, copy this jar to microstar-watchdog.jar and start it.<p>
 *
 *  The microstar-watchdog.jar name should be constant as scripts,
 *  cron-jobs or scheduled tasks may depend on it.
 */
public final class VersionChecker {
    private VersionChecker() {}
    private static final Comparator<String> VERSION_COMPARATOR = VersionComparator.OLDEST_TO_NEWEST;
    private static final Comparator<String> PATH_VERSION_COMPARATOR = Comparator.comparing(name -> name.replaceAll("^.*-([^-]+)\\.jar", "$1"), VERSION_COMPARATOR);
    private static final Class<?>           MAIN_CLASS = Watchdog.class;
    private static final String             WATCHDOG_JAR_NAME = "microstar-watchdog.jar";
    private static final String             VERSION_TXT_NAME = "VERSION.txt";
            static final String             CURRENT_VERSION;
    private static final boolean            IS_RUNNING_FROM_IDE;
    private static final String             JAR_PATH;
    private static       String[]           commandLineArgs = {};
    private static       BlockingDataStore  store;
    private static       long               lastVersionCheckTime = 0;
    private static       List<String>       previousWatchdogJars = new ArrayList<>();
    private static       boolean            skipChecks = false;


    static {
        @SuppressWarnings("ConstantConditions") // assumption is that this class is always found in classpath
        final String pathName = MAIN_CLASS.getClassLoader()
            .getResource(MAIN_CLASS.getCanonicalName().replace(".","/") + ".class")
            .getPath().split("!")[0]
            .replaceFirst("^\\w+:", "");

        store = BlockingDataStore.forStore(DynamicReference.of(new FileSystemDataStore(Path.of(".")))); // to be overwritten by init()

        IS_RUNNING_FROM_IDE = !pathName.endsWith(".jar");
        if(IS_RUNNING_FROM_IDE) {
            CURRENT_VERSION = "0";
            JAR_PATH = ".";
            Log.info("Running from IDE");
        } else {
            JAR_PATH = pathName; // used by getCurrentVersion which checks the name for the version
            final String v = getCurrentVersion();
            CURRENT_VERSION = v.contains("19700101.000000") ? v.split("-")[0] : v;
            Log.info("Version: {}", CURRENT_VERSION.isEmpty() ? "?" : CURRENT_VERSION);
        }
    }

    static void init(BlockingDataStore dataStore, String... cliArgs) {
        store = dataStore;
        commandLineArgs = cliArgs;
        if(Arrays.asList(cliArgs).contains("noVersionCheck")) skipChecks = true;
    }

    /** Algorithm of check():<pre>
     *
     * - Is there a jar with a higher version?
     *     - Start that one and exit
     * - Not running from watchdog.jar?
     *     - Overwrite watchdog.jar with the current jar
     *     - Start that one and exit
     */
    @SuppressWarnings({"NonBooleanMethodNameMayNotStartWithQuestion"})
    static void check() {
        if(IS_RUNNING_FROM_IDE || skipChecks) return;

        // Version check -- When list is coming from database it can be somewhat expensive
        final long now = System.currentTimeMillis();
        if(now - lastVersionCheckTime < 25_000) return;
        lastVersionCheckTime = now;
        final List<String> versionedWatchdogJars = getVersionedWatchdogJars(); // ordered a-z so last is most recent

        // As this method is called every 10s, return quickly if no work needs to be done
        if(versionedWatchdogJars.equals(previousWatchdogJars)) return;
        previousWatchdogJars = versionedWatchdogJars;

        whenNewerJarExistsStartItAndExit(versionedWatchdogJars);
        whenNotRunningFromBareWatchdogCopyAndRestart();
    }

    private static void whenNewerJarExistsStartItAndExit(List<String> jars) {
        getLastOf(jars)
            .filter(VersionChecker::isNewer)
            .ifPresent(newerJar -> {
                Log.info("Most recent version ({}) is newer than current ({}). Switch to newest", getVersionInFilenameOf(newerJar), CURRENT_VERSION.isEmpty() ? "?" : CURRENT_VERSION);
                startJarAndExit(newerJar);
            });
    }
    private static void whenNotRunningFromBareWatchdogCopyAndRestart() {
        if(!WATCHDOG_JAR_NAME.equals(filenamePartOf(JAR_PATH))) { // e.g. 'microstar-watchdog.jar' not equal to 'microstar-watchdog-V1234.jar'
            Log.info("Overwriting {} with currently running (newest) jar {}", WATCHDOG_JAR_NAME, JAR_PATH);
            final Path runningJarPath = IOUtils.pathOf(JAR_PATH);
            final Path watchdogJar = runningJarPath.getParent().resolve(WATCHDOG_JAR_NAME);
            attemptToDeleteForAWhile(watchdogJar);
            IOUtils.copy(runningJarPath, watchdogJar); // If deletion of watchdogJar failed because still locked after a while, this will throw

            try {
                try (FileSystem zipFileSystem = FileSystems.newFileSystem(watchdogJar)) {
                    final Path versionPath = zipFileSystem.getPath(VERSION_TXT_NAME);
                    Files.writeString(versionPath, CURRENT_VERSION);
                }
            } catch(final Exception fail) {
                Log.error("Failed to add {} to zip: {}", VERSION_TXT_NAME, fail.getMessage());
                return; // don't start the new jar: it won't be able to get the version
            }

            Log.info("Switch to {}", WATCHDOG_JAR_NAME);
            startJarAndExit(WATCHDOG_JAR_NAME);
        }
    }

    private static boolean isNewer(String jar) {
        return VERSION_COMPARATOR.compare(CURRENT_VERSION, getVersionInFilenameOf(jar)) < 0;
    }
    private static void startJarAndExit(String newJar) {
        noThrow(() -> {
            Log.info("Start jar {} and exit", newJar);
            final Path jarPath = newJar.endsWith(WATCHDOG_JAR_NAME) // only download jars that contain a version in the name
                ? IOUtils.pathOf(newJar)
                : download(newJar).orElseThrow(() -> new IllegalStateException("Download failed"));

            final ProcessBuilder procBuilder = new ProcessBuilder()
                .directory(Path.of(".").toAbsolutePath().toFile()) // newJar is in temp but should run from local dir
                .command(Stream.concat(
                    Stream.of(Settings.JAVA_CMD, "-jar", jarPath.toAbsolutePath().toString()),
                    Stream.of(commandLineArgs)).toList()
                );
            procBuilder.inheritIO();
            procBuilder.start();

            //noinspection CallToSystemExit
            System.exit(0);
        }, ex -> Log.error("Unable to start {}: {}", newJar, ex.getMessage()));
    }
    private static Optional<Path> download(String jarNameToDownload) {
        Log.info("download " + jarNameToDownload + " from store.");
        return store.readStream(jarNameToDownload).map(stream -> {
            final Path outPath = IOUtils.pathOf(jarNameToDownload);
            try (final OutputStream fout = new FileOutputStream(outPath.toFile())) {
                IOUtils.copy(stream, fout);
                return outPath;
            } catch (IOException e) {
                Log.error("Unable to download: " + jarNameToDownload);
                throw new IllegalStateException("Unable to download jar", e);
            }
        });
    }

    private static String getCurrentVersion() {
        if(IS_RUNNING_FROM_IDE) return "0";
        try {
            final String versionFromFilename = getVersionInFilenameOf(JAR_PATH);
            if(!"0".equals(versionFromFilename)) return versionFromFilename;
            return IOUtils.getResourceAsString(VersionChecker.class, VERSION_TXT_NAME).orElse("");
        } catch(final Exception e) {
            Log.error("Unable to determine version of running jar: {}", e.getMessage());
            // Unable to read or unexpected format
            return "9999999"; // This makes that WatchDog.jar no longer updates itself (prevents endless loop)
        }
    }
    private static String getVersionInFilenameOf(String filename) {
        return StringUtils.getRegexGroup(filename, "^.*?-(\\d.*?)\\.jar").orElse("0");
    }
    private static List<String> getVersionedWatchdogJars() {
        return store.list().stream()
            .filter(item -> item.getFilename().matches("(?i)^microstar-watchdog-\\d.*\\.jar.*$"))
            .map(item -> item.path.replaceAll("/+$",""))
            .sorted(PATH_VERSION_COMPARATOR)
            .toList();
    }

    private static <T> Optional<T> getLastOf(List<T> items) {
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(items.size()-1));
    }
    private static void attemptToDeleteForAWhile(Path toDelete) {
        int attemptsLeft = 15;
        while(attemptsLeft --> 0) {
            IOUtils.del(toDelete);
            if(Files.exists(toDelete)) sleep(500); else break;
        }
        if(attemptsLeft == 0) Log.warn("Failed to delete {}", toDelete);
    }
    private static String filenamePartOf(String path) {
        return path.replace("\\\\","/").replaceAll("^.*/([^/]+)$", "$1");
    }
}