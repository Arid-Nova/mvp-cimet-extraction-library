import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Integration test for the multi-repository IR extraction path. Verifies that an IR
 * generated from a {@code Config} containing more than one repository is structurally
 * valid: it contains microservices from every configured repository, every microservice
 * is correctly attributed to its source repo, the count and shape of the IR matches the
 * pinned-commit expectation, and the IR survives a JSON round-trip without loss.
 * <p>
 * Pinned commits in {@code test_config3.json}:
 * <ul>
 *   <li>{@code koushikkothagal/spring-boot-microservices-workshop} @ {@code 8b01c6d1}</li>
 *   <li>{@code mdeket/spring-cloud-movie-recommendation} @ {@code 5aa5ee9e}</li>
 * </ul>
 */
@Tag("integration")
@Tag("multi-repo")
class MultiRepoExtractionIT {

    private static final Path CONFIG_PATH =
            Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config3.json");
    private static final Path IR_OUT =
            Path.of(FileUtils.getOutputPath() + File.separator + "MultiRepoIR.json");

    private static final String WORKSHOP_REPO =
            "https://github.com/koushikkothagal/spring-boot-microservices-workshop.git";
    private static final String MOVIE_REC_REPO =
            "https://github.com/mdeket/spring-cloud-movie-recommendation.git";

    // Pinned-commit expectations. Update these whenever the commitIDs in test_config3.json
    // are bumped, and treat unexpected drift as a parser regression until proven otherwise.
    private static final int EXPECTED_TOTAL_MICROSERVICES = 10;
    private static final int EXPECTED_TOTAL_ENDPOINTS = 18;

    private static final Map<String, Set<String>> EXPECTED_SERVICES_BY_REPO = Map.of(
            WORKSHOP_REPO, Set.of(
                    "movie-info-service",
                    "discovery-server",
                    "ratings-data-service",
                    "movie-catalog-service"),
            MOVIE_REC_REPO, Set.of(
                    "recommendation-client",
                    "eureka-service",
                    "user-service",
                    "config-service",
                    "recommendation-service",
                    "movie-service"));

    private static Config config;
    private static MicroserviceSystem irFromDisk;

    @BeforeAll
    static void cloneAndExtractMultiRepoIR() throws IOException, InterruptedException, GitAPIException {
        FileUtils.makeDirs();
        config = ConfigUtil.readConfigFromFile(CONFIG_PATH);
        IRExtractionService.createAndWrite(config, IR_OUT.getFileName().toString());
        irFromDisk = IRExtractionService.read(IR_OUT);
        Assertions.assertNotNull(irFromDisk, "IR read back from disk must not be null");
        Assertions.assertNotNull(irFromDisk.getMicroservices(),
                "MicroserviceSystem.microservices must be non-null after extraction");
    }

    @Test
    @DisplayName("Multi-repo IR contains microservices from every configured repository")
    void includesMicroservicesFromEveryConfiguredRepository() {
        Set<String> repoURLsSeen = irFromDisk.getMicroservices().stream()
                .map(Microservice::getRepositoryURL)
                .collect(Collectors.toSet());

        Assertions.assertTrue(repoURLsSeen.contains(WORKSHOP_REPO),
                "Multi-repo IR is missing microservices from " + WORKSHOP_REPO
                        + "; saw repositoryURLs: " + repoURLsSeen);
        Assertions.assertTrue(repoURLsSeen.contains(MOVIE_REC_REPO),
                "Multi-repo IR is missing microservices from " + MOVIE_REC_REPO
                        + "; saw repositoryURLs: " + repoURLsSeen);
        Assertions.assertEquals(EXPECTED_SERVICES_BY_REPO.keySet(), repoURLsSeen,
                "Multi-repo IR has microservices attributed to unexpected repositories");
    }

    @Test
    @DisplayName("Every microservice tracks a configured, non-blank source repositoryURL")
    void everyMicroserviceTracksItsSourceRepository() {
        List<String> servicesWithMissingRepoURL = irFromDisk.getMicroservices().stream()
                .filter(microservice -> microservice.getRepositoryURL() == null
                        || microservice.getRepositoryURL().isBlank())
                .map(Microservice::getName)
                .toList();
        Assertions.assertTrue(servicesWithMissingRepoURL.isEmpty(),
                "Microservices with missing or blank repositoryURL: "
                        + servicesWithMissingRepoURL);

        List<String> servicesWithUnknownRepoURL = irFromDisk.getMicroservices().stream()
                .filter(microservice -> !EXPECTED_SERVICES_BY_REPO
                        .containsKey(microservice.getRepositoryURL()))
                .map(microservice -> microservice.getName() + " -> " + microservice.getRepositoryURL())
                .toList();
        Assertions.assertTrue(servicesWithUnknownRepoURL.isEmpty(),
                "Microservices attributed to a repository that is not in the config: "
                        + servicesWithUnknownRepoURL);
    }

    @Test
    @DisplayName("Per-repo and total microservice counts match pinned-commit expectation")
    void microserviceCountsPerRepositoryMatchExpectation() {
        Assertions.assertEquals(EXPECTED_TOTAL_MICROSERVICES,
                irFromDisk.getMicroservices().size(),
                "Total microservice count drift in multi-repo IR; "
                        + "if a pinned commit changed, update EXPECTED_TOTAL_MICROSERVICES, "
                        + "otherwise investigate the boundary detector or extractor.");

        Map<String, Set<String>> servicesByRepo = irFromDisk.getMicroservices().stream()
                .collect(Collectors.groupingBy(
                        Microservice::getRepositoryURL,
                        Collectors.mapping(Microservice::getName, Collectors.toSet())));

        for (Map.Entry<String, Set<String>> expected : EXPECTED_SERVICES_BY_REPO.entrySet()) {
            Set<String> actual = servicesByRepo.getOrDefault(expected.getKey(), Set.of());
            Assertions.assertEquals(expected.getValue(), actual,
                    "Microservice set for " + expected.getKey()
                            + " does not match pinned-commit expectation. Expected="
                            + expected.getValue() + ", actual=" + actual);
        }
    }

    @Test
    @DisplayName("Multi-repo IR survives JSON round-trip without structural loss")
    void irRoundTripsThroughJsonWithoutLoss() throws IOException, InterruptedException, GitAPIException {
        MicroserviceSystem inMemory = IRExtractionService.create(config);
        MicroserviceSystem readBack = IRExtractionService.read(IR_OUT);

        // Orphans are an unordered set whose deserialized identity differs by reference;
        // align it before equality, exactly as the single-repo E2E IT does.
        inMemory.setOrphans(new HashSet<>());
        readBack.setOrphans(new HashSet<>());

        Assertions.assertTrue(Objects.deepEquals(inMemory, readBack),
                "Multi-repo IR must round-trip through JSON without losing structure; "
                        + "in-memory and on-disk MicroserviceSystem differ");
    }

    @Test
    @DisplayName("Every controller-bearing microservice in the multi-repo IR exposes at least one endpoint")
    void everyControllerBearingMicroserviceExposesEndpoints() {
        List<Microservice> microservicesWithControllers = irFromDisk.getMicroservices().stream()
                .filter(microservice -> microservice.getControllers() != null
                        && !microservice.getControllers().isEmpty())
                .toList();
        Assertions.assertFalse(microservicesWithControllers.isEmpty(),
                "Multi-repo IR should contain at least one microservice with controllers");

        List<String> controllerBearingServicesWithNoEndpoints = microservicesWithControllers.stream()
                .filter(microservice -> microservice.getEndpoints().isEmpty())
                .map(Microservice::getName)
                .toList();
        Assertions.assertTrue(controllerBearingServicesWithNoEndpoints.isEmpty(),
                "Every controller-bearing microservice in the multi-repo IR must expose "
                        + "at least one endpoint; controller-bearing services with zero "
                        + "endpoints (likely a parser regression): "
                        + controllerBearingServicesWithNoEndpoints);
    }

    @Test
    @DisplayName("Total endpoint count across both repositories matches pinned-commit expectation")
    void totalEndpointCountMatchesPinnedExpectation() {
        int actualTotalEndpoints = irFromDisk.getMicroservices().stream()
                .mapToInt(microservice -> microservice.getEndpoints().size())
                .sum();
        Assertions.assertEquals(EXPECTED_TOTAL_ENDPOINTS, actualTotalEndpoints,
                "Endpoint count drift in multi-repo IR: expected "
                        + EXPECTED_TOTAL_ENDPOINTS + ", got " + actualTotalEndpoints
                        + ". If a pinned commit in test_config3.json changed, update the "
                        + "constant; otherwise investigate the IR extractor for endpoint "
                        + "loss/duplication.");
    }
}
