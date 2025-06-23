import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.services.DeltaExtractionService;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.diff.DiffFormatter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class Test {

    public static void main(String[] args) throws Exception {
        // Path to your Git repository
        String repoPath = "C:\\Users\\Connor\\Desktop\\CurrentProjects\\CIMET\\cimet-extract-lib\\clone\\train-ticket";

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