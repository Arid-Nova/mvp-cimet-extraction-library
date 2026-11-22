package edu.university.ecs.lab.common.services;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentMultiRepositorySchemaTest {
  @TempDir Path temporary;
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void checkedInSchemasDescribeTheCurrentMultiRepositoryModels() throws Exception {
    JsonSchemaService.writeSchemas(temporary.toString());
    for (String filename :
        new String[] {"MicroserviceSystemSchema.json", "SystemChangeSchema.json"}) {
      assertEquals(
          json.readTree(Path.of(".docs", "schema", filename).toFile()),
          json.readTree(temporary.resolve(filename).toFile()),
          "Regenerate schema documentation from the current model: " + filename);
    }
    JsonNode system = json.readTree(temporary.resolve("MicroserviceSystemSchema.json").toFile());
    JsonNode root = system.path("properties");
    assertTrue(root.has("microservices"));
    assertTrue(root.has("orphans"));
    assertFalse(root.has("repositoryURL"));
    assertFalse(root.has("commitID"));
    JsonNode service = root.path("microservices").path("items").path("properties");
    assertEquals("string", service.path("repositoryURL").path("type").asText());
    assertEquals("string", service.path("commitID").path("type").asText());
    JsonNode delta =
        json.readTree(temporary.resolve("SystemChangeSchema.json").toFile()).path("properties");
    assertTrue(delta.has("oldCommits"));
    assertTrue(delta.has("newCommits"));
    assertTrue(delta.has("targetSnapshot"));
    assertTrue(delta.has("baseFingerprint"));
    assertFalse(delta.has("oldCommit"));
    assertFalse(delta.has("newCommit"));
  }

  @Test
  void currentWriterAndReaderPreserveIndependentRepositoryRevisions() throws Exception {
    MicroserviceSystem system =
        new MicroserviceSystem("two-repositories", new LinkedHashSet<>(), new LinkedHashSet<>());
    system.setMetadata(new JSONObject().put("languagePolicy", "per-file-registry"));
    Microservice orders =
        new Microservice(
            system,
            "orders",
            "https://example.invalid/orders.git",
            "a".repeat(40),
            Path.of("services", "orders"));
    Microservice users =
        new Microservice(
            system,
            "users",
            "https://example.invalid/users.git",
            "b".repeat(40),
            Path.of("services", "users"));
    system.getMicroservices().add(orders);
    system.getMicroservices().add(users);
    Path output = temporary.resolve("current-ir.json");
    JsonReadWriteUtils.writeToJSON(output, system);
    JsonNode wire = json.readTree(output.toFile());
    assertEquals(2, wire.path("microservices").size());
    assertFalse(wire.has("commitID"));
    assertFalse(wire.has("repositoryURL"));
    assertEquals("per-file-registry", wire.path("metadata").path("languagePolicy").asText());
    MicroserviceSystem restored = IRExtractionService.read(output);
    Map<String, String> revisions =
        restored.getMicroservices().stream()
            .collect(Collectors.toMap(Microservice::getRepositoryURL, Microservice::getCommitID));
    assertEquals(
        Map.of(
            orders.getRepositoryURL(),
            orders.getCommitID(),
            users.getRepositoryURL(),
            users.getCommitID()),
        revisions);
    assertTrue(
        restored.getMicroservices().stream()
            .allMatch(service -> service.getParent().orElseThrow() == restored));
  }
}
