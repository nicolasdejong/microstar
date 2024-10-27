package net.microstar.spring.logging;

import net.microstar.common.util.ByteSize;
import net.microstar.common.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static net.microstar.common.util.ExceptionUtils.noThrow;

public final class LogFilesUtil {
    private LogFilesUtil() {}
    private static final String DATE_TIME_FORMATTER_PATTERN = "yyyyMMddHHmmss";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(DATE_TIME_FORMATTER_PATTERN);

    static String getDateTimeString() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }
    static String getDateTimeString(long epochMillis) {
        return epochMillis <= 0 ? "" :
            LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(DATE_TIME_FORMATTER);
    }
    static String getDateTimeString(Path path) {
        return noThrow(() -> Files.getLastModifiedTime(path))
            .map(FileTime::toMillis)
            .map(Instant::ofEpochSecond)
            .map(t -> LocalDateTime.ofInstant(t, ZoneId.systemDefault()))
            .map(instant -> instant.format(DATE_TIME_FORMATTER))
            .orElse("");
    }
    static long getEpochSecondOfString(String timeString) {
        return timeString.isEmpty() ? 0 :
            LocalDateTime.parse(timeString, DATE_TIME_FORMATTER).atZone(ZoneId.systemDefault()).toInstant().getEpochSecond();
    }
    static int parseInt(String s) { return s.isEmpty() ? 0 : Integer.parseInt(s); }
    static long getTimeOfFile(File file) {
        return StringUtils
            .getRegexGroup(file.getName(), "_(\\d{"+DATE_TIME_FORMATTER_PATTERN.length()+"})_")
            .map(LogFilesUtil::getEpochSecondOfString)
            .orElseGet(file::lastModified);
    }

    static List<Path> pruneOnAge(List<Path> logFiles, Duration maxAge) {
        return logFiles.stream()
            .filter(file -> !(isFileOlderThan(file, maxAge) && delFile(file)))
            .toList();
    }
    static List<Path> pruneOnTotalSize(List<Path> logFiles, ByteSize maxSize) {
        final long size = logFiles.stream().map(Path::toFile).filter(File::isFile).mapToLong(File::length).sum();
        final long[] sizeToRemove = { size - maxSize.getBytesLong() };

        return logFiles.stream()
            .filter(file -> {
                final long fileLength = file.toFile().length();
                if(sizeToRemove[0] > 0 && file.toFile().delete()) {  // NOSONAR -- don't care about reason for deletion failure
                    sizeToRemove[0] -= fileLength;
                    return false;
                }
                return true;
            })
            .toList();
    }

    static boolean wasFileTouchedSinceAgo(Path file, Duration timeAgo) {
        final FileTime fileTime = noThrow(() -> Files.readAttributes(file, BasicFileAttributes.class))
            .map(BasicFileAttributes::lastModifiedTime)
            .orElseGet(() -> FileTime.fromMillis(0));

        final Instant deadline = Instant.now().minus(timeAgo.toMillis(), ChronoUnit.MILLIS);
        return fileTime.toInstant().isAfter(deadline);
    }
    private static boolean isFileOlderThan(Path file, Duration maxAge) {
        final long now = System.currentTimeMillis();
        return now - getTimeOfFile(file.toFile()) > maxAge.toMillis();
    }

    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    private static boolean delFile(Path file) {
        noThrow(() -> Files.deleteIfExists(file));
        return Files.exists(file);
    }

    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    static boolean mkdirsForFileReturnsExists(File file) {
        final File dir = file.getParentFile();

        // mkdirs() returns true if it created any directories. That may not be necessary if dirs already exist
        //noinspection ResultOfMethodCallIgnored -- A result of false doesn't necessarily mean it failed
        dir.mkdirs();
        return dir.exists();
    }
}
