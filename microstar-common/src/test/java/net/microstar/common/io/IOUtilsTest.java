package net.microstar.common.io;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static net.microstar.common.io.IOUtils.concatPath;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IOUtilsTest {
    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    @TempDir
    private Path testDir;

    @Test void testReversibleFilenames() {
        final String filename = "abc\ndef:ghi\uFF00jkl%mno$";
        final String validName = IOUtils.createValidReversibleFilename(filename);
        assertThat(validName, is("abc%0Adef%3Aghi$FF00jkl%25mno%24"));
        assertThat(IOUtils.reverseReversibleFilename(validName), is(filename));
    }

    @Test void testCopy() {
        createTestData(testDir, List.of(
            "/from/file1.txt",
            "/from/dir/file2.txt",
            "/from/dir/file2b.txt",         // overwrites existing file
            "/from/dir/deeper/file3.txt:3333",
            "/from/dir/deeper2/file3b.txt", // adds to existing dir
            "/from/empty/",
            "/to/dir/file2b.txt:toBeOverwritten", // overwritten
            "/to/dir/deeper2/file3.txt",
            "/to/empty2/",                  // should not be touched
            "/to/someExistingFile.txt"      // should not be touched
        ));
        IOUtils.copy(testDir.resolve("from"), testDir.resolve("to"));
        assertTestData(testDir, List.of(
            "/from/file1.txt",
            "/from/dir/file2.txt",
            "/from/dir/file2b.txt",
            "/from/dir/deeper/file3.txt:3333",
            "/from/dir/deeper2/file3b.txt",
            "/from/empty/",
            "/to/file1.txt",
            "/to/dir/file2.txt",
            "/to/dir/file2b.txt",
            "/to/dir/deeper/file3.txt:3333",
            "/to/dir/deeper2/file3.txt",
            "/to/dir/deeper2/file3b.txt",
            "/to/empty/",
            "/to/empty2/",
            "/to/someExistingFile.txt"
        ), Assertions::fail);
    }
    @Test void testMove() {
        // The result of a move is basically the same as a copy, except the source files will no longer exist afterwards
        createTestData(testDir, List.of(
            "/from/file1.txt",
            "/from/dir/file2.txt",
            "/from/dir/file2b.txt",         // overwrites existing file
            "/from/dir/deeper/file3.txt:3333",
            "/from/dir/deeper2/file3b.txt", // adds to existing dir
            "/from/empty/",
            "/to/dir/file2b.txt:toBeOverwritten", // overwritten
            "/to/dir/deeper2/file3.txt",
            "/to/empty2/",                  // should not be touched
            "/to/someExistingFile.txt"      // should not be touched
        ));
        IOUtils.move(testDir.resolve("from"), testDir.resolve("to"));
        assertTestData(testDir, List.of(
            "/to/file1.txt",
            "/to/dir/file2.txt",
            "/to/dir/file2b.txt",
            "/to/dir/deeper/file3.txt:3333",
            "/to/dir/deeper2/file3.txt",
            "/to/dir/deeper2/file3b.txt",
            "/to/empty/",
            "/to/empty2/",
            "/to/someExistingFile.txt"
        ), Assertions::fail);
    }
    @Test void testDelTree() {
        createTestData(testDir, List.of(
            "/dir/file1.txt",
            "/dir/file2.txt",
            "/dir/deeper/file3.txt",
            "/dir/deeper/deeper2/file4.txt",
            "/dir/deeper/deeper2/file5.txt",
            "/dir/deeper/deeper2/deeper3/file6.txt"
        ));
        IOUtils.delTree(testDir.resolve("dir"));
        assertTestData(testDir, Collections.emptyList(), Assertions::fail);
    }

    @Test void pathOfShouldNotCrash() {
        if(IS_WINDOWS) { // Path.of with a colon only crashes on Windows
            assertThrows(InvalidPathException.class, () -> Path.of("/C:/some/path"));
            assertThat(IOUtils.pathOf("/C:/some/path", "and", "more"), is(Path.of("C:/some/path", "and", "more")));
            assertThat(IOUtils.pathOf("/C:\\some\\path", "and", "more"), is(Path.of("C:\\some\\path", "and", "more")));
            assertThat(IOUtils.pathOf("/"), is(Path.of("/")));
            assertThat(IOUtils.pathOf("/C"), is(Path.of("/C")));
            assertThat(IOUtils.pathOf("/C:"), is(Path.of("C:")));
            assertThat(IOUtils.pathOf("/C:/a"), is(Path.of("C:/a")));
            assertThat(IOUtils.pathOf("file:/C:/some/path"), is(Path.of("C:/some/path")));
        }
    }

    @Test void testConcatPath() {
        assertThat(concatPath(), is(""));
        assertThat(concatPath("/"), is("/"));
        assertThat(concatPath("//"), is("/"));
        assertThat(concatPath("a"), is("a"));
        assertThat(concatPath("/a"), is("/a"));
        assertThat(concatPath("/a/"), is("/a/"));
        assertThat(concatPath("//a//"), is("/a/"));
        assertThat(concatPath("a","b"), is("a/b"));
        assertThat(concatPath("a/","/b"), is("a/b"));
        assertThat(concatPath("/a/","/b/"), is("/a/b/"));
        assertThat(concatPath("a","b","c"), is("a/b/c"));
        assertThat(concatPath("/a/","/b/","/c/"), is("/a/b/c/"));
        assertThat(concatPath("/a/","/b/","/c//"), is("/a/b/c/"));
        assertThat(concatPath("/a/","/b/","/c/",""), is("/a/b/c/"));
        assertThat(concatPath("a","","b"), is("a//b"));
        assertThat(concatPath("","a","b"), is("/a/b"));
        assertThat(concatPath("https://some.host", "some/path"), is("https://some.host/some/path"));
        assertThat(concatPath("/", "a/"), is("/a/"));
        assertThat(concatPath("/", "/a/"), is("/a/"));
        assertThat(concatPath("", "/a/"), is("/a/"));
    }

    @Test void testIsProbablyTempFile() {
        assertThat(IOUtils.isProbablyTempFile(Path.of("/just/normal/path/file.txt")), is(false));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/windows/path/PROGRA~1/path/file.txt")), is(false));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/just/normal/windows/path/ABCDEF~1.txt")), is(false));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/just/normal/path/file.tmp")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/just/normal/path/file.temp")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/just/normal/path/file.txt~")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/just/normal/path/~file.txt")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/some/windows/path.tmp/file.txt")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("\\some\\windows\\path.tmp\\file.txt")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("\\some\\window~1\\path\\file.txt")), is(false));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/some/other/path.tmp//file.txt")), is(true));
        assertThat(IOUtils.isProbablyTempFile(Path.of("/some/other/path~/file.txt")), is(true));
    }
    @Test void testCreateAndDeleteTempFile() {
        final Path tempFile = IOUtils.createAndDeleteTempFile();
        assertThat(tempFile.toString(), endsWith(".tmp"));
        assertThat(Files.exists(tempFile), is(false));
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void createTestData(Path root, List<String> targets) {
        targets.stream()
            .map(target -> (target + ":" + target.replaceAll("^.*?([^/\\\\]+)$", "$1")).split(":"))
            .forEach(target -> {
                final Path path = root.resolve(target[0].replaceAll("^[/\\\\]+", ""));
                if(target[0].endsWith("/")) {
                    path.toFile().mkdirs();
                } else {
                    final String contents = target[1];
                    path.getParent().toFile().mkdirs();
                    try {
                        Files.writeString(path, contents);
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
    }
    private static void assertTestData(Path root, List<String> targets, Consumer<String> fail) {
        final List<Path> paths = IOUtils.listDeep(root);
        paths.remove(root);

        targets.stream()
            .map(target -> (target + ":" + target.replaceAll("^.*?([^/]+)$", "$1")).split(":"))
            .forEach(target -> {
                final Path path = root.resolve(target[0].replaceAll("^/+", ""));
                if(!Files.exists(path)) fail.accept("Expected but not found: " + root.relativize(path)); // NOSONAR -- Files.exists() never returns null
                if(!target[0].endsWith("/")) {
                    final String contents = target[1];
                    try {
                        if(!contents.equals(Files.readString(path))) fail.accept("File has wrong content: " + root.relativize(path));
                    } catch (final IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                for(Path p = path; !p.equals(root); p = p.getParent()) paths.remove(p);
            });

        if(!paths.isEmpty()) {
            fail.accept("Unexpected paths: " + IOUtils.relativize(root, paths));
        }
    }
}