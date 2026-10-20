package in.code.gatekeeper.gateway.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This instance's own fixed-window counters, used only while Redis is down (decision #72). Same windows and keys
 * as the Redis counters; counts are not shared with the other instances, so callers compare them against the
 * instance's share of the limit. Thrown away when Redis is back.
 */
public class LocalWindowCounter {

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private volatile long currentWindow;

    public long hit(String counterKey, long windowStart) {
        if (windowStart != currentWindow) {
            // a new minute: earlier windows can't be hit any more
            counts.keySet().removeIf(key -> !key.endsWith(":" + windowStart));
            currentWindow = windowStart;
        }
        return counts.computeIfAbsent(counterKey + ":" + windowStart, key -> new AtomicLong()).incrementAndGet();
    }

    public void clear() {
        counts.clear();
    }
}
