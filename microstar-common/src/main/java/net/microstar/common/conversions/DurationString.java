package net.microstar.common.conversions;

import net.microstar.common.util.StringUtils;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MICROS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static net.microstar.common.util.ExceptionUtils.noThrow;

/** Conversion between Duration and String */
@SuppressWarnings("squid:S3973"/*conditions indents*/)
public final class DurationString {
    private DurationString() {}
    private static final String TO_DURATION_ERROR = "Text cannot be parsed to a Duration because ";

    public static Duration toDuration(String s) {
        if(s.trim().isEmpty()) throw new IllegalArgumentException(TO_DURATION_ERROR + "empty input");
        if(s.startsWith("PT")) return Duration.parse(s);

        final List<String> parts = StringUtils.getRegexGroups(s, "[.\\d]+\\s*\\D+");
        if(parts.isEmpty()) throw new IllegalArgumentException(TO_DURATION_ERROR + " invalid input");

        final long nanos = parts
            .stream()
            .map(part -> numberWithFormatToNanos(part, s))
            .reduce(0L, Long::sum);
        return Duration.ofNanos(nanos);
    }
    private static long numberWithFormatToNanos(String part, String fullText) {
        final String[] subParts = Optional
            .of(part.split("(?<=[.\\d])\\s*(?=[^.\\d])", 2))
            .filter(parts -> parts.length == 2)
            .orElseThrow(() -> new IllegalArgumentException(TO_DURATION_ERROR + "bad parts (" + part + "): " + fullText));
        final String numberText = subParts[0].trim();
        final String format     = subParts[1].trim();
        final double number     = noThrow(() -> Double.parseDouble(numberText))
            .orElseThrow(() -> new IllegalArgumentException(TO_DURATION_ERROR + "bad number (" + numberText + "): " + fullText));
        final long multiplier  = switch(format.toLowerCase(Locale.ROOT)) {
            case "n", "ns", "nano", "nanos"              ->                          1L;
            case "ms", "milli", "millis"                 ->                  1_000_000L;
            case "s", "sec", "second", "secs", "seconds" ->              1_000_000_000L;
            case "m", "min", "minute", "mins", "minutes" ->             60_000_000_000L;
            case "h", "hr", "hrs", "hour", "hours"       ->          3_600_000_000_000L;
            case "d", "day", "days"                      ->     24 * 3_600_000_000_000L;
            case "w", "week", "weeks"                    -> 7 * 24 * 3_600_000_000_000L;
            default -> -1;
        };
        if(multiplier < 0) throw new IllegalArgumentException(TO_DURATION_ERROR + "unsupported format (" + format + "): " + fullText);

        return (number != (long)number) ? (long)(number * multiplier) : ((long)number * multiplier);
    }

    /** String representation of given duration at seconds resolution */
    public static String toString(Duration duration) { // NOSONAR -- complexity
        return toString(duration, SECONDS);
    }
    @SuppressWarnings({"MagicNumber", "StringConcatenationMissingWhitespace"})
    public static String toString(Duration duration, ChronoUnit suffixResolution) { // NOSONAR -- complexity
        final int  res     = suffixResolution.ordinal();
        final long nanos   = duration.toNanos();
        final long micros  = duration.toNanos() / 1000;
        final long millis  = duration.toMillis();
        final long seconds = millis  / 1000;
        final long minutes = seconds / 60;
        final long hours   = minutes / 60;
        final long days    = hours   / 24;
        final long weeks   = days    / 7;

        final String initial;

        //noinspection IfStatementWithTooManyBranches -- this is pretty readable, but I'm open to alternatives
        if(nanos   < 1000) initial = nanos   + "ns"; else
        if(micros  < 1000) initial = micros  + "us"; else
        if(millis  < 1000) initial = millis  + "ms"; else
        if(seconds <   60) initial = seconds + "s"; else
        if(minutes <   60) initial = minutes + "m"; else
        if(hours   <   24) initial = hours   + "h"; else
        if(days    <    7) initial = days    + "d"; else initial = weeks   + "w";
        return initial
            + (res <= DAYS   .ordinal() && days    >=    7 && days    %    7 != 0 ? days    %    7 + "d" : "")
            + (res <= HOURS  .ordinal() && hours   >=   24 && hours   %   24 != 0 ? hours   %   24 + "h" : "")
            + (res <= MINUTES.ordinal() && minutes >=   60 && minutes %   60 != 0 ? minutes %   60 + "m" : "")
            + (res <= SECONDS.ordinal() && seconds >=   60 && seconds %   60 != 0 ? seconds %   60 + "s" : "")
            + (res <= MILLIS .ordinal() && millis  >= 1000 && millis  % 1000 != 0 ? millis  % 1000 + "ms" : "")
            + (res <= MICROS .ordinal() && micros  >= 1000 && micros  % 1000 != 0 ? micros  % 1000 + "us" : "")
            + (res == NANOS  .ordinal() && nanos   >= 1000 && nanos   % 1000 != 0 ? nanos   % 1000 + "ns" : "");
    }

    public static String toStringSinceMillis(long ms) { return toStringSinceMillis(ms, SECONDS); }
    public static String toStringSinceMillis(long ms, ChronoUnit suffixResolution) {
        return toString(Duration.ofMillis(System.currentTimeMillis() - ms), suffixResolution);
    }
}
