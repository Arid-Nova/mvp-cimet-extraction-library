package edu.university.ecs.lab.common.cache;

import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded in-process LRU cache for partial IRs.
 *
 * <p>Entries are stored as JSON strings (not object references) so that callers
 * mutating a returned DTO — e.g. path normalization — can never corrupt the
 * cached copy. Eviction is least-recently-used with a fixed entry bound, so
 * memory use is capped regardless of how many configurations are processed.
 *
 * <p>Thread-safe. Used as the default backend when no Redis is configured; note
 * the cache is per-JVM and is lost on restart.
 */
public final class InMemoryLruPartialIRCache implements PartialIRCache {

    /** Default maximum number of cached partial IRs. */
    public static final int DEFAULT_MAX_ENTRIES = 64;

    private final Map<String, String> entries;

    public InMemoryLruPartialIRCache() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * @param maxEntries maximum number of entries retained; must be &gt;= 1
     */
    public InMemoryLruPartialIRCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        // accessOrder=true turns LinkedHashMap into an LRU: both get() and put()
        // refresh an entry's position, and the eldest entry is evicted on overflow.
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxEntries;
            }
        };
    }

    @Override
    public synchronized Optional<PartialMicroserviceSystemDto> get(String key) {
        String json = entries.get(key);
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(PartialIRCodec.fromJson(json));
    }

    @Override
    public synchronized void put(String key, PartialMicroserviceSystemDto value) {
        entries.put(key, PartialIRCodec.toJson(value));
    }

    /** Current number of cached entries (for tests and diagnostics). */
    public synchronized int size() {
        return entries.size();
    }
}
