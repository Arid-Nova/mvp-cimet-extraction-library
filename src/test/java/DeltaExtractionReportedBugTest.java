import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.models.ir.ProjectFile;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.delta.services.DeltaExtractionService;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Tag("integration")
class DeltaExtractionReportedBugTest {
    private static final Path CONFIG_PATH =
            Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json");
    private static final String BASE_COMMIT = "313886e99befb94be6cd45f085c98e0019f59829";
    private static final String COMPARING_COMMIT = "a4ed2433b0b6ab6e0d60115fc19efecb2548c6cd";

    @Test
    @DisplayName("Delta extraction handles clone-root-prefixed train-ticket IR paths")
    void deltaExtractionHandlesCloneRootPrefixedTrainTicketIrPaths()
            throws GitAPIException, IOException, InterruptedException {
        Config config = ConfigUtil.readConfigFromFile(CONFIG_PATH);
        RepositoryConfig baseRepository = config.getSystemRepositories().getFirst();
        config.getSystemRepositories().set(0, new RepositoryConfig(baseRepository.repoBranchPair(), BASE_COMMIT));
        baseRepository = config.getSystemRepositories().getFirst();

        MicroserviceSystem baseIr = IRExtractionService.create(config);
        prefixIrPathsWithCloneRoot(baseIr, baseRepository.getRepoName());

        Map<RepositoryConfig, String> comparingRepositories = new HashMap<>();
        comparingRepositories.put(baseRepository, COMPARING_COMMIT);

        SystemChange delta = Assertions.assertDoesNotThrow(
                () -> DeltaExtractionService.create(config, baseIr, comparingRepositories),
                "DeltaExtractionService.create should not throw `Failed to find old class` "
                        + "when the base IR stores paths under /app/clone/<repo>/"
        );

        Assertions.assertFalse(delta.getChanges().isEmpty(),
                "The reported train-ticket comparison should produce a non-empty delta");
        Assertions.assertEquals(BASE_COMMIT,
                delta.getOldCommits().get(baseRepository.repoBranchPair().repositoryURL()));
        Assertions.assertEquals(COMPARING_COMMIT,
                delta.getNewCommits().get(baseRepository.repoBranchPair().repositoryURL()));
    }

    private void prefixIrPathsWithCloneRoot(MicroserviceSystem baseIr, String repoName) {
        String prefix = "/app/clone/" + repoName;

        for (Microservice microservice : baseIr.getMicroservices()) {
            microservice.setPath(prefixPath(prefix, microservice.getPath()));
            for (ProjectFile file : microservice.getAllFiles()) {
                file.setPath(prefixPath(prefix, file.getPath()));
            }
        }

        for (ProjectFile orphan : baseIr.getOrphans()) {
            orphan.setPath(prefixPath(prefix, orphan.getPath()));
        }
    }

    private Path prefixPath(String prefix, Path path) {
        return Path.of(prefix + path.toString().replace('\\', '/'));
    }
}
