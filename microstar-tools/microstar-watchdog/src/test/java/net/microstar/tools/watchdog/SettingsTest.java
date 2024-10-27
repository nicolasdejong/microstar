package net.microstar.tools.watchdog;

import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.FileSystemDataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.matchesPattern;

class SettingsTest {
//    @BeforeEach void setStore() { Watchdog.jarsStore = BlockingDataStore.forStore(DynamicReference.of(new FileSystemDataStore(Path.of(".")))); }
    @BeforeAll static void preventExit() { Watchdog.throwInsteadOfExit = true; }
    @BeforeAll static void storeLogs() { Log.storeInMemory(); }

    private static String run(int expectedResultCode, Runnable toRun) {
        Log.storeInMemory();
        try {
            toRun.run();
        } catch(final ExitException exitEx) {
            assertThat(exitEx.code, is(expectedResultCode));
        }
        final String logs = Log.getStored();
        Log.clearStored();
        return logs;
    }

    @ExtendWith(OutputCaptureExtension.class)
    @Test void testHelp(CapturedOutput output) {
        run(0, () -> Settings.of("help"));
        assertThat(output.getAll(), containsString("Microstar Watchdog"));
        assertThat(output.getAll(), containsString("Dispatcher"));
    }
    @Test void testEncrypt() {
        final String output = run(0, () -> Settings.of("encrypt:secret"));
        assertThat(output, startsWith("{cipher}"));
        assertThat(output, endsWith("=="));
    }
    @Test void testDecrypt() {
        final String enc = run(0, () -> Settings.of("encrypt:secret"));
        assertThat(Settings.of("jarsDir:foo","param:" + enc).params.get(0), is("secret"));
    }
    @Test void testBuilder() {
        final Settings cla = Settings.of(
            "jarsDir:testJarsDir,test2JarsDir", "interval:3min", "port:1234",
            "var:a=aa", "var:b=bb", "var:c=cc",
            "param:pa", "param:pb",
            "jparam:jpa", "jparam:jpb"
        );
        assertThat(cla.jarsDirs, is(List.of("testJarsDir","test2JarsDir")));
        assertThat(cla.interval, is(Duration.ofMinutes(3)));
        assertThat(cla.port, is(1234));
        assertThat(cla.jarVars, is(List.of("a=aa", "b=bb", "c=cc")));
        assertThat(cla.params, is(List.of("pa","pb")));
        assertThat(cla.jParams, is(List.of("jpa","jpb")));
    }
    @Test void testPortFromUrl() {
        final Settings cla = Settings.of(
            "jarsDir:testJarsDir",
            "var:app.config.dispatcher.url=http://localhost:112233"
        );
        assertThat(cla.port, is(112233));
    }
    @Test void testArgumentsFromFile(@TempDir Path tempDir) throws IOException {
        final Path tempFile = tempDir.resolve("args.txt");
        Files.writeString(tempFile, "port:4567 \"jarsDir:abc,some dir with spaces/,def\" var:a=b");

        final Settings cla = Settings.of(
            "interval:12m",
            "@" + tempFile.toAbsolutePath(),
            "param:foo"
        );
        assertThat(cla.interval, is(Duration.ofMinutes(12)));
        assertThat(cla.port, is(4567));
        assertThat(cla.jarsDirs, is(List.of("abc", "some dir with spaces/", "def")));
        assertThat(cla.jarVars, is(List.of("a=b")));
        assertThat(cla.params, is(List.of("foo")));
    }
    @Test void testVersionsCompare(@TempDir Path tempDir) throws IOException {
        Watchdog.jarsStore = BlockingDataStore.forStore(DynamicReference.of(new FileSystemDataStore(tempDir)));
        try {
            for (final String filename : List.of("main-dispatcher-2.1", "main-dispatcher-1.0", "main-dispatcher-1.0.2", "main-dispatcher-1.3")) {
                Files.writeString(tempDir.resolve(filename + ".jar"), "");
            }

            final Settings cla = Settings.of(
                "jarsDir:" + tempDir.toAbsolutePath()
            );

            assertThat(cla.getDispatcherJarPath(), is(Optional.of("main-dispatcher-2.1.jar")));
        } finally {
            Watchdog.jarsStore.getCloseRunner().run();
        }
    }
    @Test void testRunCommand(@TempDir Path tempDir) throws IOException {
        Watchdog.jarsStore = BlockingDataStore.forStore(DynamicReference.of(new FileSystemDataStore(tempDir)));
        try {
            Files.writeString(tempDir.resolve("main-dispatcher-1.1.jar"), "");
            Files.writeString(tempDir.resolve("main-dispatcher-1.0.jar"), "");

            final Settings cla = Settings.of(
                "jarsDir:" + tempDir.toAbsolutePath(),
                "var:varA=valA", "var:varB=valB",
                "param:paramA", "param:paramB",
                "jparam:jpa", "jparam:jpb"
            );
            final String cmd = String.join(" ", cla.getJavaCommand()).replace("\\", "/"); // Windows has backslashes

            assertThat(cmd, matchesPattern("^.*? -jar .*/microstar\\d+/main-dispatcher-1.1.jar.*$"));
            assertThat(cmd, matchesPattern("^.*?java(.exe)? jpa jpb.*$"));
            assertThat(cmd, matchesPattern("^.*?-DvarA=valA -DvarB=valB -jar \\S+ paramA paramB$"));
        } finally {
            Watchdog.jarsStore.getCloseRunner().run();
        }
    }
}