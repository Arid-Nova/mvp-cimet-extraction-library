import edu.university.ecs.lab.common.services.JsonSchemaService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

public class JSONDocumentationTest {
    @Test
    void testGenerateDocs() throws IOException {
        // Only generate new docs if the old ones have been deleted
        File docsFolder = new File(".docs");
        if (docsFolder.exists() && docsFolder.isDirectory()) {
            File[] files = docsFolder.listFiles();
            if (files != null && files.length > 0)
                return;
        }

        JsonSchemaService.writeSchemas();
        System.out.println("Generated JSON documentation at ./.docs/schema/");
    }
}
