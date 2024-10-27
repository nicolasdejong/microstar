package net.microstar.spring.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.spi.LifeCycle;
import net.microstar.common.util.Reflection;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.settings.DynamicPropertyRef;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/** <pre>
 * Log appender that logs to files and manages files for multiple concurrently
 * running instances and what log files remain (via LogFiles).
 * It is kept separate from Spring to not interfere.
 *
 * It also takes care of calls to System.out and System.err to end up in the log file.
 * The CONSOLE appender is hijacked to detect when it is printing to System.out.
 *
 * Configuration keys this appender looks for are:
 * - logging.microstar.enabled                default is true
 * - logging.microstar.pattern                default is %d{yyyy-MM-dd HH:mm:ss.SSS} %5p --- %-40.40logger{39} : %m%wEx
 * - logging.microstar.patternAlsoForConsole  default is false (pattern overrides Spring pattern if true)
 * - logging.microstar.history.enabled        default is true
 * - logging.microstar.history.maxSize        default is 100MB
 * - logging.microstar.history.maxAge         default is 30d
 */
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "squid:S106", "squid:S5164"})
public final class MicroStarLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {
    private static final String APPENDER_NAME = "MicroStarLogAppender";
    private static final PrintStream systemOut = System.out;
    private static final PrintStream systemErr = System.err;
    private static final ThreadLocal<Boolean> loggerIsPrintingToConsole = new ThreadLocal<>();
    @Nullable private static Appender<ILoggingEvent> consoleAppender = null;

    private final DynamicPropertiesRef<LoggingProperties> propsRef = DynamicPropertiesRef.of(LoggingProperties.class);
    private final DynamicPropertyRef<String> rootLevelRef = DynamicPropertyRef.of("logging.level.root").withDefault("INFO");
    @Nullable private PatternLayoutEncoder encoder;
    @Nullable private ApplicationContext appContext;
    private static final List<BiPredicate<ILoggingEvent,String>> skipPredicates = new CopyOnWriteArrayList<>();

    // Spring stops all LogAppender instances when initializing so there needs
    // to be a static init to prevent new log files from being created.

    public static void init() { init(null); }
    public static void init(@Nullable ApplicationContext appContext) {
        final LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        final MicroStarLogAppender logAppender = new MicroStarLogAppender();
        logAppender.appContext = appContext; // appContext will be null when Spring hasn't started yet
        logAppender.setContext(lc);
        logAppender.start();

        // Console logging is done from here
        detachAndStoreConsoleAppender();
        logAppender.updatePattern();

        // Using System.out or System.err will end up at our stream which will be redirected to the logging
        System.setOut(new PrintStream(new SystemOutputStream(systemOut)));
        System.setErr(new PrintStream(new SystemOutputStream(systemErr)));
    }

    public static void reset() {
        System.setOut(systemOut);
        System.setErr(systemErr);
    }

    public static MicroStarLogAppender get() {
        final Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if (logger != null && logger.getAppender(APPENDER_NAME) instanceof MicroStarLogAppender msAppender) return msAppender;
        throw new IllegalStateException(APPENDER_NAME + " appender not found!");
    }

    private static void detachAndStoreConsoleAppender() {
        final Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        if(logger != null) {
            // Appender name is case-sensitive and sometimes uppercase, sometimes lowercase, so try both
            Stream.of("console", "CONSOLE")
                .map(logger::getAppender)
                .filter(Objects::nonNull)
                .forEach(appender -> {
                    consoleAppender = appender;
                    logger.detachAppender(appender);
                });
        }
    }

    private MicroStarLogAppender() {
        setName(APPENDER_NAME);
        propsRef.onChange(this::updatePattern);
        rootLevelRef.onChange(this::update);

        // Remove some messages that can not be disabled otherwise
        skipIf((evt, txt) -> txt.contains("WebSocket close status code does NOT comply"));
    }

    @SuppressWarnings("UnusedReturnValue")
    public MicroStarLogAppender skipIf(BiPredicate<ILoggingEvent,String> skipWhenTestIsTrue) {
        skipPredicates.add(skipWhenTestIsTrue);
        return this;
    }

    @Override public void setContext(Context context) {
        super.setContext(context);
        update();
    }
    @Override protected void append(ILoggingEvent evt) {
        final String textToLog;
        if(encoder == null) {
            textToLog = DateTimeFormatter.ISO_LOCAL_TIME.format(Instant.ofEpochMilli(evt.getTimeStamp())) + " " + evt.getFormattedMessage();
        } else {
            textToLog = new String(encoder.encode(evt), StandardCharsets.UTF_8);
        }
        if(shouldSkip(evt, textToLog)) return;
        logToFile(textToLog);

        // Call consoleAppender directly so we know when it is called so its System.out call won't be redirected
        if(consoleAppender != null) {
            loggerIsPrintingToConsole.set(true);
            consoleAppender.doAppend(evt);
            loggerIsPrintingToConsole.set(false);
        } else {
            systemOut.print(textToLog);
        }
    }

    public static void logToFile(String text) {
        LogFiles.getInstance().log(text);
    }

    private static boolean shouldSkip(ILoggingEvent event, String textToLog) {
        return skipPredicates.stream().anyMatch(predicate -> predicate.test(event, textToLog));
    }

    private void update() {
        final Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logger.setAdditive(false); /* set to true if root should log too */
        logger.addAppender(this);
        final Level rootLevel = Level.toLevel(rootLevelRef.get(), Level.INFO);
        logger.setLevel(rootLevel);
        updatePattern();
    }
    private void updatePattern() {
        final LoggingProperties props = propsRef.get();
        final String pattern = appContext == null
            ? props.pattern.replace("%wEx", "")
            : appContext.getEnvironment().getProperty("logging.pattern", props.pattern);

        if(encoder != null) encoder.stop();
        encoder = new PatternLayoutEncoder();
        encoder.setPattern(pattern.replaceAll("%[a-z]{3,11}\\(([^)]+)\\)", "$1")); // remove colors from pattern
        encoder.setContext(getContext());
        encoder.start();

        // This sets the same pattern to the console. This would override the settings from
        // Spring itself. Therefore, make this optional via a flag.
        if(consoleAppender instanceof OutputStreamAppender<ILoggingEvent> osAppender && props.patternAlsoForConsole) {
            Optional.ofNullable(osAppender.getEncoder()).ifPresent(LifeCycle::stop);
            final PatternLayoutEncoder consoleEncoder = new PatternLayoutEncoder();
            consoleEncoder.setPattern(pattern + "\n");
            consoleEncoder.setContext(osAppender.getContext());
            consoleEncoder.start();
            osAppender.setEncoder(consoleEncoder);
        }
    }


    /** Calls to System.out and System.err will end up here. They should be saved to file before being passed through.
      * Calls from the console appender will end up here as well and should simply pass through (they are already saved to file).
      */
    private static final class SystemOutputStream extends OutputStream {
        private final PrintStream targetOut;
        private SystemOutputStream(PrintStream targetOut) {
            this.targetOut = targetOut;
        }

        @Override public void write(int byteVal) {
            targetOut.write(byteVal);
        }
        @Override public void write(@Nonnull byte[] bytes) throws IOException {
            if(isDirectPrintCall()) {
                logRaw(new String(bytes, StandardCharsets.UTF_8));
            } else {
                targetOut.write(bytes);
            }
        }
        @Override public void write(@Nonnull byte[] bytes, int off, int len) {
            if(isDirectPrintCall()) {
                logRaw(new String(bytes, off, len, StandardCharsets.UTF_8));
            } else {
                targetOut.write(bytes, off, len);
                targetOut.flush();
            }
        }

        private void logRaw(String msg) {
            loggerIsPrintingToConsole.set(false);

            // This is slower but ok because using System.out and System.err will only be used when
            // debugging or in special cases (a library that prints to System.err or spring that prints
            // the banner to System.out for example).
            final Class<?> callerClass = Reflection.getCallerClass(SystemOutputStream.class, MicroStarLogAppender.class);
            if(callerClass.getName().contains(".logback.")) {
                detachAndStoreConsoleAppender();
                return;
            }
            // Perhaps later add a flag that leads to printing the callerClass so
            // it is known who is doing the printing to console.

            targetOut.print(msg);
            logToFile(msg.replaceAll("\n$", ""));
        }
        private static boolean isDirectPrintCall() {
            final Boolean isLoggerPrintingToConsole = loggerIsPrintingToConsole.get();
            return isLoggerPrintingToConsole == null || !isLoggerPrintingToConsole;
        }
    }
}
