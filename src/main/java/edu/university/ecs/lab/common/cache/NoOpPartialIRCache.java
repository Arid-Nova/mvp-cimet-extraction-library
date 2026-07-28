package edu.university.ecs.lab.common.cache;

import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;

import java.util.Optional;

/**
 * Disabled cache: every lookup misses, every store is discarded.
 * Selected with {@code CIMET_CACHE=none}; every extraction re-clones and re-parses.
 */
public final class NoOpPartialIRCache implements PartialIRCache {

    @Override
    public Optional<PartialMicroserviceSystemDto> get(String key) {
        return Optional.empty();
    }

    @Override
    public void put(String key, PartialMicroserviceSystemDto value) {
        // intentionally discarded
    }
}
