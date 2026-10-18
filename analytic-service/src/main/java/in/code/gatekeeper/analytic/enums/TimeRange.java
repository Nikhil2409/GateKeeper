package in.code.gatekeeper.analytic.enums;

import in.code.gatekeeper.analytic.exception.ApiException;

import java.time.Duration;
import java.time.Instant;

/**
 * The ranges the dashboards offer, each split into fixed buckets aligned to the epoch (so a bucket always means the
 * same wall-clock slot). The last bucket is the one "now" falls into, so the newest data is always on the chart.
 */
public enum TimeRange {

    LAST_HOUR("1h", 12, Duration.ofMinutes(5)),
    LAST_DAY("24h", 24, Duration.ofHours(1)),
    LAST_WEEK("7d", 28, Duration.ofHours(6));

    private final String code;
    private final int buckets;
    private final Duration step;

    TimeRange(String code, int buckets, Duration step) {
        this.code = code;
        this.buckets = buckets;
        this.step = step;
    }

    // [from, to): first bucket start to the end of the current bucket
    public record Window(Instant from, Instant to) {
    }

    public static TimeRange from(String code) {
        if (code == null || code.isBlank()) {
            return LAST_DAY;
        }
        for (TimeRange range : values()) {
            if (range.code.equals(code)) {
                return range;
            }
        }
        throw ApiException.badRequest("range must be 1h, 24h or 7d");
    }

    public Window window(Instant now) {
        long stepMs = step.toMillis();
        Instant currentBucket = Instant.ofEpochMilli(now.toEpochMilli() / stepMs * stepMs);
        return new Window(currentBucket.minus(step.multipliedBy(buckets - 1L)), currentBucket.plus(step));
    }

    // The same length of time right before the window, for the "vs previous period" deltas
    public Window previous(Window window) {
        return new Window(window.from().minus(span()), window.from());
    }

    public Duration span() {
        return step.multipliedBy(buckets);
    }

    public String code() {
        return code;
    }

    public int buckets() {
        return buckets;
    }

    public Duration step() {
        return step;
    }
}
