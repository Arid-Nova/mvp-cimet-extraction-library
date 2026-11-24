package edu.university.ecs.lab.javascript;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.models.ir.JSModule;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class JavaScriptSnapshotIndexTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void indexedProjectionMatchesThePreIndexGoldenOutputIncludingEvidenceOrdering() throws Exception {
    JavaScriptSnapshotIndex index = JavaScriptSnapshotIndex.create(fixture());
    JsonNode expected = read("projection-before-index.json");
    for (String file : List.of("a.js", "b.js")) {
      JSModule projected =
          JavaScriptIRConverter.projectModule(index, file, service(), Path.of("/" + file));
      JsonNode actual =
          mapper.readTree(JsonReadWriteUtils.setupObjectWriter().writeValueAsString(projected));
      assertEquals(expected.path(file), actual, file);
    }
    assertEquals("PARSE_1005", index.exclusionCode("broken.js"));
    assertEquals("FILE_TOO_LARGE", index.exclusionCode("huge.js"));
    assertNull(index.exclusionCode("a.js"));
    assertFalse(index.includesModule("types.d.ts"));
    assertTrue(index.includesModule("a.js"));
  }

  @Test
  void ownershipOfTheSnapshotPreventsCallerMutationFromCorruptingCachedEvidence() throws Exception {
    ObjectNode source = fixture();
    JavaScriptSnapshotIndex index = JavaScriptSnapshotIndex.create(source);
    source.withArray("endpoints").removeAll();
    index.snapshotCopy().withObject("/architecture").withArray("modules").removeAll();
    assertTrue(index.includesModule("a.js"));
    assertEquals(2, index.endpoints("a.js").size());
    assertEquals(2, index.handlers("a.js").size());
    assertEquals(
        1,
        index.semantics("dataAccess", "a.js").size(),
        "File and endpoint membership must not duplicate a record");
    assertEquals(1, index.semantics("proxies", "a.js").size());
    assertEquals(1, index.semantics("proxies", "b.js").size());
  }

  @Test
  void anIndexForAnotherRevisionCannotLeakOldMembershipOrParseDiagnostics() throws Exception {
    JavaScriptSnapshotIndex before = JavaScriptSnapshotIndex.create(fixture());
    ObjectNode after = mapper.createObjectNode();
    after.putArray("diagnostics");
    after.putObject("architecture").putArray("modules").addObject().put("file", "new.ts");
    JavaScriptSnapshotIndex next = JavaScriptSnapshotIndex.create(after);
    assertTrue(before.includesModule("a.js"));
    assertFalse(next.includesModule("a.js"));
    assertTrue(next.includesModule("new.ts"));
    assertNull(next.exclusionCode("broken.js"));
    assertTrue(next.endpoints("a.js").isEmpty());
  }

  @Test
  void fileLookupDoesNotSelectOtherModulesFromALargeSnapshot() throws Exception {
    ObjectNode source = mapper.createObjectNode();
    var modules = source.putObject("architecture").putArray("modules");
    var endpoints = source.putArray("endpoints");
    for (int i = 0; i < 2000; i++) {
      modules.addObject().put("file", "source" + i + ".js");
      endpoints.addObject().put("id", "endpoint" + i).put("file", "source" + i + ".js");
    }
    JavaScriptSnapshotIndex index = JavaScriptSnapshotIndex.create(source);
    for (int i = 0; i < 2000; i++) {
      String file = "source" + i + ".js";
      assertTrue(index.includesModule(file));
      assertEquals("endpoint" + i, index.endpoints(file).getFirst().path("id").asText());
    }
  }

  private ObjectNode fixture() throws Exception {
    return (ObjectNode) read("projection-snapshot.json");
  }

  private JsonNode read(String filename) throws Exception {
    try (var stream = getClass().getResourceAsStream("/javascript/" + filename)) {
      assertNotNull(stream);
      return mapper.readTree(stream);
    }
  }

  private Microservice service() {
    return new Microservice(
        null, "api", "https://github.com/example/api.git", "a".repeat(40), Path.of("api"));
  }
}
