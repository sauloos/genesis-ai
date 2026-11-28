package ai.genesisbrands.previews;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window counter per key. In memory, so each replica counts on its own; the limits are
 * set so that is still a meaningful brake on scraping.
 */
public class PreviewRateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final java.util.function.LongSupplier clock;

    public PreviewRateLimiter() {
        this(System::currentTimeMillis);
    }

    PreviewRateLimiter(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** Records a hit and returns whether it is within {@code limit} per {@code window}. */
    public boolean allow(String key, int limit, Duration window) {
        long now = clock.getAsLong();
        long cutoff = now - window.toMillis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= cutoff) q.pollFirst();
            if (q.size() >= limit) return false;
            q.addLast(now);
            return true;
        }
    }

    /** Drops keys with no recent hits; called occasionally so the map cannot grow without bound. */
    public void sweep(Duration longestWindow) {
        long cutoff = clock.getAsLong() - longestWindow.toMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || e.getValue().peekLast() <= cutoff;
            }
        });
    }
}
