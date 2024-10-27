package net.microstar.common.io;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Helper class to get a hash-like value based on the relevant part of a jar.
  * It does this by xor stacking all CRC values from the files in the jar that
  * are relevant (everything except the META-INF/*). The CRC of each file is
  * stored in the TOC of the zip file so scanning is fast. Exception is the
  * lib/*-SNAPSHOT.jar which will often be part of the build so its CRC will
  * be different every build (because of its META-INF contents which contains
  * a timestamp) so that entry will be unzipped and scanned just like the
  * parent jar so it will result in the same CRC if no code changed.
  */
public class JarHash {
    public final long id;

    public JarHash(File jarFile) { this(jarFile.toPath()); }
    public JarHash(Path jarPath) {
        id = stackCRCs(jarPath);
    }

    // This implementation always uses ZipFile instead of ZipInputStream, even with
    // recursion in zip files because ZipFile is significantly faster to scan (x10)
    // than ZipInputStream. This requires temp files to scan zips inside zips but
    // that is worth the performance benefit.

    private static long stackCRCs(Path jarPath) {
        try (final ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            final AtomicLong   stackedCrc = new AtomicLong(0); // So this is not a combined CRC but an x-or stacked set of CRCs
            final AtomicInteger scanCount = new AtomicInteger(0);
            final Enumeration<? extends ZipEntry> zipEnum = zipFile.entries();

            while (zipEnum.hasMoreElements()) {
                final long crc = crcOf(zipFile, zipEnum.nextElement());
                if (crc != 0) {
                    stackedCrc.set(stackedCrc.get() ^ (crc << (crc < 0x1_0000_0000L && scanCount.get() % 2 == 0 ? 32 : 0)));
                    scanCount.incrementAndGet();
                }
            }
            return stackedCrc.get();
        } catch(final IOException e) {
            return 0;
        }
    }
    private static long stackCRCs(InputStream zipIn) throws IOException {
        final Path tempZipFile = Files.createTempFile("ServiceJarHash-", ".jar");
        try(final OutputStream tempZipOut = Files.newOutputStream(tempZipFile)) {
            zipIn.transferTo(tempZipOut);
            return stackCRCs(tempZipFile);
        } finally {
            Files.deleteIfExists(tempZipFile);
        }
    }
    private static long crcOf(ZipFile file, ZipEntry entry) {
        final String name = entry.getName();

        if (entry.isDirectory()) return 0;
        if (name.startsWith("META-INF/")) return 0;
        if (name.contains("-SNAPSHOT.jar")) {
            try(final InputStream entryIn = file.getInputStream(entry)) {
                return stackCRCs(entryIn);
            } catch(final IOException e) {
                return 0;
            }
        }
        return entry.getCrc();
    }
}
