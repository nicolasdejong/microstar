package net.microstar.common.util;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Count additions per time resolution for a given period. Thread safe.<br><br>
  *
  * For example a TimedCounter of second resolution and one hour will provide
  * the functionality to get the number of additions in the last seconds
  * upto 3600 seconds.<br><br>
  *
  * For example a TimedCounter of minute resolution and 24 hours will provide
  * the functionality to get the number of additions in the last minutes upto
  * 24 * 60 minutes.<br><br>
  *
  * A round-robin array is used so adding costs (nearly) no time but getting
  * the result requires getting the sum of all values (upto the requested time)
  * so the performance and memory requirements are defined by the total time
  * divided by the resolution. (but note that the number of buckets has to be
  * several million before calculating the sum takes more than 1 ms)
  */
public class TimedCounter {
    private final long resolutionMs;
    private final long totalTimeMs;
    private final long startTimeMs;
    private final int[] buckets;
    private final Object sync = new Object();
    private long lastUpdatedTimeMs;

    /** Creates a timed counter for the given total time where a count is kept per resolution */
    public TimedCounter(Duration totalTime, TimeUnit resolution) {
        this(totalTime, Duration.ofMillis(resolution.toMillis(1)));
    }

    /** Creates a timed counter for the given total time where a count is kept per resolution */
    @SuppressWarnings({"OverridableMethodCallDuringObjectConstruction", "OverriddenMethodCallDuringObjectConstruction", "this-escape"})
    public TimedCounter(Duration totalTime, Duration resolution) {
        if(resolution.isZero() || totalTime.isZero()) throw new IllegalArgumentException("Resolution and totalTime must both be greater than zero");
        this.resolutionMs = resolution.toMillis();
        this.totalTimeMs = totalTime.toMillis();
        buckets = new int[(int)(totalTimeMs / resolutionMs)];
        startTimeMs = lastUpdatedTimeMs = now();
    }

    /** This method can be overridden by the unit test so there are no clock dependencies */
    protected long now() {
        return System.currentTimeMillis();
    }

    public void clear() {
        synchronized(sync) { Arrays.fill(buckets, 0); }
    }

    /** Returns the sum of additions for the complete period (totalTime given at construction) */
    public int sum() {
        return sumSinceLast(Duration.ofMillis(totalTimeMs));
    }

    /** Returns the sum of additions for the period given before now.<p>
      *
      * It is unlikely that the current time is exactly on the resolution given at construction.
      * That means that the oldest bucket value needed for the sum will be for a longer period than
      * requested here. Then there are three possible solutions: include that whole value (leading
      * to a somewhat greater sum than requested), exclude that whole value (leading to a somewhat
      * smaller sum than requested) or use the partial value equal to the fraction of time over that
      * bucket (leading to an unknown deviation because the distribution within a bucket isn't stored).
      * This implementation chooses the last option: using a fraction of the bucket. The deviation
      * from actual then depends on the unknown distribution of the bucket values which will be higher
      * if the increases are more spiked and lower when increases are flatter.
      */
    public int sumSinceLast(Duration period) {
        synchronized(sync) {
            increase(0); // side effect of increase() is that it removes old values
            long timeToGetMs = period.toMillis();
            if (timeToGetMs >= totalTimeMs) return Arrays.stream(buckets).sum();
            int index = indexForTime(now());

            int amount = 0;
            long step = now() % resolutionMs; if(step == 0) step = resolutionMs;
            while(timeToGetMs >= resolutionMs) {
                amount += buckets[index];
                if(--index < 0) index = buckets.length - 1;
                timeToGetMs -= step; step = resolutionMs;
            }
            if(timeToGetMs > 0) {
                final double fraction = (double)timeToGetMs / resolutionMs;
                amount += (int)(buckets[index] * fraction);
            }
            return amount;
        }
    }

    /** Increase by one */
    public void increase() { increase(1); }

    /** Increase the current bucket by the given amount. Negative amounts are ignored. */
    public void increase(int amount) {
        synchronized(sync) {
            final long time = now();
            if(time - lastUpdatedTimeMs > totalTimeMs) clear();
            final int lastUpdatedIndex = indexForTime(lastUpdatedTimeMs);
            final int indexToUpdate = indexForTime(time);
            lastUpdatedTimeMs = time;

            int index = lastUpdatedIndex;
            while(index != indexToUpdate) { index = (index + 1) % buckets.length; buckets[index] = 0; }
            buckets[index] += Math.max(amount, 0);
        }
    }

    private int indexForTime(long timeMs) {
        return (int)((timeMs - startTimeMs) / resolutionMs) % buckets.length;
    }
}
