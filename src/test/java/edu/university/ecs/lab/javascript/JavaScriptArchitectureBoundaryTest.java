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

    @Test void contractEvidenceMustCoverDeclaredEndpointsAndUseSourceMetadata() {
        ObjectNode snapshot = graphSnapshot();
        ObjectNode contracts = snapshot.withObject("architecture").putObject("contracts").put("schemaVersion", 1);
        var operations = contracts.putArray("operations");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        ObjectNode operation = operations.addObject().put("endpointId", "route").put("inputComplete", false).put("outputComplete", false);
        operation.putArray("inputs"); operation.putArray("responses"); operation.putArray("limitations");
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
        ObjectNode input = operation.withArray("inputs").addObject().put("location", "body").put("name", "count")
                .put("type", "number").put("evidence", "coercion").put("file", "app.js").put("line", 2);
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
        input.put("file", "../outside.js");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        input.put("file", "app.js").put("type", "invented-type");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        input.put("type", "number");
        contracts.putObject("summary").put("truncatedOperations", 2);
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        contracts.withObject("summary").put("truncatedOperations", 0);
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
    }

    @Test void unresolvedFunctionCallCountsMustBeNonnegativeIntegers() {
        ObjectNode snapshot = graphSnapshot();
        ObjectNode function = (ObjectNode) snapshot.path("architecture").path("functions").get(0);
        function.put("unresolvedCalls", -1);
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        function.put("unresolvedCalls", 2);
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
    }

    private ObjectNode semanticSnapshot() {
        ObjectNode snapshot = graphSnapshot();
        ObjectNode semantic = snapshot.withObject("architecture").putObject("semanticEvidence").put("schemaVersion", 1);
        for (String group : java.util.List.of("dataAccess", "entities", "proxies", "resilience", "healthEndpoints", "limitations")) semantic.putArray(group);
        semantic.putObject("summary").put("inspectionComplete", true).put("boundsReached", false)
                .put("unresolvedDataAccessCandidates", 0);
        semantic.withArray("dataAccess").addObject().put("id", "query").put("file", "app.js").put("line", 2).put("endLine", 3)
                .put("functionId", "handler").put("kind", "sql").put("library", "mysql2").put("operation", "select")
                .put("shape", "select users.id from users").put("fingerprint", "a".repeat(64)).put("complete", true)
                .putArray("tables").add("users");
        ((ObjectNode) semantic.path("dataAccess").get(0)).putArray("columns").add("id");
        return snapshot;
    }

    @Test void semanticRecordsRequireKnownProvenanceAndSafeStructuralTokens() {
        ObjectNode snapshot = semanticSnapshot();
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
        ObjectNode query = (ObjectNode) snapshot.path("architecture").path("semanticEvidence").path("dataAccess").get(0);
        query.put("functionId", "absent");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        query.put("functionId", "handler").put("file", "../outside.js");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        query.put("file", "app.js").put("fingerprint", "invalid");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        query.put("fingerprint", "b".repeat(64)).withArray("tables").add("users; DROP TABLE users");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
    }

    @Test void semanticCoverageCannotClaimCompleteTruncationOrInventedHealthEndpoints() {
        ObjectNode snapshot = semanticSnapshot();
        ObjectNode semantic = snapshot.withObject("architecture").withObject("semanticEvidence");
        semantic.withObject("summary").put("boundsReached", true);
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        semantic.withObject("summary").put("inspectionComplete", false);
        assertDoesNotThrow(() -> JavaScriptExtractionService.validateSnapshot(snapshot));
        semantic.withObject("summary").put("unresolvedDataAccessCandidates", -1);
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
        semantic.withObject("summary").put("unresolvedDataAccessCandidates", 0);
        semantic.withArray("healthEndpoints").addObject().put("endpointId", "missing")
                .put("classification", "health-like-route").put("basis", "path-name").putArray("functionIds").add("handler");
        assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
    }
}
