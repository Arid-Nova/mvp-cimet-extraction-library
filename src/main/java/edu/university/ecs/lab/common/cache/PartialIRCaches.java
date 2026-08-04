package edu.university.ecs.lab.common.cache;

import edu.university.ecs.lab.common.config.RepositoryConfig;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Factory and key scheme for {@link PartialIRCache} instances.
 *
 * <p>Backend selection is environment-driven so that embedding services
 * (e.g. the AridNova toolkit's backend and componentanalysis containers)
 * can switch backends purely through deployment configuration:
 *
 * <ul>
 *   <li>{@code CIMET_CACHE} — {@code redis} | {@code memory} | {@code none}.
 *       Default: {@code redis} when {@code CIMET_REDIS_URI} is set,
 *       {@code memory} otherwise.</li>
 *   <li>{@code CIMET_REDIS_URI} — Redis connection URI,
 *       e.g. {@code redis://cimet_redis:6379}.</li>
 *   <li>{@code CIMET_REDIS_TTL_SECONDS} — optional per-entry TTL; {@code 0}
 *       (default) relies solely on server-side LRU eviction.</li>
 *   <li>{@code CIMET_CACHE_MAX_ENTRIES} — in-memory LRU bound
 *       (default {@value InMemoryLruPartialIRCache#DEFAULT_MAX_ENTRIES}).</li>
 * </ul>
 */
public final class PartialIRCaches {

    private static final Logger LOGGER = Logger.getLogger(PartialIRCaches.class.getName());

    public static final String ENV_CACHE_MODE = "CIMET_CACHE";
    public static final String ENV_REDIS_URI = "CIMET_REDIS_URI";
    public static final String ENV_REDIS_TTL_SECONDS = "CIMET_REDIS_TTL_SECONDS";
    public static final String ENV_MAX_ENTRIES = "CIMET_CACHE_MAX_ENTRIES";

    /** Versioned key prefix; bump {@code v1} if the partial IR JSON schema changes shape. */
    private static final String KEY_PREFIX = "cimet:partial-ir:v1:";

    private PartialIRCaches() {
    }

    /**
     * Builds the cache key for a repository configuration. Mirrors the identity of
     * the legacy {@code PART_<repo>_<branch>_<commit>.json} file names: a partial IR
     * is fully determined by repository, branch, and commit.
     */
    public static String keyFor(RepositoryConfig rc) {
        return KEY_PREFIX
                + rc.getRepoName() + ":"
                + rc.repoBranchPair().branchName() + ":"
                + rc.commitID();
    }

    /** Creates a cache from process environment variables (see class docs). */
    public static PartialIRCache fromEnvironment() {
        return fromSettings(System.getenv());
    }

    /**
     * Creates a cache from an explicit settings map. Exposed (and used by tests)
     * so selection logic is verifiable without manipulating process state.
     */
    public static PartialIRCache fromSettings(Map<String, String> settings) {
        String redisUri = trimToNull(settings.get(ENV_REDIS_URI));
        String mode = trimToNull(settings.get(ENV_CACHE_MODE));
        if (mode == null) {
            mode = (redisUri != null) ? "redis" : "memory";
        }

        switch (mode.toLowerCase()) {
            case "redis":
                if (redisUri == null) {
                    LOGGER.warning(ENV_CACHE_MODE + "=redis but " + ENV_REDIS_URI
                            + " is not set; falling back to in-memory LRU cache.");
                    return new InMemoryLruPartialIRCache(maxEntries(settings));
                }
                return new RedisPartialIRCache(redisUri, ttlSeconds(settings));
            case "memory":
                return new InMemoryLruPartialIRCache(maxEntries(settings));
            case "none":
                return new NoOpPartialIRCache();
            default:
                LOGGER.warning("Unknown " + ENV_CACHE_MODE + " value '" + mode
                        + "'; expected redis|memory|none. Using in-memory LRU cache.");
                return new InMemoryLruPartialIRCache(maxEntries(settings));
        }
    }

    private static int maxEntries(Map<String, String> settings) {
        String raw = trimToNull(settings.get(ENV_MAX_ENTRIES));
        if (raw == null) {
            return InMemoryLruPartialIRCache.DEFAULT_MAX_ENTRIES;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid " + ENV_MAX_ENTRIES + " value '" + raw + "'; using default "
                    + InMemoryLruPartialIRCache.DEFAULT_MAX_ENTRIES + ".");
            return InMemoryLruPartialIRCache.DEFAULT_MAX_ENTRIES;
        }
    }

    private static long ttlSeconds(Map<String, String> settings) {
        String raw = trimToNull(settings.get(ENV_REDIS_TTL_SECONDS));
        if (raw == null) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid " + ENV_REDIS_TTL_SECONDS + " value '" + raw + "'; disabling TTL.");
            return 0L;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
