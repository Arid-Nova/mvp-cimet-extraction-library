package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptSemanticTest {
    private final JavaScriptSemanticService service = new JavaScriptSemanticService();

    private static ObjectNode fixture(String name) {
        ObjectNode snapshot = snapshot("backend", name);
        snapshot.withArray("endpoints").add(endpoint("endpoint", "/resource"));
        ObjectNode graph = snapshot.putObject("architecture").put("schemaVersion", 1);
        graph.putArray("modules").addObject().put("id", "routes/users.js").put("file", "routes/users.js").put("line", 1);
        graph.putArray("dependencies"); graph.putArray("functions"); graph.putArray("calls"); graph.putArray("endpointHandlers");
        for (String id : List.of("handler", "query")) graph.withArray("functions").addObject().put("id", id).put("name", id)
                .put("file", "routes/users.js").put("line", 1).put("endLine", 9).put("bodyFingerprint", "a".repeat(64)).put("unresolvedCalls", 0);
        graph.withArray("calls").addObject().put("id", "call").put("callerId", "handler").put("targetId", "query").put("file", "routes/users.js").put("line", 3);
        graph.withArray("endpointHandlers").addObject().put("endpointId", "endpoint").put("functionId", "handler");
        ObjectNode semantics = graph.putObject("semanticEvidence").put("schemaVersion", 1);
        for (String field : List.of("dataAccess", "entities", "healthEndpoints", "proxies", "resilience")) semantics.putArray(field);
        semantics.putObject("summary").put("inspectionComplete", true).put("boundsReached", false)
                .put("unresolvedDataAccessCandidates", 0).put("unregisteredProxyCreations", 0);
        semantics.putArray("limitations"); return snapshot;
    }
    private static ObjectNode semantic(ObjectNode snapshot) { return (ObjectNode) snapshot.path("architecture").path("semanticEvidence"); }
    private static ObjectNode record(String id) {
        return JavaScriptExtractionService.JSON.createObjectNode().put("id", id).put("file", "routes/users.js").put("line", 3)
                .put("endLine", 3).put("functionId", "query").put("fingerprint", "a".repeat(64));
    }
    private static ObjectNode query(ObjectNode snapshot, String fingerprint) {
        ObjectNode query = record("query-record").put("kind", "sql").put("library", "mysql2").put("operation", "select")
                .put("shape", "select User email").put("fingerprint", fingerprint.repeat(64)).put("complete", true);
        query.putArray("tables").add("User"); query.putArray("columns").add("email");
        semantic(snapshot).withArray("dataAccess").add(query); return query;
    }
    private static ObjectNode entity(ObjectNode snapshot, String name, String field, String type, String fingerprint) {
        ObjectNode entity = record("entity-" + name).putNull("functionId").put("library", "sequelize").put("name", name)
                .put("fingerprint", fingerprint.repeat(64)).put("complete", true);
        entity.putArray("fields").addObject().put("name", field).put("type", type);
        semantic(snapshot).withArray("entities").add(entity); return entity;
    }
    private static void guard(ObjectNode snapshot, String kind, String operation) {
        semantic(snapshot).withArray("resilience").add(record("guard-" + kind).put("functionId", "handler")
                .put("library", "synthetic-guard").put("kind", kind).put("operation", operation));
    }
    private ObjectNode analyze(ObjectNode... snapshots) throws IOException { return service.analyze(List.of(snapshots)); }
    private static JsonNode component(JsonNode report) { return report.path("components").get(0); }
    private static long findings(JsonNode report, String kind) {
        long count = 0; for (JsonNode finding : report.path("findings")) if (finding.path("kind").asText().equals(kind)) count++; return count;
    }
    private static ObjectNode full(ObjectNode... snapshots) {
        ObjectNode full = JavaScriptExtractionService.JSON.createObjectNode();
        full.putArray("functions"); full.putArray("functionCalls"); full.putArray("endpointHandlers");
        full.putArray("components"); full.putObject("http").putArray("endpoints");
        for (ObjectNode snapshot : snapshots) {
            String component = "backend:" + snapshot.path("component").path("name").asText(); JsonNode graph = snapshot.path("architecture");
            full.withArray("components").addObject().put("id", component).put("kind", "backend");
            for (JsonNode endpoint : snapshot.path("endpoints")) ((ObjectNode) full.path("http")).withArray("endpoints").addObject()
                    .put("id", JavaScriptSemanticService.qualified(component, endpoint.path("id").asText())).put("component", component);
            for (JsonNode function : graph.path("functions")) full.withArray("functions").addObject()
                    .put("id", JavaScriptSemanticService.qualified(component, function.path("id").asText())).put("component", component);
            for (JsonNode call : graph.path("calls")) full.withArray("functionCalls").addObject()
                    .put("callerId", JavaScriptSemanticService.qualified(component, call.path("callerId").asText()))
                    .put("targetId", JavaScriptSemanticService.qualified(component, call.path("targetId").asText()));
            for (JsonNode handler : graph.path("endpointHandlers")) full.withArray("endpointHandlers").addObject()
                    .put("endpointId", JavaScriptSemanticService.qualified(component, handler.path("endpointId").asText()))
                    .put("functionId", JavaScriptSemanticService.qualified(component, handler.path("functionId").asText()));
        }
        return full;
    }

    @Test void healthNameOnlyIsCandidateAndMissingGatewayIsNotDeploymentAbsence() throws Exception {
        ObjectNode snapshot = fixture("api");
        ObjectNode candidate = semantic(snapshot).withArray("healthEndpoints").addObject().put("endpointId", "endpoint")
                .put("classification", "health-like-route").put("basis", "path-name"); candidate.putArray("functionIds").add("handler");
        JsonNode component = component(analyze(snapshot));
        assertEquals(1, component.path("health").path("candidateCount").asInt());
        assertFalse(component.path("healthEndpoints").get(0).path("healthBehaviorVerified").asBoolean());
        assertTrue(component.path("health").path("deploymentHealthcheckAbsent").isNull());
        assertTrue(component.path("proxyGateway").path("deploymentGatewayAbsent").isNull());
    }

    @Test void importOnlyProxyEvidenceDoesNotInventMountedGateway() throws Exception {
        ObjectNode snapshot = fixture("api"); semantic(snapshot).withObject("summary").put("unregisteredProxyCreations", 1);
        ((ObjectNode) snapshot.path("architecture")).withArray("dependencies").addObject().put("id", "proxy-import")
                .put("source", "routes/users.js").putNull("target").put("line", 1).put("kind", "import")
                .put("resolution", "external").put("typeOnly", false);
        assertEquals(0, component(analyze(snapshot)).path("proxyGateway").path("sourceProxyObservationCount").asInt());
        semantic(snapshot).withArray("proxies").add(record("proxy").put("kind", "middleware").put("library", "http-proxy-middleware")
                .put("operation", "mount").put("target", "literal-redacted"));
        JsonNode mounted = component(analyze(snapshot)).path("proxyGateway");
        assertEquals(1, mounted.path("sourceProxyObservationCount").asInt()); assertTrue(mounted.path("deploymentGatewayAbsent").isNull());
    }

    @Test void wrongCutsRequiresInspectedEvidenceAndAliasesDoNotDuplicateFindings() throws Exception {
        ObjectNode missing = fixture("api"); ((ObjectNode) missing.path("architecture")).remove("semanticEvidence");
        ObjectNode absentReport = analyze(missing);
        assertEquals("unsupported", component(absentReport).path("wrongCuts").path("status").asText());
        assertEquals(0, absentReport.path("findings").size());
        ObjectNode inspected = fixture("api"); ObjectNode report = analyze(inspected);
        assertEquals(1, findings(report, "no-recognized-data-access-role")); assertEquals(1, report.path("findings").size());
        assertEquals("WrongCuts", report.path("aliases").path("AR21").asText());
        semantic(inspected).withObject("summary").put("unresolvedDataAccessCandidates", 1);
        assertTrue(component(analyze(inspected)).path("wrongCuts").path("noRecognizedDataAccessRole").isNull());
        query(inspected, "a"); // Positive evidence settles the negated predicate despite unresolved observations.
        assertFalse(component(analyze(inspected)).path("wrongCuts").path("noRecognizedDataAccessRole").asBoolean());
    }

    @Test void wobblyOnlyUsesAllFourActivatedKindsNotMissingGuardOrConstructorPresence() throws Exception {
        ObjectNode snapshot = fixture("api"); query(snapshot, "a");
        for (String kind : List.of("circuit-breaker", "retry", "rate-limit", "concurrency-limit")) guard(snapshot, kind, "construct");
        assertEquals(0, component(analyze(snapshot)).path("resilience").path("legacyAnalogyCount").asInt());
        for (JsonNode guard : semantic(snapshot).path("resilience")) ((ObjectNode) guard).put("operation", "invoke");
        ObjectNode report = analyze(snapshot);
        assertEquals(1, findings(report, "all-four-activated-resilience-kinds-analogy"));
        assertFalse(component(report).path("resilience").path("legacyAnnotationResultEquivalent").asBoolean());
        String[] activated = {"fire", "attempt", "consume", "submit"};
        for (int i = 0; i < activated.length; i++) ((ObjectNode) semantic(snapshot).path("resilience").get(i)).put("operation", activated[i]);
        assertEquals(1, findings(analyze(snapshot), "all-four-activated-resilience-kinds-analogy"));
        semantic(snapshot).withArray("resilience").remove(3);
        assertEquals(0, findings(analyze(snapshot), "all-four-activated-resilience-kinds-analogy"));
    }

    @Test void structuralQueryChangeReachesEndpointThroughQualifiedLocalFunctionGraph() throws Exception {
        ObjectNode before = fixture("api"), after = fixture("api"); query(before, "a"); query(after, "b");
        ObjectNode delta = service.compare(analyze(before), analyze(after), full(before), full(after));
        assertEquals(1, delta.path("changedDataAccess").size());
        assertEquals(2, delta.path("AR7").path("potentiallyAffectedEndpointObservations").asInt());
        assertTrue(delta.path("AR7").path("traversalComplete").asBoolean());
        assertEquals(JavaScriptSemanticService.qualified("backend:api", "endpoint"),
                delta.path("AR7").path("potentiallyAffectedEndpoints").get(0).path("endpointId").asText());
        assertEquals(2, delta.path("AR7").path("potentiallyAffectedEndpoints").get(0).path("functionPath").size());
        assertEquals("not-applicable", delta.path("AR22").path("status").asText());
        assertEquals(0, service.compare(analyze(before), analyze(before), full(before), full(before)).path("changedDataAccess").size());
        ((ObjectNode) semantic(after).path("dataAccess").get(0)).put("complete", false);
        JsonNode incomplete = service.compare(analyze(before), analyze(after), full(before), full(after)).path("changedDataAccess").get(0);
        assertEquals("incomplete-structural-evidence-change", incomplete.path("classification").asText());
        assertTrue(incomplete.path("beforeEvidenceComplete").asBoolean()); assertFalse(incomplete.path("afterEvidenceComplete").asBoolean());
    }

    @Test void schemaChangeUsesExplicitEntityTableAssociationAndUnrelatedNamesDoNotMatch() throws Exception {
        ObjectNode before = fixture("api"), after = fixture("api"); query(before, "a"); query(after, "a");
        entity(before, "User", "email", "string", "a"); entity(after, "User", "email", "number", "b");
        ObjectNode delta = service.compare(analyze(before), analyze(after), full(before), full(after));
        assertEquals(0, delta.path("changedDataAccess").size()); assertEquals(1, delta.path("changedEntities").size());
        assertEquals(2, delta.path("AR7").path("potentiallyAffectedEndpointObservations").asInt());
        for (ObjectNode snapshot : List.of(before, after)) ((ObjectNode) semantic(snapshot).path("dataAccess").get(0)).putArray("tables").add("Order");
        assertEquals(0, service.compare(analyze(before), analyze(after), full(before), full(after)).path("AR7").path("potentiallyAffectedEndpointObservations").asInt());
    }

    @Test void ar22RequiresHistoricalSameEntityAndFieldWithExplicitTypesAcrossBackends() throws Exception {
        ObjectNode a0 = fixture("a"), a1 = fixture("a"), b0 = fixture("b"), b1 = fixture("b");
        entity(a0, "User", "email", "string", "a"); entity(a1, "User", "email", "number", "b");
        entity(b0, "User", "email", "string", "a"); entity(b1, "User", "email", "string", "a");
        ObjectNode delta = service.compare(analyze(a0, b0), analyze(a1, b1), full(a0, b0), full(a1, b1));
        assertEquals(1, delta.path("AR22").path("candidateCount").asInt());
        assertEquals("email", delta.path("AR22").path("candidates").get(0).path("field").asText());
        for (ObjectNode snapshot : List.of(b0, b1)) ((ObjectNode) semantic(snapshot).path("entities").get(0)).put("name", "DifferentEntity");
        assertEquals(0, service.compare(analyze(a0, b0), analyze(a1, b1), full(a0, b0), full(a1, b1)).path("AR22").path("candidateCount").asInt());
    }

    @Test void missingOrBoundedSemanticHistoryIsExcludedNotReportedAsRemoval() throws Exception {
        ObjectNode before = fixture("api"), after = fixture("api"); query(before, "a"); query(after, "b");
        ((ObjectNode) before.path("architecture")).remove("semanticEvidence");
        ObjectNode delta = service.compare(analyze(before), analyze(after), full(before), full(after));
        assertEquals(1, delta.path("excludedComponents").size()); assertEquals(0, delta.path("changedDataAccess").size());
        assertEquals("unsupported", delta.path("AR7").path("status").asText());
        assertTrue(delta.path("AR7").path("potentiallyAffectedEndpointObservations").isNull());
        assertFalse(delta.path("comparisonCompleteForInspectedComponents").asBoolean());
        ObjectNode bounded = fixture("api"); semantic(bounded).withObject("summary").put("inspectionComplete", false).put("boundsReached", true);
        assertEquals(1, service.compare(analyze(after), analyze(bounded), full(after), full(bounded)).path("excludedComponents").size());
    }

    @Test void traversalAndEntityComparisonBoundsRemainExplicit() throws Exception {
        ObjectNode before = fixture("api"), after = fixture("api"); query(before, "a"); query(after, "b");
        var small = new JavaScriptSemanticService(new JavaScriptSemanticService.Options(100, 1, 1, 1));
        JsonNode delta = small.compare(small.analyze(List.of(before)), small.analyze(List.of(after)), full(before), full(after));
        assertFalse(delta.path("AR7").path("traversalComplete").asBoolean());
        ObjectNode b0 = fixture("b"), b1 = fixture("b");
        entity(before, "User", "email", "string", "a"); entity(after, "User", "email", "number", "b");
        entity(b0, "User", "email", "string", "a"); entity(b1, "User", "email", "string", "a");
        delta = small.compare(small.analyze(List.of(before, b0)), small.analyze(List.of(after, b1)), full(before, b0), full(after, b1));
        assertTrue(delta.path("AR22").path("candidateCount").isNull()); assertTrue(delta.path("AR22").path("boundsReached").asBoolean());
    }

    @Test void deterministicOrderingAndInvalidBoundsAreRejected() throws Exception {
        ObjectNode a = fixture("a"), b = fixture("b"); query(a, "a");
        assertEquals(analyze(a, b), analyze(b, a));
        assertThrows(IOException.class, () -> analyze(a, a));
        assertThrows(IllegalArgumentException.class, () -> new JavaScriptSemanticService.Options(0, 1, 1, 1));
        ObjectNode invalid = analyze(a); ((ObjectNode) invalid.path("components").get(0).path("dataAccess").get(0)).remove("fingerprint");
        assertThrows(IOException.class, () -> service.compare(invalid, analyze(a), full(a), full(a)));
    }

    @Test void savedSemanticValidationRejectsDanglingFunctionsEndpointsAndMissingAssociationArrays() throws Exception {
        ObjectNode snapshot = fixture("api"); query(snapshot, "a");
        ObjectNode candidate = semantic(snapshot).withArray("healthEndpoints").addObject().put("endpointId", "endpoint")
                .put("classification", "health-like-route").put("basis", "path-name"); candidate.putArray("functionIds").add("handler");
        ObjectNode good = analyze(snapshot); service.validateAnalysis(good, full(snapshot));
        JsonNode saved = JavaScriptExtractionService.JSON.readTree(JavaScriptExtractionService.JSON.writeValueAsBytes(good));
        service.validateAnalysis(saved, full(snapshot));
        assertTrue(service.compare(saved, good, full(snapshot), full(snapshot)).path("comparisonCompleteForInspectedComponents").asBoolean());
        ObjectNode invalid = good.deepCopy(); ((ObjectNode) component(invalid).path("dataAccess").get(0)).put("functionId", "missing");
        assertThrows(IOException.class, () -> service.validateAnalysis(invalid, full(snapshot)));
        ObjectNode wrongEndpoint = good.deepCopy(); ((ObjectNode) component(wrongEndpoint).path("healthEndpoints").get(0)).put("endpointId", "missing");
        assertThrows(IOException.class, () -> service.validateAnalysis(wrongEndpoint, full(snapshot)));
        ObjectNode missingTables = good.deepCopy(); ((ObjectNode) component(missingTables).path("dataAccess").get(0)).remove("tables");
        assertThrows(IOException.class, () -> service.validateAnalysis(missingTables, full(snapshot)));
    }
}
