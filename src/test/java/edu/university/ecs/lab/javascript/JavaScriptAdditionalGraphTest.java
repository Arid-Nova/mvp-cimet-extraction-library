package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JavaScriptAdditionalGraphTest {
    private static ObjectNode object() { return JavaScriptExtractionService.JSON.createObjectNode(); }
    private static ObjectNode analysis() {
        ObjectNode input = object().put("schemaVersion", 1).put("analysis", "static-javascript-architecture");
        input.putArray("functions"); input.putArray("functionCalls"); input.putArray("endpointHandlers");
        ArrayNode components = input.putArray("components");
        components.addObject().put("id", "backend:api").put("kind", "backend").put("name", "api");
        components.addObject().put("id", "frontend:web").put("kind", "frontend").put("name", "web");
        ArrayNode coverage = input.putArray("sourceCoverage");
        for (String id : List.of("backend:api", "frontend:web"))
            coverage.addObject().put("component", id).put("unresolvedCallSites", 0).put("resolvedTopLevelCallsOmitted", 0);
        ObjectNode graphs = input.putObject("graphs");
        ObjectNode modules = graph("file-modules", "supported", "a", "b", "c");
        modules.putObject("coverage").put("snapshotsWithArchitecture", 2).put("snapshotsTotal", 2);
        graphs.set("modules", modules);
        ObjectNode componentGraph = graph("declared-applications-and-clients", "partial", "backend:api", "frontend:web");
        componentGraph.putObject("coverage").put("matchingComplete", true); graphs.set("components", componentGraph);
        ObjectNode backends = graph("declared-backend-applications", "not-applicable", "backend:api");
        backends.putObject("coverage").put("matchingComplete", true); graphs.set("backendApplications", backends);
        return input;
    }
    private static ObjectNode graph(String scope, String status, String... ids) {
        ObjectNode graph = object().put("scope", scope).put("status", status);
        ArrayNode nodes = graph.putArray("nodes"); graph.putArray("edges");
        for (String id : ids) nodes.addObject().put("id", id).put("file", id + ".js").put("line", 1);
        return graph;
    }
    private static ObjectNode graph(ObjectNode analysis, String key) { return (ObjectNode) analysis.path("graphs").path(key); }
    private static ObjectNode edge(ObjectNode graph, String id, String source, String target, int weight) {
        ObjectNode edge = graph.withArray("edges").addObject().put("id", id).put("source", source).put("target", target).put("observations", weight);
        edge.putObject("evidence").put("file", source + ".js").put("line", 3);
        return edge;
    }
    private static ObjectNode function(ObjectNode input, String id) { return function(input, id, "frontend:web"); }
    private static ObjectNode function(ObjectNode input, String id, String component) {
        return input.withArray("functions").addObject().put("id", id).put("component", component)
                .put("file", id + ".js").put("name", id).put("line", 1).put("endLine", 4);
    }
    private static ObjectNode call(ObjectNode input, String id, String caller, String target) {
        return input.withArray("functionCalls").addObject().put("id", id).put("component", "frontend:web")
                .put("file", caller + ".js").put("line", 2).put("callerId", caller).put("targetId", target);
    }
    private static JsonNode analyze(ObjectNode input) throws IOException { return new JavaScriptAdditionalGraphService().analyze(input); }
    private static List<JsonNode> findings(JsonNode result, String kind) {
        List<JsonNode> output = new ArrayList<>();
        for (JsonNode finding : result.path("findings")) if (finding.path("kind").asText().equals(kind)) output.add(finding);
        return output;
    }

    @Test void weightedGreedyAndHubUseObservationSumsAndOriginalInclusiveThresholds() throws Exception {
        ObjectNode input = analysis(); ObjectNode modules = graph(input, "modules");
        edge(modules, "ab-1", "a", "b", 4); edge(modules, "ab-2", "a", "b", 2); edge(modules, "bc", "b", "c", 4);
        JsonNode result = analyze(input), weighted = result.path("weighted").path("modules");
        assertEquals(1, weighted.path("counts").path("greedy").asInt());
        assertEquals(1, weighted.path("counts").path("hub").asInt());
        assertEquals(2, weighted.path("counts").path("AR20").asInt());
        assertEquals(10, weighted.path("totalObservationWeight").asInt());
        assertEquals(6, findings(result, "weighted-greedy").getFirst().path("score").asLong());
        JsonNode hub = findings(result, "weighted-hub").getFirst();
        assertEquals("b", hub.path("subjects").get(0).asText());
        assertEquals("file-modules", hub.path("scope").asText());
        assertEquals(2, hub.path("evidence").path("edgeSamples").size());
        assertEquals("a.js", hub.path("evidence").path("edgeSamples").get(0).path("sourceEvidence").path("file").asText());
        assertEquals(4, findings(result, "AR20-weighted-hub").getFirst().path("threshold").asInt());
    }

    @Test void projectionExcludesSelfLoopsWhileRetainingSeparateRecursionDetection() throws Exception {
        ObjectNode input = analysis(); edge(graph(input, "modules"), "loop", "a", "a", 500);
        function(input, "f"); call(input, "self", "f", "f");
        JsonNode result = analyze(input), weighted = result.path("weighted").path("modules"), functionGraph = result.path("functionGraph");
        assertEquals(0, weighted.path("counts").path("greedy").asInt());
        assertEquals(500, weighted.path("excludedSelfLoopWeight").asInt());
        assertEquals(1, functionGraph.path("metrics").path("cyclicSccCount").asInt());
        assertEquals(1, functionGraph.path("metrics").path("recursiveFunctions").asInt());
        assertEquals(2, functionGraph.path("cycleWitnesses").get(0).path("nodes").size());
        assertTrue(functionGraph.path("cyclicComponents").get(0).path("directRecursionOnly").asBoolean());
        assertEquals(0, functionGraph.path("chainWitnessCount").asInt());
    }

    @Test void scopesDoNotPromoteFrontendOrModulesToBackendServices() throws Exception {
        ObjectNode input = analysis(); edge(graph(input, "components"), "web-api", "frontend:web", "backend:api", 8);
        JsonNode result = analyze(input);
        assertEquals("partial", result.path("weighted").path("components").path("status").asText());
        assertTrue(result.path("weighted").path("components").path("available").asBoolean());
        assertEquals("not-applicable", result.path("weighted").path("backendApplications").path("status").asText());
        assertTrue(result.path("weighted").path("backendApplications").path("counts").path("hub").isNull());
        assertTrue(findings(result, "weighted-hub").stream().allMatch(n -> n.path("scope").asText().equals("declared-applications-and-clients")));
        ObjectNode twoBackends = graph("declared-backend-applications", "partial", "backend:a", "backend:b");
        twoBackends.putObject("coverage").put("matchingComplete", true); edge(twoBackends, "a-b", "backend:a", "backend:b", 6);
        input.withObject("graphs").set("backendApplications", twoBackends);
        assertEquals(1, analyze(input).path("weighted").path("backendApplications").path("counts").path("greedy").asInt());
    }

    @Test void incompleteHttpAndMissingModuleCoverageSuppressAbsenceAndCounts() throws Exception {
        ObjectNode input = analysis(); edge(graph(input, "components"), "web-api", "frontend:web", "backend:api", 8);
        graph(input, "components").withObject("coverage").put("matchingComplete", false);
        edge(graph(input, "modules"), "a-b", "a", "b", 8);
        graph(input, "modules").withObject("coverage").put("snapshotsWithArchitecture", 1);
        JsonNode result = analyze(input);
        for (String key : List.of("modules", "components")) {
            JsonNode weighted = result.path("weighted").path(key);
            assertFalse(weighted.path("available").asBoolean()); assertFalse(weighted.path("absenceProven").asBoolean());
            assertTrue(weighted.path("counts").path("hub").isNull()); assertEquals(0, weighted.path("scores").size());
        }
        assertTrue(findings(result, "weighted-hub").isEmpty());
    }

    @Test void missingOldArchitectureIsUnsupportedRatherThanHealthyEmptyFunctionGraph() throws Exception {
        ObjectNode input = analysis(); input.withArray("sourceCoverage").removeAll();
        graph(input, "modules").put("status", "unsupported");
        JsonNode graph = analyze(input).path("functionGraph");
        assertEquals("unsupported", graph.path("status").asText()); assertFalse(graph.path("available").asBoolean());
        assertTrue(graph.path("metrics").path("cyclicSccCount").isNull());
        assertEquals(2, graph.path("coverage").path("componentsWithoutArchitecture").size());
    }

    @Test void resolvedFrontendFunctionsHaveCyclesAndSimpleChainWitnessesWithCallProvenance() throws Exception {
        ObjectNode input = analysis(); for (String id : List.of("a", "b", "c", "d")) function(input, id);
        call(input, "ab", "a", "b"); call(input, "bc", "b", "c"); call(input, "ca", "c", "a"); call(input, "cd", "c", "d");
        JsonNode result = analyze(input), graph = result.path("functionGraph");
        assertEquals("partial", graph.path("status").asText());
        assertEquals(2, graph.path("metrics").path("sccCount").asInt());
        assertEquals(1, graph.path("metrics").path("cyclicSccCount").asInt());
        assertEquals(3, graph.path("metrics").path("cyclicFunctions").asInt());
        assertEquals(4, graph.path("cycleWitnesses").get(0).path("nodes").size());
        assertEquals("ab", graph.path("cycleWitnesses").get(0).path("calls").get(0).path("id").asText());
        assertEquals(4, graph.path("chainWitnessCount").asInt());
        for (JsonNode witness : graph.path("chainWitnesses")) {
            Set<String> unique = new HashSet<>(); witness.path("nodes").forEach(id -> unique.add(id.asText()));
            assertEquals(3, unique.size()); assertEquals(2, witness.path("calls").size());
        }
        assertEquals(1, findings(result, "function-cycle").size());
        assertTrue(findings(result, "function-chain").isEmpty());
        assertFalse(graph.path("chainWitnessesTruncated").asBoolean());
    }

    @Test void repeatedCallSitesDoNotDuplicateStructuralEdgesOrPaths() throws Exception {
        ObjectNode input = analysis(); for (String id : List.of("a", "b", "c")) function(input, id);
        call(input, "ab-2", "a", "b"); call(input, "ab-1", "a", "b"); call(input, "bc", "b", "c");
        JsonNode graph = analyze(input).path("functionGraph");
        assertEquals(3, graph.path("callObservations").asInt()); assertEquals(2, graph.path("edges").asInt());
        assertEquals(1, graph.path("chainWitnessCount").asInt());
        assertEquals("ab-1", graph.path("chainWitnesses").get(0).path("calls").get(0).path("id").asText());
        assertEquals(0, graph.path("metrics").path("cyclicSccCount").asInt());
    }

    @Test void witnessAndTraversalBoundsAreExplicitAndDoNotTruncateSccDetection() throws Exception {
        ObjectNode input = analysis(); for (String id : List.of("a", "b", "c", "d")) function(input, id);
        call(input, "ab", "a", "b"); call(input, "ba", "b", "a"); call(input, "bc", "b", "c"); call(input, "cd", "c", "d"); call(input, "dc", "d", "c");
        JavaScriptAdditionalGraphService oneWitness = new JavaScriptAdditionalGraphService(new JavaScriptAdditionalGraphService.Options(6, 4, 3, 1, 100, 100, 100));
        JsonNode graph = oneWitness.analyze(input).path("functionGraph");
        assertEquals(2, graph.path("metrics").path("cyclicSccCount").asInt());
        assertEquals(1, graph.path("cycleWitnessCount").asInt()); assertTrue(graph.path("cycleWitnessesTruncated").asBoolean());
        assertEquals(1, graph.path("chainWitnessCount").asInt()); assertTrue(graph.path("chainWitnessesTruncated").asBoolean());
        JavaScriptAdditionalGraphService oneStep = new JavaScriptAdditionalGraphService(new JavaScriptAdditionalGraphService.Options(6, 4, 3, 100, 100, 100, 1));
        graph = oneStep.analyze(input).path("functionGraph");
        assertEquals(2, graph.path("metrics").path("cyclicSccCount").asInt());
        assertTrue(graph.path("cycleTraversalBoundReached").asBoolean()); assertTrue(graph.path("chainTraversalBoundReached").asBoolean());
        assertEquals(1, graph.path("chainTraversalSteps").asInt());
    }

    @Test void sourceOrderAndLineChangesDoNotChangeStableFindingOrWitnessIdentities() throws Exception {
        ObjectNode input = analysis(); edge(graph(input, "modules"), "ab", "a", "b", 6);
        for (String id : List.of("a", "b", "c")) function(input, id);
        call(input, "ab", "a", "b"); call(input, "bc", "b", "c"); call(input, "ca", "c", "a");
        JsonNode baseline = analyze(input); ObjectNode reordered = input.deepCopy();
        for (String field : List.of("functions", "functionCalls", "sourceCoverage", "components")) reverse(reordered.withArray(field));
        reverse(graph(reordered, "modules").withArray("nodes"));
        assertEquals(baseline, analyze(reordered));
        for (JsonNode node : reordered.path("functions")) ((ObjectNode) node).put("line", 500).put("endLine", 510);
        for (JsonNode node : reordered.path("functionCalls")) ((ObjectNode) node).put("line", 501);
        JsonNode changed = analyze(reordered);
        assertEquals(ids(baseline.path("findings")), ids(changed.path("findings")));
        assertEquals(ids(baseline.path("functionGraph").path("cycleWitnesses")), ids(changed.path("functionGraph").path("cycleWitnesses")));
        assertEquals(ids(baseline.path("functionGraph").path("chainWitnesses")), ids(changed.path("functionGraph").path("chainWitnesses")));
    }

    @Test void malformedDuplicatesReferencesWeightsAndLimitsFailClosed() {
        ObjectNode duplicate = analysis(); function(duplicate, "a"); function(duplicate, "a");
        assertThrows(IOException.class, () -> analyze(duplicate));
        ObjectNode dangling = analysis(); function(dangling, "a"); call(dangling, "ab", "a", "b");
        assertThrows(IOException.class, () -> analyze(dangling));
        ObjectNode duplicateCall = analysis(); function(duplicateCall, "a"); call(duplicateCall, "x", "a", "a"); call(duplicateCall, "x", "a", "a");
        assertThrows(IOException.class, () -> analyze(duplicateCall));
        ObjectNode crossComponent = analysis(); function(crossComponent, "a"); function(crossComponent, "b", "backend:api"); call(crossComponent, "ab", "a", "b");
        assertThrows(IOException.class, () -> analyze(crossComponent));
        ObjectNode invalidWeight = analysis(); edge(graph(invalidWeight, "modules"), "ab", "a", "b", 0);
        assertThrows(IOException.class, () -> analyze(invalidWeight));
        ObjectNode duplicateNode = analysis(); graph(duplicateNode, "modules").withArray("nodes").addObject().put("id", "a");
        assertThrows(IOException.class, () -> analyze(duplicateNode));
        assertThrows(IllegalArgumentException.class, () -> new JavaScriptAdditionalGraphService.Options(0, 4, 3, 100, 100, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new JavaScriptAdditionalGraphService.Options(6, 4, 20, 100, 100, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new JavaScriptAdditionalGraphService.Options(6, 4, 3, 100, 100001, 100, 100));
    }

    @Test void partialComponentCoverageAndUnresolvedCallsStayExplicit() throws Exception {
        ObjectNode input = analysis(); input.withArray("sourceCoverage").remove(0);
        ((ObjectNode) input.path("sourceCoverage").get(0)).put("unresolvedCallSites", 9).put("resolvedTopLevelCallsOmitted", 2);
        function(input, "recursive"); call(input, "self", "recursive", "recursive");
        JsonNode result = analyze(input), graph = result.path("functionGraph");
        assertEquals("partial", graph.path("status").asText()); assertTrue(graph.path("available").asBoolean());
        assertEquals(9, graph.path("coverage").path("unresolvedCallSites").asInt());
        assertEquals(2, graph.path("coverage").path("resolvedTopLevelCallsOmitted").asInt());
        assertEquals("backend:api", graph.path("coverage").path("componentsWithoutArchitecture").get(0).asText());
        assertEquals(1, findings(result, "function-cycle").size());
        assertFalse(graph.path("absenceProven").asBoolean());
    }

    @Test void thirtyThousandFunctionsUseIterativeSparseGraphAlgorithms() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            ObjectNode input = analysis();
            for (int i = 0; i < 30000; i++) function(input, "f" + i);
            for (int i = 1; i < 30000; i++) call(input, "c" + i, "f" + (i - 1), "f" + i);
            JsonNode result = analyze(input).path("functionGraph");
            assertEquals(30000, result.path("metrics").path("sccCount").asInt());
            assertEquals(0, result.path("metrics").path("cyclicSccCount").asInt());
            assertEquals(100, result.path("chainWitnessCount").asInt());
            assertTrue(result.path("chainWitnessesTruncated").asBoolean());
        });
    }

    @Test void savedReportRecomputationAcceptsValidAndRejectsTamperedCountsOrFindings() throws Exception {
        ObjectNode input = analysis(); edge(graph(input, "modules"), "ab", "a", "b", 6);
        function(input, "a"); call(input, "self", "a", "a");
        input.set("additionalGraphs", analyze(input));
        JsonNode before = input.deepCopy();
        JavaScriptAdditionalGraphService.validateSavedAnalysis(input);
        JsonNode reloaded = JavaScriptExtractionService.JSON.readTree(JavaScriptExtractionService.JSON.writeValueAsBytes(input));
        JavaScriptAdditionalGraphService.validateSavedAnalysis(reloaded);
        assertEquals(before, input);
        ObjectNode tampered = input.deepCopy();
        ((ObjectNode) tampered.path("additionalGraphs").path("weighted").path("modules").path("counts")).put("hub", 0);
        assertThrows(IOException.class, () -> JavaScriptAdditionalGraphService.validateSavedAnalysis(tampered));
        ObjectNode missingFinding = input.deepCopy(); missingFinding.withObject("additionalGraphs").withArray("findings").removeAll();
        assertThrows(IOException.class, () -> JavaScriptAdditionalGraphService.validateSavedAnalysis(missingFinding));
    }

    @Test void savedLimitsRejectStringsFractionsOverflowAndOutOfRangeValues() throws Exception {
        ObjectNode input = analysis(); input.set("additionalGraphs", analyze(input));
        for (JsonNode invalid : List.of(JavaScriptExtractionService.JSON.getNodeFactory().textNode("6"),
                JavaScriptExtractionService.JSON.getNodeFactory().numberNode(1.5),
                JavaScriptExtractionService.JSON.getNodeFactory().numberNode(Long.MAX_VALUE),
                JavaScriptExtractionService.JSON.getNodeFactory().numberNode(0))) {
            ObjectNode bad = input.deepCopy(); bad.withObject("additionalGraphs").withObject("limits").set("weightedThreshold", invalid);
            assertThrows(IOException.class, () -> JavaScriptAdditionalGraphService.validateSavedAnalysis(bad));
        }
        ObjectNode huge = input.deepCopy(); huge.withObject("additionalGraphs").withObject("limits").put("maxNodesPerGraph", 100001);
        assertThrows(IOException.class, () -> JavaScriptAdditionalGraphService.validateSavedAnalysis(huge));
        ObjectNode future = input.deepCopy(); future.withObject("additionalGraphs").withObject("limits").put("definitionsVersion", 2);
        assertThrows(IOException.class, () -> JavaScriptAdditionalGraphService.validateSavedAnalysis(future));
        ObjectNode extra = input.deepCopy(); extra.withObject("additionalGraphs").withObject("limits").put("ignoredOption", 1);
        assertThrows(IOException.class, () -> JavaScriptAdditionalGraphService.validateSavedAnalysis(extra));
    }

    private static void reverse(ArrayNode array) {
        List<JsonNode> nodes = new ArrayList<>(); array.forEach(nodes::add); Collections.reverse(nodes); array.removeAll(); nodes.forEach(array::add);
    }
    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>(); array.forEach(n -> ids.add(n.path("id").asText())); return ids;
    }
}
