package net.microstar.common.io;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

@Slf4j
class JarHashTest {
    @TempDir
    private Path tempDir;

    @Test void testCreatingTempZip() throws IOException {
        final Path zipFile = createTempZip(
            "rootFile.txt",
            "BOOT-INF/",
            "BOOT-INF/classes/",
            "BOOT-INF/classes/aFile1.txt",
            "BOOT-INF/classes/aFile2.txt",
            "BOOT-INF/classes/pak/",
            "BOOT-INF/classes/pak/file.class",
            "BOOT-INF/lib/",
            "BOOT-INF/lib/libA.jar",
            "BOOT-INF/lib/libB.jar"
        );

        final List<String> scannedNames = new ArrayList<>();
        scanZip(zipFile, entry -> scannedNames.add(entry.getName()));
        assertThat(scannedNames, is(List.of(
            "rootFile.txt",
            "BOOT-INF/classes/aFile1.txt",
            "BOOT-INF/classes/aFile2.txt",
            "BOOT-INF/classes/pak/file.class",
            "BOOT-INF/lib/libA.jar",
            "BOOT-INF/lib/libB.jar"
        )));
    }
    @Test void idShouldReflectJarContents() throws IOException {
        assertThat(createZipIdFor(
            "META-INF/something.txt"
        ), is(0L));
        final long crc0 = createZipIdFor(
            "rootFile.txt",
            "BOOT-INF/",
            "BOOT-INF/classes/",
            "BOOT-INF/classes/SomeClassA.class"
        );
        assertThat(crc0, is(0xCDB4A18F20E89833L));
        assertThat(createZipIdFor(
            "rootFile.txt",
            "BOOT-INF/classes/SomeClassA.class"
        ), is(crc0));
        assertThat(createZipIdFor(
            "rootFile.txt",
            "META-INF/something.txt",
            "BOOT-INF/classes/SomeClassA.class"
        ), is(crc0));
        assertThat(createZipIdFor(
            "rootFile.txt",
            "BOOT-INF/",
            "BOOT-INF/classes/",
            "BOOT-INF/classes/SomeClassA.class",
            "BOOT-INF/classes/SomeClassB.class"
        ), is(0xDCB4232120E89833L));
        assertThat(createZipIdFor(
            "rootFile.txt",
            "BOOT-INF/",
            "BOOT-INF/classes/",
            "BOOT-INF/classes/SomeClassA.class",
            "BOOT-INF/classes/SomeClassB.class",
            "BOOT-INF/lib/",
            "BOOT-INF/lib/SomeLibrary.jar"
        ), is(0xDCB42321A087F83FL));
    }
    @Test void snapshotLibrariesShouldBeScanned() throws IOException {
        final Path zipFile1 = createTempZip(
            "rootFile.txt",
            "BOOT-INF/lib/SomeLibrary.jar"
        );
        addToZip(zipFile1, "BOOT-INF/lib/just-built-library-SNAPSHOT.jar", createTempZip(
            "deepRootFile.txt",
            "BOOT-INF/classes/deepA.class"
        ));

        // Same as zipFile1, but SNAPSHOT jar holds an extra file that should be ignored
        final Path zipFile2 = createTempZip(
            "rootFile.txt",
            "BOOT-INF/lib/SomeLibrary.jar"
        );
        addToZip(zipFile2, "BOOT-INF/lib/just-built-library-SNAPSHOT.jar", createTempZip(
            "deepRootFile.txt",
            "BOOT-INF/classes/deepA.class",
            "META-INF/to-be-ignored.txt" // When this file is ignored, the result should be the same
        ));

        final long hash1 = new JarHash(zipFile1).id;
        final long hash2 = new JarHash(zipFile2).id;

        assertThat(hash2, is(hash1));
    }
    @Test void hashingShouldBeFast() {
        final AtomicInteger count = new AtomicInteger(0);
        final AtomicLong time = new AtomicLong(0);
        noThrow(() -> IOUtils.list(Path.of("../../jars/"))).orElse(Collections.emptyList()).stream()
            .filter(path -> path.getFileName().toString().endsWith(".jar"))
            .forEach(jar -> {
                final long t0 = System.currentTimeMillis(); // timed separately in case a specific jar takes very long
                final long hash = new JarHash(jar).id;
                final long t1 = System.currentTimeMillis();
                final long t = t1 - t0;
                final long maxT = 500;
                time.addAndGet(t);
                count.incrementAndGet();
                if(t > maxT/2) log.warn(toHex(hash) + " hash, took " + t + " ms for " + jar.getFileName());
                assertThat(t, is(lessThanOrEqualTo(maxT))); // Tricky, because build machine may be slow (avg on VDI: 6ms)
            });
        final long avgTime = count.get() > 0 ? time.get() / count.get() : 0;
        log.info("JarHashing total time: " + time.get() + " ms for " + count.get() + " jars, avg: " + avgTime + " ms");
    }

    private        long createZipIdFor(String... names) throws IOException {
        final Path zipFile = createTempZip(names);
        final JarHash jarHash = new JarHash(zipFile);
        return jarHash.id;
    }
    private static void scanZip(Path file, Consumer<ZipEntry> visitor) throws IOException {
        try (final ZipFile zipFile = new ZipFile(file.toFile())) {
            final Enumeration<? extends ZipEntry> zipEnum = zipFile.entries();
            while (zipEnum.hasMoreElements()) {
                final ZipEntry zipEntry = zipEnum.nextElement();
                if (!zipEntry.isDirectory()) visitor.accept(zipEntry);
            }
        }
    }
    private        Path createTempZip(String... names) throws IOException { return createTempZipFile(Files.createTempFile(tempDir, "testZip", ".zip"), names); }
    private static Path createTempZipFile(Path file, String... names) throws IOException {
        Files.deleteIfExists(file);
        final FileOutputStream fileStream = new FileOutputStream(file.toFile());
        final ZipOutputStream zipStream = new ZipOutputStream(fileStream);

        for(final String name : names) {
            final ZipEntry zipEntry = new ZipEntry(name);
            zipStream.putNextEntry(zipEntry);
            zipStream.write(("dummyData-" + name).getBytes(StandardCharsets.UTF_8));
            zipStream.closeEntry();
        }
        zipStream.close();
        fileStream.close();
        return file;
    }
    private static void addToZip(Path zipPath, @SuppressWarnings("SameParameterValue") String nameToUse, Path fileToAdd) throws IOException {
        final Map<String,String> env = new HashMap<>();
        env.put("create", "false"); // We don't create the file but modify it

        final URI uri = URI.create("jar:" + zipPath.toAbsolutePath().toUri());
        try (FileSystem zipFS = FileSystems.newFileSystem(uri, env)) {
            final Path pathToAdd = zipFS.getPath(nameToUse);
            Files.copy(fileToAdd, pathToAdd, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static String toHex(long value) { return "0x" + String.format("%016X", value); }
}