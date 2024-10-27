package net.microstar.common.io;

import net.microstar.common.util.Reflection;
import net.microstar.common.util.ThreadUtils;
import net.microstar.common.util.Utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.time.ZoneOffset.UTC;
import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ExceptionUtils.rethrow;
import static net.microstar.common.util.StringUtils.replaceMatches;
import static net.microstar.common.util.Utils.sleep;

public final class IOUtils {
    private static final int MAX_8BIT_VALUE = 0xFF;
    private static final int HEX_BIT_WIDTH = 16;
    private static final Pattern PATTERN_ILLEGAL_FILENAME_CHARS = Pattern.compile("[\\p{C}<>:\"/\\\\|?*%$]"); // % and $ are used as escape chars
    private IOUtils() {/*singleton*/}

    /** Unchecked alias for File.createDirectories(dir), on failure throws UncheckedIOException */
    public static Path makeSureDirectoryExists(Path dir) {
        try {
            return Files.createDirectories(dir);
        } catch (final IOException failedToCreate) {
            throw new UncheckedIOException("Unable to access " + dir.toAbsolutePath(), failedToCreate);
        }
    }
    /** Unchecked alias for File.createDirectories() and Files.writeString, on failure throws UncheckedIOException */
    public static void writeString(Path file, String contents) {
        makeSureDirectoryExists(file.getParent());
        try {
            Files.writeString(file, contents);
        } catch (IOException failedToWrite) {
            throw new UncheckedIOException("Failed to write to " + file.toAbsolutePath(), failedToWrite);
        }
    }
    /** Unchecked alias for File.createDirectories() and Files.write, on failure throws UncheckedIOException */
    public static void write(Path file, byte[] contents) {
        makeSureDirectoryExists(file.getParent());
        try {
            Files.write(file, contents);
        } catch (IOException failedToWrite) {
            throw new UncheckedIOException("Failed to write to " + file.toAbsolutePath(), failedToWrite);
        }
    }
    public static Optional<LocalDateTime> getLastModifiedUtcOf(Path path) {
        try {
            return Optional.of(LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.of(UTC.getId())));
        } catch (final IOException ignored) {
            return Optional.empty();
        }
    }
    public static LocalDateTime getUtcLastModifiedOrNowOf(Path path) {
        return getLastModifiedUtcOf(path).orElseGet(Utils::nowUtc);
    }
    public static void copy(Path from, Path to) {
        if(!Files.exists(from)) throw new IllegalArgumentException("File or dir to copy does not exist: " + from);
        try {
            if(from.toFile().isDirectory()) {
                if(to.toFile().isFile()) Files.delete(to); // existing is overwritten
                final Path toDir = to;
                makeSureDirectoryExists(toDir);
                list(from)
                    .forEach(fromFile -> copy(fromFile, toDir.resolve(fromFile.getFileName())));
            } else {
                Files.copy(from, to, REPLACE_EXISTING, COPY_ATTRIBUTES);

                // Next verifies if the copy succeeded. This may happen in case of read/write
                // collision. Strange enough in that case no exception is thrown by Files.copy()
                // This is a shallow check because full data check is too expensive.
                if(from.toFile().length() != to.toFile().length()) {
                    // Because this is probably related to some race condition, sleep a bit before trying again.
                    sleep(500);

                    Files.copy(from, to, REPLACE_EXISTING, COPY_ATTRIBUTES);
                    if(from.toFile().length() != to.toFile().length()) throw new IOException("Destination file differs from source file");
                }
            }
        } catch (final IOException cause) {
            throw new IllegalStateException("Failed to copy " + from + " to " + to + ": " + cause.getMessage(), cause);
        }
    }
    public static void move(Path from, Path to) {
        if(!Files.exists(from)) throw new IllegalArgumentException("File to move does not exist: " + from);
        try {
            if(Files.isDirectory(to)) {
                if(Files.isDirectory(from)) {
                    list(from).forEach(fromChild -> move(fromChild, to.resolve(fromChild.toFile().getName())));
                    del(from);
                    return;
                } else {
                    delTree(to);
                }
            } // no else
            makeSureDirectoryExists(to.getParent());
            if(!Files.isDirectory(to.getParent())) throw new IOException("Unable to create directory: " + to.getParent());
            Files.move(from, to, REPLACE_EXISTING);
        } catch (final IOException cause) {
            throw new IllegalStateException("Failed to move " + from + " to " + to + ": " + cause.getMessage(), cause);
        }
    }
    public static void del(Path path, String... deeperPath) {
        final Path fileToDelete = deeperPath.length == 0 ? path : path.resolve(String.join("/", deeperPath));
        try {
            Files.deleteIfExists(fileToDelete);
        } catch (final IOException cause) {
            throw new IllegalStateException("Unable to delete: " + path, cause);
        }
    }
    public static void delTree(Path path) {
        if (Files.isDirectory(path)) list(path).forEach(IOUtils::delTree);
        del(path);
    }

    private static final int COPY_STREAM_BUFFER_SIZE = 8192;

    /** Copy of InputStream.transferTo() */
    public static long copy(InputStream in, OutputStream out) {
        return copy(in, out, done -> {});
    }

    /** Copy of InputStream.transferTo() with added once per second throttled progress */
    public static long copy(InputStream in, OutputStream out, LongConsumer progressHandler) {
        return copy(in, out, progressHandler, Duration.ofSeconds(1));
    }

    /** Copy of InputStream.transferTo() with added throttled progress */
    public static long copy(InputStream in, OutputStream out, LongConsumer progressHandler, Duration progressThrottle) {
        final Consumer<Long> progress = ThreadUtils.throttleLC(progressHandler, progressThrottle); // NOSONAR -- LongConsumer is not possible here
        long transferred = 0;
        byte[] buffer = new byte[COPY_STREAM_BUFFER_SIZE];
        int read;
        try {
            while ((read = in.read(buffer, 0, COPY_STREAM_BUFFER_SIZE)) >= 0) {
                out.write(buffer, 0, read);
                transferred += read;
                progress.accept(transferred);
            }
        } catch (final IOException cause) {
            throw new UncheckedIOException("Failed to copy from stream to stream: " + cause.getMessage(), cause);
        }
        progressHandler.accept(transferred);
        return transferred;
    }

    public static void touch(File file) { touch(file.toPath()); }
    public static void touch(Path path) { touch(path, Instant.now()); }
    public static void touch(Path path, LocalDateTime dt) { touch(path, dt.toInstant(ZoneId.systemDefault().getRules().getOffset(dt))); }
    public static void touch(Path path, Instant time) {
        final boolean dtIsNow = Math.abs(time.getEpochSecond() - Instant.now().getEpochSecond()) <= 2;
        if(!Files.exists(path)) {
            try {
                final Path parentPath = path.toAbsolutePath().getParent();
                if(parentPath != null) Files.createDirectories(parentPath);
                Files.createFile(path); // "lastModifiedTime" is not allowed as attribute, so we have to touch the filesystem again for not-now times
            } catch (final FileAlreadyExistsException ignore) {
                // This is not a problem -- someone else created the file between the exists() call and createFile() call
                // and the result is what we want: the file exists
            } catch (final IOException failedToCreate) {
                throw new IllegalStateException(failedToCreate);
            }
            if(dtIsNow) return; // otherwise setting lastModified below leads to a double touch while time is current anyway
        }
        noCheckedThrow(() -> Files.setLastModifiedTime(path, FileTime.from(time)));
    }
    public static void touch(Path path, String... deeperPath) {
        touch(deeperPath.length == 0 ? path : path.resolve(String.join("/", deeperPath)));
    }

    /** Like Files.list() but is not lazily populated, does not require a close and only throws unchecked exceptions */
    public static List<Path> list(Path toList) {
        try(final Stream<Path> files = Files.list(toList)) {
            return files.toList();
        } catch (final NoSuchFileException cause) {
            return Collections.emptyList();
        } catch (final IOException cause) {
            throw new IllegalArgumentException("Unable to list " + toList, cause);
        }
    }
    public static List<Path> listDeep(Path root) {
        final List<Path> paths = new ArrayList<>();
        listDeep(root, paths);
        paths.remove(root);
        return paths;
    }
    private static void listDeep(Path path, List<Path> paths) {
        paths.add(path);
        if(Files.isDirectory(path)) list(path).forEach(child -> listDeep(child, paths));
    }
    public static List<Path> relativize(Path root, Collection<Path> paths) {
        return paths.stream().map(root::relativize).toList();
    }
    public static long sizeOf(Path p) {
        return Files.isDirectory(p)
            ? list(p).stream().mapToLong(IOUtils::sizeOf).sum()
            : noThrow(() -> Files.size(p)).orElse(0L);
    }
    public static long[] sizeAndCountOf(Path p) {
        return Files.isDirectory(p)
            ? list(p).stream().map(IOUtils::sizeAndCountOf).reduce(new long[] { 0, 0 }, (a, r) -> { a[0]+=r[0]; a[1]+=r[1]; return a; })
            : noThrow(() -> new long[] { Files.size(p), 1L }).orElse(new long[] { 0, 0 });
    }
    public static boolean isProbablyTempFile(Path p) {
        return isProbablyTempFile(p.toAbsolutePath().toString());
    }
    public static boolean isProbablyTempFile(String name) {
        class Local {
            private Local() {} // keep Sonar happy
            static final Pattern TEMP_PATTERN = Pattern.compile("^.*?(\\.te?mp|/~|~/|(~$)).*$");
        }
        return Local.TEMP_PATTERN.matcher(name.replace("\\","/")).matches();
    }

    /** Alternative for Path.of() that doesn't crash on Windows when a "/D:/path" is provided.
      * On Windows, when a URL points to a local resource, the URL.getPath() or URL.getFile()
      * will return a string like "/D:\path\to\resource" which, when given to Path.of() will
      * lead to an InvalidPathException (':' is not allowed in a path that starts with slash).
      * This method removes the slash before calling Path.of() in that case.
      */
    public static Path pathOf(String firstIn, String... rest) {
        final String first = firstIn.replaceFirst("^file:", "");
        return Path.of(first.startsWith("/") && first.length() > 2 && first.charAt(2) == ':' ? first.substring(1) : first, rest);
    }

    /** Create a path to a non-existent temporary file */
    public static Path createAndDeleteTempFile() { return createAndDeleteTempFile("", ".tmp"); }
    public static Path createAndDeleteTempFile(String prefix, String suffix) {
        final Path tempFile = rethrow(() -> Files.createTempFile(prefix, suffix), ex -> new IllegalStateException("Unable to create temp file", ex));
        rethrow(() -> Files.deleteIfExists(tempFile), ex -> new IllegalStateException("Unable to delete just created temp file: " + tempFile, ex));
        return tempFile;
    }
    public static Path createTempDir() { return createTempDir(".tmp"); }
    public static Path createTempDir(String prefix) {
        return rethrow(() -> Files.createTempDirectory(prefix), ex -> new IllegalStateException("Unable to create temp directory", ex));
    }

    public static String concatPath(Object... elements) {
        final class Local {
            Local(){}
            static final Pattern TRIM_SLASHES_PATTERN = Pattern.compile("(^/+)|(/+$)");
            static String trimSlashes(String s) { return TRIM_SLASHES_PATTERN.matcher(s).replaceAll(""); }
        }
        final List<String> names = Arrays.stream(elements)
            .filter(Objects::nonNull)
            .map(Object::toString)
            .toList();
        final String startSlash = names.stream()
            .findFirst()
            .filter(s -> s.startsWith("/") && s.length() > 1)
            .map(s -> "/")
            .orElse("");
        final boolean endsWithSlash = names.stream().reduce((a,b) -> b).orElse("").endsWith("/");
        final String joined = startSlash + names.stream().map(Local::trimSlashes).collect(Collectors.joining("/"));
        return joined + (endsWithSlash && !joined.endsWith("/") ? "/" : "");
    }

    public static String createValidReversibleFilename(String beforeName) {
        return replaceMatches(beforeName, PATTERN_ILLEGAL_FILENAME_CHARS, match -> {
            final int codePoint = Character.codePointAt(match.group(), 0);
            return codePoint >= 0 && codePoint <= MAX_8BIT_VALUE
                ? "%"   + String.format("%1$02X", codePoint)
                : "\\$" + String.format("%1$04X", codePoint);
        });
    }
    public static String reverseReversibleFilename(String reversibleFilename) {
        return replaceMatches(reversibleFilename, Pattern.compile("(%.{2})|(\\$.{4})"), match ->
            new String(new int[]{Integer.parseInt(match.group().substring(1), HEX_BIT_WIDTH)}, 0, 1)
                .replace("$", "\\$") // prevent matcher group references
        );
    }

    public static boolean hasResource(String... namesToTry) {
        return hasResource(Reflection.getCallerClass(IOUtils.class), namesToTry);
    }
    public static boolean hasResource(Class<?> base, String... namesToTry) {
        return Arrays.stream(namesToTry)
            .filter(name -> !name.isEmpty() && !name.endsWith("/"))
            .map(name -> Optional.of(     name).map(base::getResource)
               .or(() -> Optional.of("/" + name).map(base::getResource)))
            .flatMap(Optional::stream)
            .findFirst()
            .isPresent();
    }

    public static Optional<byte[]> getResource(String... namesToTry) {
        return getResource(Reflection.getCallerClass(IOUtils.class), namesToTry);
    }
    public static Optional<byte[]> getResource(Class<?> base, String... namesToTry) {
        return Arrays.stream(namesToTry)
            .map(name -> Optional.of(      name).map(base::getResourceAsStream)
               .or(() -> Optional.of("/" + name).map(base::getResourceAsStream))
            )
            .flatMap(Optional::stream)
            .map(dataStream -> noThrow(dataStream::readAllBytes))
            .flatMap(Optional::stream)
            .findFirst()
            ;
    }
    public static Optional<String> getResourceAsString(String... namesToTry) {
        return getResourceAsString(Reflection.getCallerClass(IOUtils.class), namesToTry);
    }
    public static Optional<String> getResourceAsString(Class<?> base, String... namesToTry) {
        return getResource(base, namesToTry).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }
}
