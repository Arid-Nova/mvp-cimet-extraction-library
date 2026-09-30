package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptArchitectureBoundaryTest {
    private ObjectNode graphSnapshot() {
        ObjectNode snapshot = snapshot("backend", "api");
        snapshot.withArray("endpoints").add(endpoint("route", "/orders"));
        ObjectNode architecture = snapshot.putObject("architecture");
        architecture.put("schemaVersion", 1);
        architecture.putArray("modules").addObject().put("id", "app.js").put("file", "app.js").put("line", 1);
        architecture.putArray("dependencies");
        architecture.putArray("functions").addObject().put("id", "handler").put("file", "app.js")
                .put("line", 1).put("endLine", 4).put("name", "orders").put("bodyFingerprint", "a".repeat(64));
        architecture.putArray("calls");
        architecture.putArray("endpointHandlers").addObject().put("endpointId", "route").put("functionId", "handler");
        return snapshot;
    }

    @Test void optionalEvidencePreservesOlderSnapshotsAndAcceptsWellFormedGraph() {
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot("backend", "old-api")));
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(graphSnapshot()));
    }

    @Test void rejectsPathsOutsideSnapshotAndDanglingGraphEdges() {
        ObjectNode snapshot = graphSnapshot();
        ((ObjectNode) snapshot.path("architecture").path("modules").get(0)).put("file", "../private.js");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        ObjectNode dangling = graphSnapshot();
        dangling.withObject("architecture").withArray("calls").addObject().put("id", "call")
                .put("file", "app.js").put("line", 3).put("callerId", "handler").put("targetId", "missing");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(dangling));
    }

    @Test void unresolvedTargetsCannotMasqueradeAsGraphEdgesAndTypeOnlyMustBeExplicit() {
        ObjectNode snapshot = graphSnapshot();
        ObjectNode dependency = snapshot.withObject("architecture").withArray("dependencies").addObject()
                .put("id", "dependency").put("source", "app.js").put("target", "app.js")
                .put("line", 2).put("kind", "require").put("resolution", "unresolved").put("typeOnly", false);
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        dependency.putNull("target");
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
        dependency.remove("typeOnly");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
    }

    @Test void rejectsDuplicateNodesAndHandlersWithoutAnEndpoint() {
        ObjectNode duplicate = graphSnapshot();
        duplicate.withObject("architecture").withArray("functions")
                .add(duplicate.path("architecture").path("functions").get(0).deepCopy());
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(duplicate));
        ObjectNode missing = graphSnapshot();
        ((ObjectNode) missing.path("architecture").path("endpointHandlers").get(0)).put("endpointId", "absent");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(missing));
    }
}
