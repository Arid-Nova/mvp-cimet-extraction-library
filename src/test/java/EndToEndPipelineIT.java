import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.delta.services.DeltaExtractionService;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * End-to-end integration test exercising the full CIMET pipeline:
 * clone -> IR extraction -> JSON round-trip -> Delta extraction -> Merge -> equivalence.
 * <p>
 * Named with the {@code IT} suffix so it is picked up by maven-failsafe-plugin during
 * the {@code verify} phase, not by maven-surefire-plugin during {@code test}. This keeps
 * {@code mvn test} fast (unit + lightweight integration), while {@code mvn verify} or
 * {@code mvn -Pe2e verify} runs the full pipeline against a real Git repository.
 * <p>
 * Tagged {@code integration} and {@code e2e} so callers can opt in/out via JUnit groups.
 */
@Tag("integration")
@Tag("e2e")
class EndToEndPipelineIT {

    private static final Path CONFIG_PATH =
            Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json");
    private static final Path BASE_IR = Path.of(FileUtils.getOutputPath() + File.separator + "E2E_BaseIR.json");
    private static final Path DELTA = Path.of(FileUtils.getOutputPath() + File.separator + "E2E_Delta.json");
    private static final Path MERGED_IR = Path.of(FileUtils.getOutputPath() + File.separator + "E2E_MergedIR.json");
    private static final Path FRESH_IR = Path.of(FileUtils.getOutputPath() + File.separator + "E2E_FreshIR.json");

    private static Config config;
    private static RepositoryConfig pinnedRepo;
    private static String commitA;
    private static String commitB;

    @BeforeAll
    static void cloneAndPickCommitPair() throws IOException, InterruptedException, GitAPIException {
        FileUtils.makeDirs();

        config = ConfigUtil.readConfigFromFile(CONFIG_PATH);
        pinnedRepo = config.getSystemRepositories().getFirst();

        GitService gitService = new GitService(config);
        // Clone if absent; reset to the pinned commit so the log is deterministic.
        if (!new File(FileUtils.getRepositoryPath(pinnedRepo.getRepoName())).exists()) {
            gitService.cloneRemote(pinnedRepo);
        }
        gitService.resetLocal(pinnedRepo, pinnedRepo.commitID());

        // Order: oldest first (after reverse in iterableToList).
        List<RevCommit> log = TestUtilities.iterableToList(gitService.getLog(pinnedRepo));
        Assertions.assertTrue(log.size() >= 2,
                "E2E pipeline needs at least two commits in history; got " + log.size());

        // Pick the last two commits in chronological order so we always exercise a real diff.
        commitA = log.get(log.size() - 2).toString().split(" ")[1];
        commitB = log.get(log.size() - 1).toString().split(" ")[1];
        Assertions.assertNotEquals(commitA, commitB, "commit A and B must differ for a meaningful delta");
    }

    @Test
    @DisplayName("Full pipeline: clone -> IR(A) -> round-trip -> Delta(A,B) -> Merge -> equals fresh IR(B)")
    void runsFullExtractionDeltaMergeAndRoundTrip() throws GitAPIException, IOException, InterruptedException {
        // 1. Extract IR at commit A and write to disk.
        Config configA = pinAt(commitA);
        IRExtractionService.createAndWrite(configA, BASE_IR.getFileName().toString());

        // 2. Round-trip: read written IR back and confirm it matches a fresh in-memory extraction.
        MicroserviceSystem inMemoryIRA = IRExtractionService.create(configA);
        MicroserviceSystem readBackIRA = IRExtractionService.read(BASE_IR);
        normalizeOrphans(inMemoryIRA, readBackIRA);
        Assertions.assertTrue(Objects.deepEquals(inMemoryIRA, readBackIRA),
                "IR written to disk must round-trip via JSON without losing structure");

        // 3. Sanity: the extraction produced something usable, and every microservice
        //    that contains a controller class produces at least one endpoint. (Gateways
        //    and MQ-only services legitimately have no controllers and are exempted —
        //    the failure mode this guards against is "controller in source, but the
        //    parser extracted zero endpoints from it.")
        Assertions.assertFalse(inMemoryIRA.getMicroservices().isEmpty(),
                "IR at commit A should contain at least one microservice");

        List<Microservice> microservicesWithControllers = inMemoryIRA.getMicroservices().stream()
                .filter(microservice -> microservice.getControllers() != null
                        && !microservice.getControllers().isEmpty())
                .toList();
        Assertions.assertFalse(microservicesWithControllers.isEmpty(),
                "IR at commit A should contain at least one microservice with controllers");

        List<String> controllerBearingServicesWithNoEndpoints = microservicesWithControllers.stream()
                .filter(microservice -> microservice.getEndpoints().isEmpty())
                .map(Microservice::getName)
                .toList();
        Assertions.assertTrue(controllerBearingServicesWithNoEndpoints.isEmpty(),
                "Every microservice in IR(A) that contains a controller class must expose "
                        + "at least one endpoint; controller-bearing services with zero "
                        + "endpoints (likely a parser regression): "
                        + controllerBearingServicesWithNoEndpoints);

        // Pinned-commit invariant: train-ticket@313886e9 exposes exactly 262 REST endpoints
        // across 39 controller-bearing microservices. Any drift from this number signals
        // a parser regression (drop) or a legitimate parser improvement (gain) — either
        // way, the test should fail loudly so the change is reviewed and the expected
        // count is updated deliberately. Update this constant when bumping the pinned
        // commit in test_config2.json.
        final int EXPECTED_TOTAL_ENDPOINTS_AT_COMMIT_A = 262;
        int actualTotalEndpoints = inMemoryIRA.getMicroservices().stream()
                .mapToInt(microservice -> microservice.getEndpoints().size())
                .sum();
        Assertions.assertEquals(
                EXPECTED_TOTAL_ENDPOINTS_AT_COMMIT_A,
                actualTotalEndpoints,
                "Endpoint count drift at train-ticket@" + commitA + ": expected "
                        + EXPECTED_TOTAL_ENDPOINTS_AT_COMMIT_A + ", got " + actualTotalEndpoints
                        + ". If train-ticket's pinned commit changed, update the constant; "
                        + "otherwise investigate the IR extractor for endpoint loss/duplication.");

        // 4. Compute delta A -> B and merge onto IR(A) to produce a derived IR at B.
        Map<RepositoryConfig, String> commits = new HashMap<>();
        commits.put(configA.getSystemRepositories().getFirst(), commitB);
        DeltaExtractionService.createAndWrite(configA, BASE_IR, commits, DELTA);
        MergeService.createAndWrite(BASE_IR, DELTA, MERGED_IR);

        // 5. Extract a fresh IR at commit B straight from source for ground truth.
        Config configB = pinAt(commitB);
        IRExtractionService.createAndWrite(configB, FRESH_IR.getFileName().toString());

        // 6. The merged IR must be structurally equivalent to the fresh IR.
        MicroserviceSystem mergedAtB = IRExtractionService.read(MERGED_IR);
        MicroserviceSystem freshAtB = IRExtractionService.read(FRESH_IR);
        normalizeOrphans(mergedAtB, freshAtB);

        TestUtilities.deepCompareSystems(mergedAtB, freshAtB);
        Assertions.assertTrue(
                Objects.deepEquals(mergedAtB, freshAtB),
                "Merging Delta(A,B) onto IR(A) must produce the same MicroserviceSystem as a fresh IR(B)"
        );

        // 7. Final sanity invariants on the ground-truth IR at B.
        Assertions.assertFalse(freshAtB.getMicroservices().isEmpty(),
                "Fresh IR at commit B should contain at least one microservice");
        Assertions.assertEquals(
                mergedAtB.getMicroservices().size(),
                freshAtB.getMicroservices().size(),
                "Merged IR and fresh IR must agree on microservice count"
        );
    }

    private static Config pinAt(String commitId) throws IOException {
        Config copy = ConfigUtil.readConfigFromFile(CONFIG_PATH);
        RepositoryConfig original = copy.getSystemRepositories().getFirst();
        copy.getSystemRepositories().set(0, new RepositoryConfig(original.repoBranchPair(), commitId));
        return copy;
    }

    private static void normalizeOrphans(MicroserviceSystem... systems) {
        for (MicroserviceSystem system : systems) {
            system.setOrphans(new HashSet<>());
        }
    }
}
