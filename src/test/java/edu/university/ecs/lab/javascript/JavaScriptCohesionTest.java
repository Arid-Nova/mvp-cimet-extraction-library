package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptCohesionTest {
    private static ObjectNode fixture(String kind, String name, int count) {
        ObjectNode snapshot = snapshot(kind, name);
        for (int index = 0; index < count; index++) snapshot.withArray("endpoints").add(endpoint("e" + index, "/route" + index));
        ObjectNode graph = snapshot.putObject("architecture").put("schemaVersion", 1);
        graph.putArray("modules").addObject().put("id", "routes/users.js").put("file", "routes/users.js").put("line", 1);
        graph.putArray("dependencies"); graph.putArray("functions"); graph.putArray("calls"); graph.putArray("endpointHandlers");
        ObjectNode contracts = graph.putObject("contracts").put("schemaVersion", 1);
        contracts.putArray("operations"); contracts.putObject("summary");
        for (int index = 0; index < count; index++) contract(snapshot, "e" + index, false, false);
        return snapshot;
    }

    private static ObjectNode contract(ObjectNode snapshot, String endpoint, boolean completeInput, boolean completeOutput) {
        ObjectNode contract = null;
        for (JsonNode item : snapshot.path("architecture").path("contracts").path("operations"))
            if (item.path("endpointId").asText().equals(endpoint)) contract = (ObjectNode) item;
        if (contract == null) contract = ((ObjectNode) snapshot.path("architecture").path("contracts")).withArray("operations").addObject();
        contract.put("endpointId", endpoint).put("inputComplete", completeInput).put("outputComplete", completeOutput);
        contract.putArray("inputs"); contract.putArray("responses"); contract.putArray("limitations"); return contract;
    }

    private static void input(ObjectNode contract, String name, String type) {
        contract.withArray("inputs").addObject().put("location", "body").put("name", name).put("type", type)
                .put("evidence", "declared").put("file", "routes/users.js").put("line", 4);
    }

    private static void response(ObjectNode contract, String type, String shape, boolean complete) {
        contract.withArray("responses").addObject().put("kind", "json").put("type", type).put("shape", shape)
                .put("complete", complete).put("file", "routes/users.js").put("line", 4);
    }

    private static ObjectNode typed(ObjectNode snapshot, String endpoint, String[] inputs, String output) {
        ObjectNode contract = contract(snapshot, endpoint, true, true);
        for (int index = 0; index < inputs.length; index++) input(contract, "field" + index, inputs[index]);
        response(contract, output, output, true); return contract;
    }

    private static void function(ObjectNode snapshot, String id, int unresolved) {
        ((ObjectNode) snapshot.path("architecture")).withArray("functions").addObject().put("id", id).put("name", id)
                .put("file", "routes/users.js").put("line", 1).put("endLine", 4).put("bodyFingerprint", "a".repeat(64))
                .put("unresolvedCalls", unresolved);
    }

    private static void callEdge(ObjectNode snapshot, String source, String target) {
        ObjectNode graph = (ObjectNode) snapshot.path("architecture");
        graph.withArray("calls").addObject().put("id", "call" + graph.path("calls").size()).put("callerId", source)
                .put("targetId", target).put("file", "routes/users.js").put("line", 3);
    }

    private static void handler(ObjectNode snapshot, String endpoint, String function) {
        ((ObjectNode) snapshot.path("architecture")).withArray("endpointHandlers").addObject().put("endpointId", endpoint).put("functionId", function);
    }

    private static ObjectNode analyze(JavaScriptCohesionService service, ObjectNode... snapshots) throws IOException {
        // Summary counters are fixture evidence, not executed application code.
        for (ObjectNode snapshot : snapshots) {
            ObjectNode contracts = (ObjectNode) snapshot.path("architecture").path("contracts");
            int inputs = 0, responses = 0, withInputs = 0, withResponses = 0, completeInputs = 0, completeOutputs = 0;
            for (JsonNode operation : contracts.path("operations")) {
                inputs += operation.path("inputs").size(); responses += operation.path("responses").size();
                if (!operation.path("inputs").isEmpty()) withInputs++;
                if (!operation.path("responses").isEmpty()) withResponses++;
                if (operation.path("inputComplete").asBoolean()) completeInputs++;
                if (operation.path("outputComplete").asBoolean()) completeOutputs++;
            }
            JsonNode truncated = contracts.path("summary").path("truncatedOperations");
            ObjectNode summary = contracts.putObject("summary").put("operations", contracts.path("operations").size()).put("inputFields", inputs)
                    .put("responseObservations", responses).put("operationsWithInputs", withInputs).put("operationsWithResponses", withResponses)
                    .put("completeInputs", completeInputs).put("completeOutputs", completeOutputs);
            if (!truncated.isMissingNode()) summary.set("truncatedOperations", truncated);
        }
        return service.analyze(List.of(snapshots));
    }

    private static JsonNode component(ObjectNode snapshot) throws IOException {
        return analyze(new JavaScriptCohesionService(), snapshot).path("components").get(0);
    }

    @Test void completeTypedContractsPreserveSidcAndLomlcNumericFormulas() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 3);
        typed(snapshot, "e0", new String[]{"string", "number"}, "string");
        typed(snapshot, "e1", new String[]{"string"}, "string");
        typed(snapshot, "e2", new String[]{"boolean"}, "number");
        JsonNode component = component(snapshot);
        assertEquals(1.0 / 3, component.path("SIDC").path("value").asDouble(), 1e-12);
        // Pair costs: .25 (input Jaccard .5, equal response), 1, 1.
        assertEquals(.75, component.path("LOMLC").path("value").asDouble(), 1e-12);
        assertEquals("supported", component.path("SIDC").path("status").asText());
        assertEquals(3, component.path("LOMLC").path("coverage").path("operationCount").asInt());
        assertFalse(component.path("SIDC").path("coverage").path("sourceContractTruncationKnown").asBoolean());
        assertTrue(component.path("SIDC").path("coverage").path("sourceContractTruncatedOperations").isNull());
    }

    @Test void partialEvidenceNeverBecomesStrictWholeApplicationCohesion() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 3);
        ObjectNode first = contract(snapshot, "e0", false, false); input(first, "first", "string"); response(first, "string", "string", true);
        ObjectNode second = contract(snapshot, "e1", false, false); input(second, "second", "string"); response(second, "string", "string", true);
        ObjectNode unknown = contract(snapshot, "e2", false, false); input(unknown, "opaque", "unknown"); response(unknown, "unknown", "unknown", false);
        ((ObjectNode) snapshot.path("architecture").path("contracts").path("summary")).put("truncatedOperations", 1);
        JsonNode component = component(snapshot);
        assertTrue(component.path("SIDC").path("value").isNull()); assertTrue(component.path("LOMLC").path("value").isNull());
        JsonNode sidc = component.path("SIDC").path("availableEvidenceEstimate");
        assertEquals(1, sidc.path("value").asDouble()); assertEquals(2, sidc.path("eligibleOperations").asInt());
        assertEquals(3, sidc.path("operationCount").asInt()); assertEquals(2, sidc.path("eligibleEndpointIds").size());
        assertEquals(0, component.path("LOMLC").path("availableEvidenceEstimate").path("value").asDouble());
        assertEquals(.5, component.path("observedFieldShapeProxy").path("LOMLC").path("value").asDouble());
        for (String metric : List.of("SIDC", "LOMLC")) {
            for (JsonNode coverage : List.of(component.path(metric).path("coverage"), component.path(metric).path("availableEvidenceEstimate"),
                    component.path("observedFieldShapeProxy").path(metric))) {
                assertTrue(coverage.path("sourceContractTruncationKnown").asBoolean());
                assertEquals(1, coverage.path("sourceContractTruncatedOperations").asInt());
            }
        }
    }

    @Test void unknownEmptyAndSingletonEvidenceDoNotProducePerfectCohesion() throws Exception {
        ObjectNode missing = fixture("backend", "api", 2);
        for (String endpoint : List.of("e0", "e1")) contract(missing, endpoint, false, false);
        JsonNode component = component(missing);
        for (String metric : List.of("SIDC", "LOMLC", "SSIC")) assertTrue(component.path(metric).path("value").isNull(), metric);
        assertTrue(component.path("SIDC").path("availableEvidenceEstimate").path("value").isNull());
        assertTrue(component.path("LOMLC").path("availableEvidenceEstimate").path("value").isNull());
        ObjectNode singleton = fixture("backend", "one", 1); typed(singleton, "e0", new String[]{"string"}, "string");
        assertTrue(component(singleton).path("SIDC").path("value").isNull());
        assertTrue(component(singleton).path("LOMLC").path("value").isNull());
        assertTrue(component(fixture("backend", "empty", 0)).path("SSIC").path("value").isNull());
        assertEquals("not-applicable", component(fixture("frontend", "web", 0)).path("SIDC").path("status").asText());
    }

    @Test void provenEmptyInputsAreDistinctFromUnknownAndNullResponsesAreKnown() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 2);
        typed(snapshot, "e0", new String[]{}, "null"); typed(snapshot, "e1", new String[]{}, "null");
        JsonNode component = component(snapshot);
        assertTrue(component.path("SIDC").path("value").isNull()); // No input type denominator.
        assertEquals(0, component.path("LOMLC").path("value").asDouble()); // Explicitly closed empty input contracts.
        assertEquals(2, component.path("LOMLC").path("coverage").path("completeTypedOutputOperations").asInt());
    }

    @Test void ssicIncludesMiddlewareReachabilityCyclesAndMissingHandlerOperationsInDenominator() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 3);
        function(snapshot, "a", 0); function(snapshot, "b", 0); function(snapshot, "shared", 3);
        function(snapshot, "middleware", 1); function(snapshot, "unreachable", 99);
        handler(snapshot, "e0", "a"); handler(snapshot, "e0", "middleware");
        handler(snapshot, "e1", "b"); handler(snapshot, "e1", "middleware");
        callEdge(snapshot, "a", "shared"); callEdge(snapshot, "b", "shared"); callEdge(snapshot, "shared", "shared");
        JsonNode component = component(snapshot); JsonNode ssic = component.path("SSIC");
        assertEquals(1.0 / 3, ssic.path("value").asDouble(), 1e-12); // 2 shared / 4 unique * 2 / all 3 operations.
        assertEquals("partial", ssic.path("status").asText());
        assertEquals(3, ssic.path("coverage").path("operationCount").asInt());
        assertEquals(2, ssic.path("coverage").path("operationsWithResolvedHandlers").asInt());
        assertEquals(4, ssic.path("coverage").path("unresolvedCallsInObservedFunctionUnion").asInt());
        assertEquals(4, ssic.path("coverage").path("uniqueImplementationElements").asInt());
        assertEquals(.5, ssic.path("coverage").path("sharedElementFraction").asDouble());
        assertEquals(3, component.path("operations").get(0).path("reachableFunctionCount").asInt());
        assertEquals(0, component.path("operations").get(2).path("reachableFunctionCount").asInt());
    }

    @Test void ssicDeduplicatesSharedElementsRatherThanSummingPairMultiplicities() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 3); function(snapshot, "shared", 0);
        for (String endpoint : List.of("e0", "e1", "e2")) handler(snapshot, endpoint, "shared");
        JsonNode ssic = component(snapshot).path("SSIC");
        assertEquals(2.0 / 3, ssic.path("value").asDouble(), 1e-12);
        assertEquals(1, ssic.path("coverage").path("sharedImplementationElements").asInt());
        assertEquals(2.0 / 3, ssic.path("coverage").path("theoreticalMaximum").asDouble(), 1e-12);
    }

    @Test void groupedLomlcWeightsOperationMultiplicityWithoutEnumeratingEveryPair() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 100);
        for (int index = 0; index < 100; index++) typed(snapshot, "e" + index, new String[]{"string"}, "string");
        JavaScriptCohesionService service = new JavaScriptCohesionService(new JavaScriptCohesionService.Options(100, 100, 1));
        JsonNode metric = analyze(service, snapshot).path("components").get(0).path("LOMLC");
        assertEquals(0, metric.path("value").asDouble());
        assertEquals(4950, metric.path("availableEvidenceEstimate").path("eligibleOperationPairs").asInt());
        assertEquals(1, metric.path("availableEvidenceEstimate").path("pairComparisons").asInt());
        ObjectNode mixed = fixture("backend", "mixed", 4);
        for (int index = 0; index < 4; index++) typed(mixed, "e" + index,
                new String[]{index < 2 ? "string" : "number"}, index < 2 ? "string" : "number");
        assertEquals(2.0 / 3, component(mixed).path("LOMLC").path("value").asDouble(), 1e-12);
    }

    @Test void pairAndReachabilityBoundsProduceNullNotTruncatedMeasurements() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 3);
        typed(snapshot, "e0", new String[]{"string"}, "string"); typed(snapshot, "e1", new String[]{"number"}, "string");
        typed(snapshot, "e2", new String[]{"boolean"}, "string");
        function(snapshot, "a", 0); function(snapshot, "b", 0); handler(snapshot, "e0", "a"); callEdge(snapshot, "a", "b");
        var service = new JavaScriptCohesionService(new JavaScriptCohesionService.Options(10, 1, 1));
        JsonNode component = analyze(service, snapshot).path("components").get(0);
        assertTrue(component.path("LOMLC").path("value").isNull());
        assertTrue(component.path("LOMLC").path("boundsReached").asBoolean());
        assertFalse(component.path("LOMLC").path("comparisonComplete").asBoolean());
        assertTrue(component.path("SSIC").path("value").isNull());
        assertTrue(component.path("SSIC").path("boundsReached").asBoolean());
        assertFalse(component.path("SSIC").path("coverage").path("reachabilityCompleteForObservedGraph").asBoolean());
    }

    @Test void knownUnknownNamedFieldsRemainEligibleButUnknownTypeMarkersDoNot() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 2);
        for (String endpoint : List.of("e0", "e1")) {
            ObjectNode contract = contract(snapshot, endpoint, true, true); input(contract, "field", "string");
            response(contract, "object", "object{unknownCount:number}", true);
        }
        JsonNode proxy = component(snapshot).path("observedFieldShapeProxy").path("LOMLC");
        assertEquals(2, proxy.path("eligibleOperations").asInt()); assertEquals(0, proxy.path("value").asDouble());
        ObjectNode unknown = contract(snapshot, "e1", false, false); input(unknown, "field", "string");
        response(unknown, "object", "object{count:unknown}", false);
        assertTrue(component(snapshot).path("observedFieldShapeProxy").path("LOMLC").path("value").isNull());
    }

    @Test void wideFieldSignaturesHaveCharacterBudgetEvenWhenPairCountFits() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 2);
        ObjectNode first = contract(snapshot, "e0", false, false); input(first, "x".repeat(200) + "A", "string"); response(first, "string", "string", true);
        ObjectNode second = contract(snapshot, "e1", false, false); input(second, "x".repeat(200) + "B", "string"); response(second, "string", "string", true);
        var service = new JavaScriptCohesionService(new JavaScriptCohesionService.Options(10, 10, 1));
        JsonNode component = analyze(service, snapshot).path("components").get(0);
        JsonNode proxy = component.path("observedFieldShapeProxy").path("LOMLC");
        assertEquals(1, proxy.path("pairComparisonsRequired").asInt()); assertTrue(proxy.path("boundsReached").asBoolean());
        assertTrue(proxy.path("value").isNull());
        assertEquals(0, component.path("LOMLC").path("availableEvidenceEstimate").path("value").asDouble());
    }

    @Test void operationLimitAndComponentIdentitiesAreEnforced() throws Exception {
        ObjectNode snapshot = fixture("backend", "api", 3);
        var service = new JavaScriptCohesionService(new JavaScriptCohesionService.Options(2, 10, 10));
        assertThrows(IOException.class, () -> analyze(service, snapshot));
        assertThrows(IOException.class, () -> analyze(new JavaScriptCohesionService(), snapshot, snapshot));
        assertThrows(IllegalArgumentException.class, () -> new JavaScriptCohesionService.Options(0, 1, 1));
        ObjectNode other = fixture("backend", "other", 0); ObjectNode frontend = fixture("frontend", "web", 0);
        assertEquals(analyze(new JavaScriptCohesionService(), snapshot, other, frontend),
                analyze(new JavaScriptCohesionService(), frontend, snapshot, other));
    }
}
