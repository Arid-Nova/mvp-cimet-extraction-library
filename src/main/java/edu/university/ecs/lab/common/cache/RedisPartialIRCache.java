package edu.university.ecs.lab.common.cache;

import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;
import redis.clients.jedis.JedisPooled;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed partial IR cache (recommended backend, issue #14).
 *
 * <p>Values are stored as JSON strings under keys produced by
 * {@link PartialIRCaches#keyFor}. Eviction strategy:
 *
 * <ul>
 *   <li><b>LRU</b> — configure the Redis server with
 *       {@code maxmemory <bound>} and {@code maxmemory-policy allkeys-lru};
 *       Redis then evicts least-recently-used partial IRs under memory pressure.</li>
 *   <li><b>TTL (optional)</b> — a per-entry expiry can additionally be set via
 *       {@code CIMET_REDIS_TTL_SECONDS} for time-based invalidation.</li>
 * </ul>
 *
 * <p>Failure semantics: Redis being down must never break IR extraction. Every
 * operation catches backend exceptions, logs a warning once, and degrades to a
 * cache miss / no-op.
 */
public final class RedisPartialIRCache implements PartialIRCache {

    private static final Logger LOGGER = Logger.getLogger(RedisPartialIRCache.class.getName());

    private final JedisPooled jedis;
    private final long ttlSeconds;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    /**
     * @param redisUri   Redis connection URI, e.g. {@code redis://localhost:6379}
     * @param ttlSeconds per-entry TTL in seconds; {@code 0} disables TTL (rely on
     *                   server-side LRU eviction only)
     */
    public RedisPartialIRCache(String redisUri, long ttlSeconds) {
        this(new JedisPooled(redisUri), ttlSeconds);
    }

    /** Visible for tests: inject a preconfigured client. */
    RedisPartialIRCache(JedisPooled jedis, long ttlSeconds) {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must be >= 0, got " + ttlSeconds);
        }
        this.jedis = jedis;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public Optional<PartialMicroserviceSystemDto> get(String key) {
        try {
            String json = jedis.get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(PartialIRCodec.fromJson(json));
        } catch (RuntimeException e) {
            warnOnce("get", e);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, PartialMicroserviceSystemDto value) {
        try {
            String json = PartialIRCodec.toJson(value);
            if (ttlSeconds > 0) {
                jedis.setex(key, ttlSeconds, json);
            } else {
                jedis.set(key, json);
            }
        } catch (RuntimeException e) {
            warnOnce("put", e);
        }
    }

    @Override
    public void close() {
        try {
            jedis.close();
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Error closing Redis client", e);
        }
    }

    private void warnOnce(String operation, RuntimeException e) {
        if (failureLogged.compareAndSet(false, true)) {
            LOGGER.log(Level.WARNING,
                    "Redis partial IR cache unavailable (" + operation + " failed); "
                            + "continuing without cache. Cause: " + e.getMessage(), e);
        } else {
            LOGGER.log(Level.FINE, "Redis partial IR cache " + operation + " failed", e);
        }
    }
}
