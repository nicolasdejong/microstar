package net.microstar.spring;

import net.microstar.common.util.TimedCounter;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Combination of various TimedCounter instances for different periods and resolutions */
public class TimedCounters {
    private final TimedCounter counter1HourPerSecond    = new TimedCounter(Duration.ofHours(1),  TimeUnit.SECONDS);
    private final TimedCounter counter24HoursPerMinute  = new TimedCounter(Duration.ofHours(24), TimeUnit.MINUTES);
    private final TimedCounter counter7DaysPer15Minutes = new TimedCounter(Duration.ofDays(7),   Duration.ofMinutes(15));

    public void increase() {
        counter1HourPerSecond.increase();
        counter24HoursPerMinute.increase();
        counter7DaysPer15Minutes.increase();
    }

    @SuppressWarnings("MagicNumber")
    public int getCountInLast(Duration duration) {
        if(duration.toHours() > 24) return counter7DaysPer15Minutes.sumSinceLast(duration);
        if(duration.toMinutes() > 60) return counter24HoursPerMinute.sumSinceLast(duration);
        return counter1HourPerSecond.sumSinceLast(duration);
    }

    // Later this data can be used to generate graphs
}
