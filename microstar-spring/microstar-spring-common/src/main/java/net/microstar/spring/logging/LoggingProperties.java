package net.microstar.spring.logging;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.util.ByteSize;
import net.microstar.spring.settings.DynamicProperties;

import java.time.Duration;

@DynamicProperties("logging.microstar")
@Builder @Jacksonized @ToString
public class LoggingProperties {
    @Default public final boolean enabled = true;
    @Default public final String pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight(%5p) --- %cyan(%-40.40logger{39}) : %m%wEx"; // log file line
    @Default public final boolean patternAlsoForConsole = false; // overrides Spring pattern if set
    @Default public final String sanitizePattern = "(?i)(?:secret|password)\\s*[=:]\\s*(\\S+)";
    @Default public final String sanitizedReplacement = "<SANITIZED>";
    @Default public final History history = History.builder().build();
    @Default public final String location = "./log";
    @Default public final ByteSize singleMaxSize = ByteSize.ofMegabytes(10);
    @Default public final Duration sleepBetweenWrites = Duration.ofSeconds(1);
    @Default public final Duration sleepBetweenMaintenance = Duration.ofSeconds(30);

    @Builder @Jacksonized @ToString
    public static class History { // Note that history settings is about all logging history of service-name (not group or version)
        @Default public final boolean enabled = true;
        @Default public final ByteSize maxSize = ByteSize.ofMegabytes(100);
        @Default public final Duration maxAge = Duration.ofDays(30);
    }
}
