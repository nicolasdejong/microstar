package net.microstar.common.datastore;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.FileTreeChangeDetector;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.StringUtils;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;

/** Data will be stored as files on the file system at given root position */
@Slf4j
public class FileSystemDataStore extends AbstractDataStore {
    final Path root;
    final AtomicReference<Instant> lastKnownTime = new AtomicReference<>(Instant.EPOCH);
    final FileTreeChangeDetector fsDetector;

    @SuppressWarnings("this-escape")
    public FileSystemDataStore(Path... roots) {
        this.root = firstExists(roots).orElseGet(() -> {
            if(roots.length == 0) throw new IllegalArgumentException("No roots provided for FileSystemDataStore");
            noCheckedThrow(() -> Files.createDirectories(roots[0]));
            return roots[0];
        });
        log.info("Opening FileSystemDataStore at {}", root.toFile().getAbsolutePath());
        if(!Files.isDirectory(root)) {
            final String error = "Provided root is not a directory: " + root.toFile().getAbsolutePath();
            log.error(error);
            throw new IllegalArgumentException(error);
        }
        fsDetector = new FileTreeChangeDetector(this.root, (path, changeType) -> {
            final Instant fileInstant = toInstant(path);
            if(Files.isRegularFile(path) && fileInstant.isAfter(lastKnownTime.get())) {
                lastKnownTime.set(fileInstant);
                changed(relativePath(path));
            }
        }).watch();
    }

    @Override
    public Runnable getCloseRunner() {
        return new Runnable() {
            final FileTreeChangeDetector detector = fsDetector;
            public void run() {
                detector.close();
                closed();
            }
        };
    }

    @Override
    public String toString() { return "[FileSystemDataStore at " + root.toAbsolutePath() + "]"; }

    @Override
    public CompletableFuture<List<Item>> list(String pathIn, boolean recursive) {
        return supplyAsync(() -> {
            final Path target = resolve(pathIn);
            return (recursive ? IOUtils.listDeep(target) : IOUtils.list(target))
                .stream()
                .filter(path -> !recursive || !Files.isDirectory(path))
                .map(path -> {
                    final long[] sizeAndCount = IOUtils.sizeAndCountOf(path);
                    return new Item(
                        restoreIllegalPathCharacters(target.relativize(path).toString()).replace("\\","/") + (Files.isDirectory(path) ? "/" : ""),
                        noCheckedThrow(() -> Files.getLastModifiedTime(path).toInstant()),
                        (int)sizeAndCount[1], sizeAndCount[0]
                    );
                })
                .sorted(ITEM_COMPARATOR)
                .peek(item -> { if(item.time.isAfter(lastKnownTime.get())) lastKnownTime.set(item.time); })
                .toList();
        });
    }

    @Override
    public CompletableFuture<Optional<Instant>> getLastModified(String path) {
        return supplyAsync(() -> noThrow(() -> Files.getLastModifiedTime(resolve(path)).toInstant()));
    }

    @Override
    public CompletableFuture<Boolean> exists(String path) {
        return supplyAsync(() -> noThrow(() -> Files.exists(resolve(path))).orElse(false));
    }

    @Override
    public CompletableFuture<Boolean> remove(String path) {
        return supplyAsync(() -> {
            final Path toRemove = resolve(path);
            final List<Path> deletedPaths = toRemove.toFile().isDirectory()
                ? IOUtils.listDeep(toRemove).stream().filter(p->p.toFile().isFile()).toList()
                : List.of(toRemove);
            IOUtils.delTree(toRemove);
            deletedPaths.forEach(del -> changed(relativePath(del)));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> move(String fromPath, String toPath) {
        return supplyAsync(() -> {
            final Path pathFrom = resolve(fromPath);
            final Path pathTo = resolve(toPath);
            final String sourcePath = relativePath(pathFrom);
            final String targetPath = relativePath(pathTo);
            final List<String> sourcePaths = pathFrom.toFile().isDirectory()
                ? IOUtils.listDeep(pathFrom).stream().filter(p->p.toFile().isFile()).map(this::relativePath).toList()
                : List.of(sourcePath);
            final List<String> targetPaths = pathFrom.toFile().isDirectory()
                ? sourcePaths.stream().map(p->IOUtils.concatPath(targetPath, p.substring(sourcePath.length()))).toList()
                : List.of(targetPath);
            lastKnownTimeNow();
            IOUtils.move(pathFrom, pathTo);
            changed(sourcePaths, targetPaths);
            return true;
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> read(String path) {
        return supplyAsync(() -> noThrow(() -> Files.readAllBytes(resolve(path))));
    }

    @Override
    public CompletableFuture<Optional<InputStream>> readStream(String path) {
        return completedFuture(noThrow(() -> new FileInputStream(resolve(path).toFile())));
    }

    @Override
    public CompletableFuture<Boolean> write(String path, byte[] data, Instant time) {
        return supplyAsync(() -> {
            final Path targetPath = resolve(path);
            IOUtils.makeSureDirectoryExists(targetPath.getParent());
            final boolean isSuccess = noThrow(() -> Files.write(targetPath, data)).isPresent();
            if(isSuccess) {
                IOUtils.touch(targetPath, time);
                changed(relativePath(targetPath));
                lastKnownTimeNow();
            }
            return isSuccess;
        });
    }

    @Override
    public CompletableFuture<Boolean> write(String path, InputStream source, Instant time, LongConsumer progress) {
        return supplyAsync(() -> {
            final Path targetPath = resolve(path);
            IOUtils.makeSureDirectoryExists(targetPath.getParent());
            try(final InputStream in = source; // copied so it will be auto-closed
                final OutputStream target = new FileOutputStream(targetPath.toFile())) {
                progress.accept(IOUtils.copy(in, target, progress));
            } catch(final IOException e) {
                log.error("Unable to write to path '{}': {}", path, e.getMessage());
                return false;
            }
            lastKnownTimeNow();
            IOUtils.touch(targetPath, time);
            changed(relativePath(targetPath));
            return true;
        });
    }

    @Override
    public CompletableFuture<Boolean> touch(String path, Instant time) {
        return supplyAsync(() -> {
            final Path toTouch = resolve(path);
            IOUtils.touch(toTouch, time);
            changed(relativePath(toTouch));
            return true;
        });
    }

    @Override
    public String normalizePath(Object... parts) {
        return replaceIllegalPathCharacters(super.normalizePath(parts));
    }

    private static final boolean IS_WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private static Optional<Path> firstExists(Path... paths) {
        return Stream.of(paths).filter(Files::exists).findFirst();
    }
    private Path resolve(String path) {
        final Path resolved = root.resolve(normalizePath(path).replaceAll("^/+",""));
        if(!resolved.toAbsolutePath().startsWith(root.toAbsolutePath())) throw new IllegalArgumentException("Given path tries to escape root: " + path);
        return resolved;
    }
    static String replaceIllegalPathCharacters(String s) {
        // In Windows, the colon in the second or third character ("/C:/..." or "C:/...") should remain!
        final String skipPart = !IS_WINDOWS ? "" :
                                s.length() >= 2 && s.charAt(1) == ':' ? s.substring(0,2) :
                                s.length() >= 3 && s.charAt(2) == ':' ? s.substring(0,3) : "";
        final String partToFix = s.substring(skipPart.length());
        return skipPart + StringUtils.replaceGroups(partToFix, "([:<>|@*?'\"%])", c -> "%" + (int)c.charAt(0) + ";");
    }
    static String restoreIllegalPathCharacters(String s) {
        return StringUtils.replaceMatches(s, "%([^;]+);", match -> Character.toString((char)Integer.parseInt(match.group(1))));
    }
    private String relativePath(Path path) {
        final String result;
        final Path rootAbs = root.toAbsolutePath();
        if(IS_WINDOWS) {
            final Path resolved = rootAbs.resolve(normalizePath(path).replaceAll("^/+", ""));
            result = "/" + rootAbs.relativize(resolved).toString().replace("\\", "/");
        } else {
            result = normalizePath(rootAbs.relativize(path).toString());
        }
        return result;
    }
    private void lastKnownTimeNow() { lastKnownTime.set(Instant.now()); }
    private Instant toInstant(Path p) { return Instant.ofEpochSecond(p.toFile().lastModified()); }
}
