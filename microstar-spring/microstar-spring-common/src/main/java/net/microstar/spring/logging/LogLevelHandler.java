package net.microstar.spring.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/**
 * When the log level configuration changes, the loggers should be updated.
 * This class takes care of that. This assumes, and only works if, Logback is used.
 */
@Slf4j
@Service // This is only a service so the class will be loaded and the static constructor runs
public class LogLevelHandler {
    static {
        // Add this listener statically, so it won't add it again when Spring restarts
        DynamicPropertiesManager.addChangeListenerFor("logging.level", Map.class, (map, changedKeys) -> update());
        update();
    }

    public static void update() {
        if(DynamicPropertiesManager.getObjectProperty("logging.level").orElse(null) instanceof Map<?,?> map) {
            //noinspection unchecked
            handleChangedLogLevels((Map<String,Object>)map); // we know logging.level is a map of string to object so the cast is safe
        }
    }
    private static void handleChangedLogLevels(Map<String,Object> settingsIn) {
        final PropsMap settings = PropsMap.fromDeepMap(settingsIn).asFlatMap();

        // Currently only supports logback.classic
        if(log instanceof Logger loggerLog)
            try {
                loggerLog.getLoggerContext().getLoggerList()
                    .forEach(logger -> getLoggerLevel(logger.getName(), settings).ifPresent(level -> setLevel(logger, level)));
            } catch(final ClassCastException cce) {
                log.warn("Unable to update logger levels: unexpected logging framework: {}", cce.getMessage());
            } catch(final Exception e) {
                log.warn("Unable to update logger levels for changed configuration", e);
            }
    }
    private static Optional<Level> getLoggerLevel(String loggerName, PropsMap settings) {
        return settings.getMap().entrySet().stream()
            .filter(entry -> nameMatchesPattern(loggerName, entry.getKey()))
            .map(entry -> entry.getValue().toString())
            .map(levelText -> Level.toLevel(levelText, /*fallback=*/Level.ERROR))
            .findFirst();
    }
    private static boolean nameMatchesPattern(String name, String pattern) {
        return name.equals(pattern)
            || noThrow(() -> Pattern.compile(pattern.replace(".", "\\.").replace("*", ".*")+".*").matcher(name).matches()).orElse(false);
    }
    private static void setLevel(Logger logger, Level level) {
        if(!level.equals(logger.getLevel())) {
            log.debug("Set log level: {}: {}", logger.getName(), level);
            logger.setLevel(level);
        }
    }
}
