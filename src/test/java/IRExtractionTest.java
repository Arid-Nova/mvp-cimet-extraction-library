import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class IRExtractionTest {
    @Test
    void testGenerateIR() throws GitAPIException, IOException, InterruptedException {
        final Config TEST_CONFIG = ConfigUtil.readConfigFromFile(Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json"));
        IRExtractionService irServ = new IRExtractionService(TEST_CONFIG, Optional.empty());
        irServ.generateIR(Path.of("output" + File.separator + "IR.json"));
        System.out.println("Generated IR at output" + File.separator + "IR.json.");
    }

    @Test
    public void testRestCallExtraction() throws IOException, InterruptedException {
        final String TEST_FILE1 = TestUtilities.JAVA_FILES_PATH + File.separator + "TestFile2.java";
        final String TEST_FILE2 = TestUtilities.JAVA_FILES_PATH + File.separator + "TestFile3.java";

        final Config TEST_CONFIG = ConfigUtil.readConfigFromFile(Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json"));

        if(!(new File("clone" + File.separator + "train-ticket").exists())) {
            GitService gitService = new GitService(TEST_CONFIG);
            gitService.cloneRemote();
        }

        Microservice ms1 = new Microservice();
        ms1.setName("ms1");
        Microservice ms2 = new Microservice();
        ms2.setName("ms2");

        AbstractClass abstractClass1 = SourceToObjectUtils.parseClass(ms1, new File(TEST_FILE1), TEST_CONFIG, false);
        AbstractClass abstractClass2 = SourceToObjectUtils.parseClass(ms2, new File(TEST_FILE2), TEST_CONFIG, false);

        assert abstractClass1 != null;
        assert abstractClass2 != null;

        int count = 0;
        for(Endpoint e : abstractClass1.getEndpoints()) {
            for(RestCall rc : abstractClass2.getRestCalls()) {
                if(RestCall.matchEndpoint(rc, e)) {
                    System.out.println("Passed " + rc.getUrl() + " " + e.getUrl());
                    count++;
                }
            }
        }
        Assertions.assertEquals(3, count);
    }
    @Test
    public void testIRToJSON() throws GitAPIException, IOException, InterruptedException {
        Config CONFIG = ConfigUtil.readConfigFromFile(Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config.json"));

        IRExtractionService.createAndWrite(CONFIG, Path.of("." + File.separator + "output" + File.separator + "TestIR.json"));

        MicroserviceSystem ms1 = IRExtractionService.create(CONFIG);
        MicroserviceSystem ms2 = IRExtractionService.read(Path.of("." + File.separator + "output" + File.separator + "TestIR.json"));
        ms1.setOrphans(null);
        ms2.setOrphans(null);
        TestUtilities.deepCompareSystems(ms1, ms2);
        Assertions.assertEquals(ms1, ms2);
    }
}
