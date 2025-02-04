import org.junit.jupiter.api.Test;

import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;

import java.io.File;
import java.util.Optional;

public class IRExtractionTest {

    public static final String TEST_CONFIG_PATH = "src" + File.separator + "test" + File.separator + "resources" + File.separator + "test_config.json";

    @Test
    void testGenerate() {
        IRExtractionService irServ = new IRExtractionService(TEST_CONFIG_PATH, Optional.empty());
        irServ.generateIR("output/IR.json");
    }
}
