package net.microstar.common.conversions;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static java.time.temporal.ChronoUnit.MICROS;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.NANOS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static java.time.temporal.ChronoUnit.WEEKS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationStringTest {

    @Test void testStringToDuration() {
        assertThat(DurationString.toDuration("4h49m10s2ms300ns"), is(Duration.ofNanos(17350_002_000_300L)));
        assertThat(DurationString.toDuration("2s300ms"), is(Duration.ofMillis(2300)));
        assertThat(DurationString.toDuration("123ns" ), is(Duration.ofNanos(123)));
        assertThat(DurationString.toDuration("123ms" ), is(Duration.ofMillis(123)));
        assertThat(DurationString.toDuration("123s"  ), is(Duration.ofSeconds(123)));
        assertThat(DurationString.toDuration("123min"), is(Duration.ofMinutes(123)));
        assertThat(DurationString.toDuration("123h"  ), is(Duration.ofHours(123)));
        assertThat(DurationString.toDuration("123d"  ), is(Duration.ofDays(123)));
        assertThat(DurationString.toDuration("123 weeks"), is(Duration.ofDays(123*7)));
        assertThat(DurationString.toDuration("123 hours"), is(Duration.ofHours(123)));
        assertThat(DurationString.toDuration("2s150ms"), is(Duration.ofMillis(2150)));
        assertThat(DurationString.toDuration("2.5s"), is(Duration.ofMillis(2500)));
        assertThat(DurationString.toDuration("2.17s"), is(Duration.ofMillis(2170)));
        assertThat(DurationString.toDuration("2.17s5ms"), is(Duration.ofMillis(2175)));
        assertThat(DurationString.toDuration("PT5S"), is(Duration.ofSeconds(5)));

        List.of("7weeks 2days 13hours 12 minutes", "1 week 2 days 1 minute 3 millis 15 nanos",
                "5h30m", "12w2s", "1 min 13 seconds", "12.5s13ms", Duration.ofHours(3).toString())
            .forEach(input -> assertThat(DurationString.toDuration(DurationString.toString(DurationString.toDuration(input), NANOS)), is(DurationString.toDuration(input))));

        assertThrows(IllegalArgumentException.class, () -> DurationString.toDuration(""));
        assertThrows(IllegalArgumentException.class, () -> DurationString.toDuration("nonsense"));
        assertThrows(IllegalArgumentException.class, () -> DurationString.toDuration("3illegals"));
        assertThrows(IllegalArgumentException.class, () -> DurationString.toDuration("3h2illegals"));
    }
    @Test void testDurationToString() {
        final ChronoUnit cu = NANOS;
        assertThat(DurationString.toString(Duration.ofNanos(300), cu), is("300ns"));
        assertThat(DurationString.toString(Duration.ofNanos(1_000_300), cu), is("1ms300ns"));
        assertThat(DurationString.toString(Duration.ofNanos(1_002_000_300), cu), is("1s2ms300ns"));
        assertThat(DurationString.toString(Duration.ofNanos(123_002_000_300L), cu), is("2m3s2ms300ns"));
        assertThat(DurationString.toString(Duration.ofNanos(7350_002_000_300L), cu), is("2h2m30s2ms300ns"));
        assertThat(DurationString.toString(Duration.ofNanos(17350_002_000_300L), cu), is("4h49m10s2ms300ns"));
        assertThat(DurationString.toString(Duration.ofMillis(123), cu), is("123ms"));
        assertThat(DurationString.toString(Duration.ofMillis(1234), cu), is("1s234ms"));
        assertThat(DurationString.toString(Duration.ofSeconds(1), cu), is("1s"));
        assertThat(DurationString.toString(Duration.ofSeconds(123), cu), is("2m3s"));
        assertThat(DurationString.toString(Duration.ofMinutes(12), cu), is("12m"));
        assertThat(DurationString.toString(Duration.ofMinutes(123), cu), is("2h3m"));
        assertThat(DurationString.toString(Duration.ofHours(12), cu), is("12h"));
        assertThat(DurationString.toString(Duration.ofHours(123), cu), is("5d3h"));
        assertThat(DurationString.toString(Duration.ofDays(3), cu), is("3d"));
        assertThat(DurationString.toString(Duration.ofDays(123), cu), is("17w4d"));

        final Duration d = Duration.ofNanos(999_350_002_050_300L);
        assertThat(DurationString.toString(d, NANOS),   is("1w4d13h35m50s2ms50us300ns"));
        assertThat(DurationString.toString(d, MICROS),  is("1w4d13h35m50s2ms50us"));
        assertThat(DurationString.toString(d, MILLIS),  is("1w4d13h35m50s2ms"));
        assertThat(DurationString.toString(d, SECONDS), is("1w4d13h35m50s"));
        assertThat(DurationString.toString(d, MINUTES), is("1w4d13h35m"));
        assertThat(DurationString.toString(d, HOURS),   is("1w4d13h"));
        assertThat(DurationString.toString(d, DAYS),    is("1w4d"));
        assertThat(DurationString.toString(d, WEEKS),   is("1w"));
    }
}