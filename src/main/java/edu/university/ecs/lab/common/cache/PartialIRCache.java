package edu.university.ecs.lab.common.cache;

import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;

import java.util.Optional;

/**
 * Cache for per-repository partial intermediate representations (IRs).
 *
 * <p>IR extraction is expensive (clone + full parse), so partial results are cached
 * per {@code (repository, branch, commit)}. Implementations must never fail extraction:
 * any backend error should degrade to a cache miss (on {@link #get}) or a no-op
 * (on {@link #put}).
 *
 * <p>Replaces the legacy behavior of writing {@code PART_*.json} files to the local
 * output directory, which grew without bound as configurations changed
 * (see issue Arid-Nova/mvp-cimet-extraction-library#14).
 */
public interface PartialIRCache extends AutoCloseable {

    /**
     * Looks up a cached partial IR.
     *
     * @param key cache key, see {@link PartialIRCaches#keyFor}
     * @return the cached value, or {@link Optional#empty()} on miss or backend failure
     */
    Optional<PartialMicroserviceSystemDto> get(String key);

    /**
     * Stores a partial IR. Backend failures are swallowed (extraction proceeds uncached).
     *
     * @param key   cache key, see {@link PartialIRCaches#keyFor}
     * @param value the partial IR to cache
     */
    void put(String key, PartialMicroserviceSystemDto value);

    /**
     * Releases backend resources (e.g. connection pools). Default: no-op.
     */
    @Override
    default void close() {
    }
}
