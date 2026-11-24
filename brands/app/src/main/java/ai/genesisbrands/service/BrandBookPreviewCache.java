package ai.genesisbrands.service;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Brand-book cover previews, rendered once per version of a direction and then served from
 * memory. A dashboard asks for every preview at once, and re-asks on each refresh; rendering
 * each of those afresh built a multi-megabyte page per request and ran the heap out, taking
 * downloads down with it. Requests for the same preview wait for one render, and at most
 * {@link #MAX_CONCURRENT_BUILDS} renders run at a time.
 */
@Component
public class BrandBookPreviewCache {

    static final int MAX_ENTRIES = 64;          // a cover JPEG is a few hundred KB at most
    static final int MAX_CONCURRENT_BUILDS = 2; // matches the renderer's Chromium threads

    private final Map<String, byte[]> previews = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    private final Map<String, Object> inFlight = new ConcurrentHashMap<>();
    private final Semaphore builds = new Semaphore(MAX_CONCURRENT_BUILDS, true);

    /**
     * @param key changes whenever the direction's content does (so a regenerated direction gets
     *            a fresh preview)
     */
    public byte[] get(String key, Supplier<byte[]> render) {
        byte[] hit = previews.get(key);
        if (hit != null) return hit;

        Object lock = inFlight.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                hit = previews.get(key);
                if (hit != null) return hit;
                builds.acquireUninterruptibly();
                try {
                    byte[] rendered = render.get();
                    previews.put(key, rendered);
                    return rendered;
                } finally {
                    builds.release();
                }
            }
        } finally {
            inFlight.remove(key, lock);
        }
    }
}
