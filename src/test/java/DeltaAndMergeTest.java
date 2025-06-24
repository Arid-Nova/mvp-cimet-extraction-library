import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.services.DeltaExtractionService;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

class DeltaAndMergeTest {
    private static IRExtractionService irExtractionService;
    private static List<RevCommit> list;
    final static String TEST_CONFIG_PATH = TestUtilities.CONFIGS_PATH + File.separator + "test_config3.json";

    @BeforeAll
    public static void setUp() throws GitAPIException, IOException, InterruptedException {
        FileUtils.makeDirs();
        GitService gitService = new GitService(TEST_CONFIG_PATH);

        list = TestUtilities.iterableToList(gitService.getLog());
        irExtractionService = new IRExtractionService(TEST_CONFIG_PATH, Optional.of(list.get(0).toString().split(" ")[1]));
        irExtractionService.generateIR("./output/OldIR.json");
    }

    @Test
    void testComparison() throws IOException, InterruptedException, GitAPIException {
        // Loop through commit history and create delta, merge, etc...
        System.out.printf("Testing over %d commits...", list.size());
        for (int i = 0; i < list.size() - 1; i++) {
            String commitIdOld = list.get(i).toString().split(" ")[1];
            String commitIdNew = list.get(i + 1).toString().split(" ")[1];

            // Extract changes from one commit to the other
            DeltaExtractionService.createAndWrite(TEST_CONFIG_PATH, "./output/OldIR.json", commitIdOld, commitIdNew, "./output/Delta.json");

            // Merge Delta changes to old IR to create new IR representing new commit changes
            MergeService.createAndWrite(TEST_CONFIG_PATH, "./output/OldIR.json", "./output/Delta.json", commitIdNew, "./output/NewIR.json");

            if(i < list.size() - 2) {
                Files.move(Paths.get("./output/NewIR.json"), Paths.get("./output/OldIR.json"), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Create IR of last commit
        irExtractionService = new IRExtractionService(TEST_CONFIG_PATH, Optional.of(list.get(list.size() - 1).toString().split(" ")[1]));
        irExtractionService.generateIR("./output/TestIR.json");

        // Compare two IRs for equivalence
        MicroserviceSystem microserviceSystem1 = JsonReadWriteUtils.readFromJSON("./output/NewIR.json", MicroserviceSystem.class);
        MicroserviceSystem microserviceSystem2 = JsonReadWriteUtils.readFromJSON("./output/TestIR.json", MicroserviceSystem.class);

        microserviceSystem1.setOrphans(new HashSet<>());
        microserviceSystem2.setOrphans(new HashSet<>());

        TestUtilities.deepCompareSystems(microserviceSystem1, microserviceSystem2);
        Assertions.assertTrue(Objects.deepEquals(microserviceSystem1, microserviceSystem2));
    }

    @Test
    void testDelta() throws GitAPIException, IOException, InterruptedException {
        // Commit hashes to compare
        String oldCommitHash = "9bdd9a28f0033e91dec4595d257da81cc7016e47";
        String newCommitHash = "313886e99befb94be6cd45f085c98e0019f59829";

        MicroserviceSystem msSystem = IRExtractionService.create(Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json"), oldCommitHash);
        JsonReadWriteUtils.writeToJSON("output\\TestingIR.json", msSystem);

        DeltaExtractionService.createAndWrite(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json",
                "./output/TestingIR.json",
                oldCommitHash,
                newCommitHash,
                "./output/RevisedDelta.json");
    }
}