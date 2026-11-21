import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.services.JsonSchemaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class JSONDocumentationTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void generatesBothSchemasInsideANestedDirectoryWithoutATrailingSeparator() throws IOException {
        Path directory = temporary.resolve("nested").resolve("schema");
        JsonSchemaService.writeSchemas(directory.toString());

        JsonNode system = readSchema(directory, "MicroserviceSystemSchema.json");
        assertEquals("object", system.path("type").asText());
        assertTrue(system.path("properties").has("name"));
        assertEquals("array", system.path("properties").path("microservices").path("type").asText());

        JsonNode changes = readSchema(directory, "SystemChangeSchema.json");
        assertEquals("object", changes.path("type").asText());
        JsonNode properties = changes.path("properties");
        assertTrue(properties.has("oldCommits"));
        assertTrue(properties.has("newCommits"));
        assertEquals("array", properties.path("changes").path("type").asText());
        assertEquals("object", properties.path("targetSnapshot").path("type").asText());
        assertEquals("string", properties.path("baseFingerprint").path("type").asText());

        assertFalse(Files.exists(temporary.resolve("nested").resolve("schemaMicroserviceSystemSchema.json")));
        assertFalse(Files.exists(temporary.resolve("nested").resolve("schemaSystemChangeSchema.json")));
    }

    @Test
    void regeneratesSchemasWhenTheDirectoryAlreadyContainsDocumentation() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("schema"));
        Path prose = directory.resolve("schema.md");
        Files.writeString(prose, "Existing schema documentation must not prevent regeneration.");
        Path changes = directory.resolve("SystemChangeSchema.json");
        Files.writeString(changes, "{\"obsolete\":true}");

        JsonSchemaService.writeSchemas(directory + java.io.File.separator);

        assertTrue(Files.isRegularFile(directory.resolve("MicroserviceSystemSchema.json")));
        assertTrue(readSchema(directory, "SystemChangeSchema.json").path("properties").has("baseFingerprint"));
        assertTrue(Files.readString(prose).startsWith("Existing schema documentation"));
    }

    private JsonNode readSchema(Path directory, String filename) throws IOException {
        Path output = directory.resolve(filename);
        assertTrue(Files.isRegularFile(output), "Schema was not generated inside the requested directory: " + filename);
        return mapper.readTree(output.toFile());
    }
}
