package net.microstar.tools.watchdog;

import net.microstar.common.util.StringUtils;

import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/** Simple logger to console and, if a filename is given, to file */
public final class Log {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static Optional<Path> logFile = Optional.empty();
    private static Optional<FileWriter> fileWriter = Optional.empty();
    private static Optional<List<String>> listTarget = Optional.empty();
    private static long lastLogTime = 0L;
    private Log() {}

    static { Runtime.getRuntime().addShutdownHook(new Thread(Log::close)); }

    static void toFile(Path path) {
        close();
        //noinspection ResultOfMethodCallIgnored -- useless return value
        path.getParent().toFile().mkdirs(); // returns false if dir exists or could not be created
        if(!Files.exists(path.getParent())) throw new IllegalStateException("Unable to log to: " + path);
        logFile = Optional.of(path);
        fileWriter = noThrow(() -> new FileWriter(path.toFile(), /*append=*/true));
        if(fileWriter.isEmpty()) error("Unable to write logs to " + path.toAbsolutePath());
    }
    static void storeInMemory() { listTarget = Optional.of(new ArrayList<>()); } // for testing output
    static String getStored() { return String.join("\n", listTarget.orElse(Collections.emptyList())); }
    static void clearStored() { listTarget.ifPresent(List::clear);}

    static void close() {
        fileWriter.ifPresent(fw -> noThrow(fw::close));
        fileWriter = Optional.empty();
        listTarget.ifPresent(List::clear);
    }

    static boolean isLoggedByExternal() {
        final long logFileLastModified = logFile.flatMap(lf -> noThrow(() -> Files.getLastModifiedTime(lf).toMillis())).orElse(0L);
        return logFileLastModified > lastLogTime;
    }

    static void info(String message, Object... args)  { out("INFO",  message, args); }
    static void warn(String message, Object... args)  { out("WARN",  message, args); }
    static void error(String message, Object... args) { out("ERROR", message, args); }

    static void out(String type, String message, Object... args) {
        final int[] index = { 0 };
        final String baseText = String.format("%s] %5s - %s", DATE_TIME_FORMATTER.format(LocalDateTime.now()), type, message);
        final String outText = StringUtils.replaceRegex(baseText, "\\{\\}",
            groups -> noThrow(() -> Objects.toString(args[index[0]++])).map(s -> s.replace("\\","\\\\")).orElse(""));
        dump(outText);
    }

    @SuppressWarnings({"squid:S106", "UseOfSystemOutOrSystemErr"}) // *This* is the logger
    static void dump(Object obj) {
        final String objString = Objects.toString(obj);
        System.out.println(objString);
        listTarget.ifPresent(list -> list.add(Objects.toString(objString)));
        fileWriter.ifPresent(fw -> noThrow(() -> {
            fw.append(objString);
            fw.append("\n");
            fw.flush();
        }));
        lastLogTime = System.currentTimeMillis();
    }
}
