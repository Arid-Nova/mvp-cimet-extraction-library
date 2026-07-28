import edu.university.ecs.lab.common.cache.InMemoryLruPartialIRCache;
import edu.university.ecs.lab.common.cache.NoOpPartialIRCache;
import edu.university.ecs.lab.common.cache.PartialIRCache;
import edu.university.ecs.lab.common.cache.PartialIRCaches;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;
import edu.university.ecs.lab.common.models.ir.Microservice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Tests for the partial IR cache introduced by issue #14 (replacement of the
 * legacy PART_*.json file-system caching).
 */
public class PartialIRCacheTest {

    private static PartialMicroserviceSystemDto dtoWithService(String serviceName) {
        Microservice microservice = new Microservice();
        microservice.setName(serviceName);
        Set<Microservice> microservices = new HashSet<>();
        microservices.add(microservice);
        return new PartialMicroserviceSystemDto(microservices);
    }

    private static String soleServiceName(PartialMicroserviceSystemDto dto) {
        Assertions.assertEquals(1, dto.getMicroservices().size());
        return dto.getMicroservices().iterator().next().getName();
    }

    @Test
    public void inMemoryCacheRoundTripsThroughSerialization() {
        InMemoryLruPartialIRCache cache = new InMemoryLruPartialIRCache(4);

        cache.put("key-a", dtoWithService("ts-order-service"));
        Optional<PartialMicroserviceSystemDto> hit = cache.get("key-a");

        Assertions.assertTrue(hit.isPresent());
        Assertions.assertEquals("ts-order-service", soleServiceName(hit.get()));
        Assertions.assertTrue(cache.get("absent-key").isEmpty());
    }

    @Test
    public void inMemoryCacheReturnsDetachedCopies() {
        InMemoryLruPartialIRCache cache = new InMemoryLruPartialIRCache(4);
        cache.put("key-a", dtoWithService("original-name"));

        // Mutate the first read; the cached copy must not be affected.
        PartialMicroserviceSystemDto first = cache.get("key-a").orElseThrow();
        first.getMicroservices().iterator().next().setName("mutated-name");

        PartialMicroserviceSystemDto second = cache.get("key-a").orElseThrow();
        Assertions.assertEquals("original-name", soleServiceName(second));
    }

    @Test
    public void inMemoryCacheEvictsLeastRecentlyUsed() {
        InMemoryLruPartialIRCache cache = new InMemoryLruPartialIRCache(2);

        cache.put("key-a", dtoWithService("a"));
        cache.put("key-b", dtoWithService("b"));
        // Touch key-a so key-b becomes the least recently used entry.
        Assertions.assertTrue(cache.get("key-a").isPresent());

        cache.put("key-c", dtoWithService("c"));

        Assertions.assertEquals(2, cache.size());
        Assertions.assertTrue(cache.get("key-a").isPresent(), "recently used entry must survive");
        Assertions.assertTrue(cache.get("key-b").isEmpty(), "LRU entry must be evicted");
        Assertions.assertTrue(cache.get("key-c").isPresent());
    }

    @Test
    public void inMemoryCacheRejectsNonPositiveBound() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new InMemoryLruPartialIRCache(0));
    }

    @Test
    public void noOpCacheNeverStores() {
        NoOpPartialIRCache cache = new NoOpPartialIRCache();
        cache.put("key-a", dtoWithService("a"));
        Assertions.assertTrue(cache.get("key-a").isEmpty());
    }

    @Test
    public void keyForMirrorsLegacyFileIdentity() {
        RepositoryConfig rc = new RepositoryConfig(
                new RepositoryBranchPair("https://github.com/g-goulis/train-ticket-microservices-test.git", "main"),
                "06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7");

        String key = PartialIRCaches.keyFor(rc);

        Assertions.assertEquals(
                "cimet:partial-ir:v1:train-ticket-microservices-test:main:06f3e1efe2e2539d05d91b0699cc8d9fe7be29d7",
                key);
    }

    @Test
    public void factorySelectsBackendFromSettings() {
        Assertions.assertInstanceOf(InMemoryLruPartialIRCache.class,
                PartialIRCaches.fromSettings(Map.of()),
                "default with no settings must be the in-memory LRU cache");

        Assertions.assertInstanceOf(InMemoryLruPartialIRCache.class,
                PartialIRCaches.fromSettings(Map.of(PartialIRCaches.ENV_CACHE_MODE, "memory")));

        Assertions.assertInstanceOf(NoOpPartialIRCache.class,
                PartialIRCaches.fromSettings(Map.of(PartialIRCaches.ENV_CACHE_MODE, "none")));

        // redis requested but no URI provided: must fall back, not fail.
        Assertions.assertInstanceOf(InMemoryLruPartialIRCache.class,
                PartialIRCaches.fromSettings(Map.of(PartialIRCaches.ENV_CACHE_MODE, "redis")));

        // Unknown mode: must fall back, not fail.
        Assertions.assertInstanceOf(InMemoryLruPartialIRCache.class,
                PartialIRCaches.fromSettings(Map.of(PartialIRCaches.ENV_CACHE_MODE, "filesystem")));
    }

    @Test
    public void factoryHonorsMaxEntriesSetting() {
        PartialIRCache cache = PartialIRCaches.fromSettings(Map.of(
                PartialIRCaches.ENV_CACHE_MODE, "memory",
                PartialIRCaches.ENV_MAX_ENTRIES, "1"));

        InMemoryLruPartialIRCache lru = Assertions.assertInstanceOf(InMemoryLruPartialIRCache.class, cache);
        lru.put("key-a", dtoWithService("a"));
        lru.put("key-b", dtoWithService("b"));

        Assertions.assertEquals(1, lru.size());
        Assertions.assertTrue(lru.get("key-b").isPresent());
    }
}
