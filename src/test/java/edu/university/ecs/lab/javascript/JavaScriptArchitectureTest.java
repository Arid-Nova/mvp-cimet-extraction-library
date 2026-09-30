package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptArchitectureTest {
    private final JavaScriptArchitectureService service = new JavaScriptArchitectureService();

    private static ObjectNode architecture(ObjectNode snapshot, String... files) {
        ObjectNode graph = snapshot.putObject("architecture").put("schemaVersion", 1);
        graph.putArray("modules"); graph.putArray("dependencies"); graph.putArray("functions");
        graph.putArray("calls"); graph.putArray("endpointHandlers");
        for (String file : files) graph.withArray("modules").addObject().put("id", file).put("file", file).put("line", 1);
        return graph;
    }
    private static ObjectNode dependency(ObjectNode graph, String from, String to) {
        ObjectNode value = graph.withArray("dependencies").addObject().put("id", "dependency-" + graph.path("dependencies").size())
                .put("source", from).put("target", to).put("line", 1).put("kind", "require")
                .put("resolution", "resolved").put("typeOnly", false);
        return value;
    }
    private static ObjectNode function(ObjectNode graph, String id, String file, String fingerprint) {
        return graph.withArray("functions").addObject().put("id", id).put("file", file).put("line", 1).put("endLine", 2)
                .put("name", id).put("bodyFingerprint", fingerprint.repeat(64));
    }
    private static void functionCall(ObjectNode graph, String from, String to, String file) {
        graph.withArray("calls").addObject().put("id", "call-" + graph.path("calls").size()).put("file", file).put("line", 1)
                .put("callerId", from).put("targetId", to);
    }
    private ObjectNode analyze(ObjectNode... snapshots) throws IOException { return service.analyze(List.of(snapshots), "system"); }
    private static JsonNode metrics(JsonNode analysis, String scope) { return analysis.path("graphs").path(scope).path("metrics"); }
    private static long findingCount(JsonNode analysis, String kind) {
        long count = 0; for (JsonNode n : analysis.path("findings")) if (n.path("kind").asText().equals(kind)) count++; return count;
    }
    private static JsonNode capability(JsonNode analysis, String id) {
        for (JsonNode n : analysis.path("capabilities")) if (id.equals(n.path("id").asText())) return n;
        throw new AssertionError("Missing capability " + id);
    }

    @Test void degreeMetricsUseDistinctNeighborsAndProductWithSccCycleWitness() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js", "c.js");
        dependency(graph, "a.js", "b.js"); dependency(graph, "a.js", "b.js");
        dependency(graph, "b.js", "c.js"); dependency(graph, "c.js", "a.js");
        var analysis = analyze(snapshot); var metrics = metrics(analysis, "modules");
        assertEquals(3, metrics.path("edgeCount").asInt()); assertEquals(0.5, metrics.path("density").asDouble());
        assertEquals(1, metrics.path("maxAIS").asInt()); assertEquals(1, metrics.path("maxADS").asInt());
        assertEquals(1, metrics.path("maxACS").asInt()); assertEquals(1, metrics.path("cyclicSccCount").asInt());
        assertEquals(0, metrics.path("reciprocalPairs").asInt());
        JsonNode cycle = analysis.path("graphs").path("modules").path("cycleWitnesses").get(0).path("nodes");
        assertEquals(4, cycle.size()); assertEquals(cycle.get(0), cycle.get(3));
        assertEquals(1, findingCount(analysis, "cyclic-dependency-component"));
    }

    @Test void acyclicPathsAndThresholdCandidatesAreModuleScoped() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js", "c.js", "d.js");
        dependency(graph, "a.js", "b.js"); dependency(graph, "a.js", "c.js"); dependency(graph, "b.js", "c.js");
        var options = new JavaScriptArchitectureService.Options(2, 3, 20, 100, 100, 1000, 1000);
        var analysis = new JavaScriptArchitectureService(options).analyze(List.of(snapshot), "system");
        assertEquals(0, metrics(analysis, "modules").path("cyclicSccCount").asInt());
        assertEquals(1, findingCount(analysis, "high-incoming-degree-candidate"));
        assertEquals(1, findingCount(analysis, "high-outgoing-degree-candidate"));
        assertEquals(1, analysis.path("graphs").path("modules").path("chainWitnesses").size());
        assertEquals("not-applicable", analysis.path("graphs").path("backendApplications").path("status").asText());
        assertTrue(metrics(analysis, "backendApplications").path("density").isNull());
    }

    @Test void typeOnlyExternalAndUnresolvedEdgesAreExcludedAndCounted() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js");
        dependency(graph, "a.js", "b.js").put("typeOnly", true);
        dependency(graph, "a.js", "b.js").put("resolution", "external").putNull("target");
        dependency(graph, "a.js", "b.js").put("resolution", "unresolved").putNull("target");
        var analysis = analyze(snapshot); var coverage = analysis.path("graphs").path("modules").path("coverage");
        assertEquals(0, metrics(analysis, "modules").path("edgeCount").asInt());
        assertEquals(1, coverage.path("excludedTypeOnlyDependencies").asInt());
        assertEquals(1, coverage.path("externalDependencies").asInt()); assertEquals(1, coverage.path("unresolvedDependencies").asInt());
    }

    @Test void serviceGraphUsesDeclaredBackendsAndExcludesFrontendFromServiceMetrics() throws Exception {
        var a = snapshot("backend", "a"); var b = snapshot("backend", "b"); var front = snapshot("frontend", "web");
        architecture(a, "a.js"); architecture(b, "b.js"); architecture(front, "web.js");
        a.withArray("endpoints").add(endpoint("a-route", "/a")); b.withArray("endpoints").add(endpoint("b-route", "/b"));
        a.withArray("calls").add(call("to-b", "/b")); b.withArray("calls").add(call("to-a", "/a"));
        front.withArray("calls").add(call("web-to-a", "/a"));
        var analysis = analyze(front, b, a); var metrics = metrics(analysis, "backendApplications");
        assertEquals(2, metrics.path("nodeCount").asInt()); assertEquals(2, metrics.path("edgeCount").asInt());
        assertEquals(1, metrics.path("density").asDouble()); assertEquals(1, metrics.path("reciprocalPairs").asInt());
        assertEquals(1, metrics.path("cyclicSccCount").asInt());
        assertEquals(3, metrics(analysis, "components").path("nodeCount").asInt());
        assertEquals("partial", analysis.path("graphs").path("backendApplications").path("status").asText());
        assertEquals(analyze(a, front, b), analysis);
    }

    @Test void httpMatchingKeepsDuplicatesExternalUnknownAndCaseOnlyAlternativesSeparate() throws Exception {
        var api = snapshot("backend", "api"); var web = snapshot("frontend", "web"); architecture(api, "a.js"); architecture(web, "w.js");
        api.withArray("endpoints").add(endpoint("param", "/users/{param}"));
        api.withArray("endpoints").add(endpoint("duplicate1", "/duplicate")); api.withArray("endpoints").add(endpoint("duplicate2", "/duplicate"));
        api.withArray("endpoints").add(endpoint("case", "/Case")); api.withArray("endpoints").add(endpoint("lonely", "/lonely"));
        web.withArray("calls").add(call("param", "/users/123")); web.withArray("calls").add(call("dup", "/duplicate"));
        web.withArray("calls").add(call("external", "/lonely").put("target", "external"));
        web.withArray("calls").add(call("unknown", "/lonely").put("target", "unknown"));
        web.withArray("calls").add(call("case", "/case")); web.withArray("calls").add(call("missing", "/missing"));
        var result = analyze(api, web); var summary = result.path("http").path("summary");
        for (String key : List.of("matched", "ambiguous", "external", "unknown", "case-only-candidate", "unmatched")) assertEquals(1, summary.path(key).asInt(), key);
        assertEquals(1, summary.path("noObservedCaller").asInt());
        assertEquals(1, metrics(result, "components").path("edgeCount").asInt());
        assertEquals(1, findingCount(result, "AR3-unmatched")); assertEquals(1, findingCount(result, "AR4-no-observed-caller"));
        api.withObject("architecture").put("caseSensitiveRouting", false);
        assertEquals(2, analyze(api, web).path("http").path("summary").path("matched").asInt());
        api.withObject("architecture").put("caseSensitiveRouting", true);
        assertEquals(2, analyze(api, web).path("http").path("summary").path("unmatched").asInt());
    }

    @Test void allAndHeadRegistrationCandidatesRemainAmbiguousWhenOverlapping() throws Exception {
        var api = snapshot("backend", "api"); var web = snapshot("frontend", "web");
        api.withArray("endpoints").add(endpoint("get", "/x")); api.withArray("endpoints").add(endpoint("head", "/x").put("method", "HEAD"));
        api.withArray("endpoints").add(endpoint("all", "/y").put("method", "ALL"));
        web.withArray("calls").add(call("head", "/x").put("method", "HEAD")); web.withArray("calls").add(call("post", "/y").put("method", "POST"));
        var summary = analyze(api, web).path("http").path("summary");
        assertEquals(1, summary.path("ambiguous").asInt()); assertEquals(1, summary.path("matched").asInt());
    }

    @Test void noArchitectureDataIsUnsupportedAndLegacySemanticCapabilitiesStayUnsupported() throws Exception {
        var analysis = analyze(snapshot("backend", "api"));
        assertEquals("unsupported", analysis.path("graphs").path("modules").path("status").asText());
        assertTrue(metrics(analysis, "modules").path("avgAIS").isNull());
        assertEquals("unsupported", capability(analysis, "AR6-style-function-impact").path("status").asText());
        for (String id : List.of("SIDC", "SSIC", "LOMLC", "SC", "LWF", "GWF", "SCCmodularity", "WrongCuts", "health-checks", "API-gateway", "wobbly-interactions", "AR7-data-access-impact"))
            assertEquals("unsupported", capability(analysis, id).path("status").asText());
    }

    @Test void weightedChangesAndCompleteContractDifferencesSurviveArchitectureIntegration() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js");
        dependency(graph, "a.js", "b.js");
        var contracts = graph.putObject("contracts").put("schemaVersion", 1).putArray("operations");
        for (String route : List.of("one", "two")) {
            snapshot.withArray("endpoints").add(endpoint(route, "/" + route).put("file", "a.js"));
            var contract = contracts.addObject().put("endpointId", route).put("inputComplete", true).put("outputComplete", true);
            contract.putArray("inputs").addObject().put("location", "body").put("name", "value").put("type", "string")
                    .put("evidence", "declared").put("file", "a.js").put("line", 1);
            contract.putArray("responses").addObject().put("kind", "json").put("type", "string").put("shape", "string")
                    .put("complete", true).put("file", "a.js").put("line", 1);
            contract.putArray("limitations");
        }
        var before = analyze(snapshot);
        dependency(graph, "a.js", "b.js");
        ((ObjectNode) contracts.get(1).path("inputs").get(0)).put("type", "number");
        var after = analyze(snapshot); var delta = service.compare(before, after);
        assertEquals("supported", capability(after, "SC").path("status").asText());
        assertEquals("supported", capability(after, "SIDC").path("status").asText());
        assertEquals(1, delta.path("graphs").path("modules").path("edgeWeightsChanged").size());
        assertEquals(before.path("graphs").path("modules").path("weightedMetrics"), delta.path("graphs").path("modules").path("beforeWeightedMetrics"));
        assertEquals(after.path("graphs").path("modules").path("coverage"), delta.path("graphs").path("modules").path("afterCoverage"));
        assertEquals(.5, delta.path("graphs").path("modules").path("metrics").path("avgSC").path("change").asDouble(), 1e-12);
        JsonNode cohesion = delta.path("cohesion").path("components").get(0);
        assertEquals(-1.0, cohesion.path("SIDC").path("change").asDouble(), 1e-12);
        assertEquals(.5, cohesion.path("LOMLC").path("change").asDouble(), 1e-12);
        assertEquals(2, cohesion.path("SIDC").path("afterCoverage").path("operationCount").asInt());
        var missing = after.deepCopy(); missing.remove("cohesion");
        assertThrows(IOException.class, () -> service.compare(before, missing));
    }

    @Test void changedFunctionPropagatesToEndpointAcrossResolvedCallsWithoutLineNoise() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "handler.js", "logic.js");
        snapshot.withArray("endpoints").add(endpoint("route", "/work"));
        function(graph, "handler", "handler.js", "a"); function(graph, "logic", "logic.js", "b");
        functionCall(graph, "handler", "logic", "handler.js");
        graph.withArray("endpointHandlers").addObject().put("endpointId", "route").put("functionId", "handler");
        var before = analyze(snapshot);
        ((ObjectNode) graph.path("functions").get(1)).put("line", 10).put("endLine", 12);
        assertEquals(0, service.compare(before, analyze(snapshot)).path("summary").path("changedFunctions").asInt());
        ((ObjectNode) graph.path("functions").get(1)).put("bodyFingerprint", "c".repeat(64));
        var delta = service.compare(before, analyze(snapshot));
        assertEquals(1, delta.path("changedFunctions").size());
        assertEquals(2, delta.path("potentiallyAffectedEndpoints").size());
        assertEquals(2, delta.path("potentiallyAffectedEndpoints").get(0).path("functionPath").size());
        assertTrue(delta.path("summary").path("impactTraversalComplete").asBoolean());
        assertEquals(0, delta.path("graphs").path("modules").path("edgesAdded").size());
        assertEquals(0, delta.path("findings").path("introduced").size());
    }

    @Test void historicalCycleRemovalAndStableFindingIdentitiesAreObservable() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js");
        dependency(graph, "a.js", "b.js"); dependency(graph, "b.js", "a.js");
        var before = analyze(snapshot);
        ((ObjectNode) graph.path("dependencies").get(0)).put("line", 20);
        var lineDelta = service.compare(before, analyze(snapshot));
        assertEquals(0, lineDelta.path("findings").path("introduced").size()); assertEquals(1, lineDelta.path("findings").path("persistingCount").asInt());
        graph.withArray("dependencies").remove(1);
        var delta = service.compare(before, analyze(snapshot));
        assertEquals(1, delta.path("findings").path("resolved").size());
        assertEquals(1, delta.path("graphs").path("modules").path("edgesRemoved").size());
        assertEquals(-1, delta.path("graphs").path("modules").path("metrics").path("cyclicSccCount").path("change").asInt());
    }

    @Test void boundsHttpSearchWithoutClaimingNoCallerOrUniqueMatch() throws Exception {
        var api = snapshot("backend", "api"); var web = snapshot("frontend", "web");
        api.withArray("endpoints").add(endpoint("1", "/a")); api.withArray("endpoints").add(endpoint("2", "/b"));
        web.withArray("calls").add(call("call", "/a"));
        var options = new JavaScriptArchitectureService.Options(6, 3, 10, 100, 100, 1, 100);
        var result = new JavaScriptArchitectureService(options).analyze(List.of(api, web), "system");
        assertFalse(result.path("http").path("summary").path("matchingComplete").asBoolean());
        assertEquals(1, result.path("http").path("summary").path("unknown").asInt());
        assertEquals(0, result.path("http").path("summary").path("noObservedCaller").asInt());
        assertEquals(0, result.path("findings").size());
    }

    @Test void validatesReferencesLimitsAndComparisonIdentity() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js"); dependency(graph, "a.js", "missing.js");
        assertThrows(IOException.class, () -> analyze(snapshot));
        graph.withArray("dependencies").removeAll();
        assertThrows(IOException.class, () -> service.analyze(List.of(snapshot, snapshot), "system"));
        var before = analyze(snapshot); var after = analyze(snapshot); after.put("name", "different");
        assertThrows(IOException.class, () -> service.compare(before, after));
        var limited = new JavaScriptArchitectureService(new JavaScriptArchitectureService.Options(6, 3, 1, 1, 10, 100, 100));
        graph.withArray("modules").addObject().put("id", "b.js").put("file", "b.js").put("line", 1);
        assertThrows(IOException.class, () -> limited.analyze(List.of(snapshot), "system"));
    }

    @Test void whollyDynamicPathsAndUnknownMethodsDoNotCreateUniqueMatches() throws Exception {
        var api = snapshot("backend", "api"); var web = snapshot("frontend", "web");
        api.withArray("endpoints").add(endpoint("all", "/unrelated").put("method", "ALL"));
        web.withArray("calls").add(call("dynamic", "/{param}"));
        web.withArray("calls").add(call("method", "/unrelated").put("method", "UNKNOWN"));
        var analysis = analyze(api, web);
        assertEquals(2, analysis.path("http").path("summary").path("unknown").asInt());
        assertEquals(0, analysis.path("http").path("summary").path("matched").asInt());
        assertEquals(0, metrics(analysis, "components").path("edgeCount").asInt());
    }

    @Test void embeddedTemplatesStayUnknownWithoutFalseAbsenceFindings() throws Exception {
        var api = snapshot("backend", "api"); var web = snapshot("frontend", "web");
        api.withArray("endpoints").add(endpoint("report", "/reports/{param}.pdf"));
        api.withArray("endpoints").add(endpoint("photo", "/photo/image-1"));
        web.withArray("calls").add(call("report", "/reports/123.pdf"));
        web.withArray("calls").add(call("photo", "/photo/image-{param}"));
        var analysis = analyze(api, web);
        assertEquals(2, analysis.path("http").path("summary").path("unknown").asInt());
        assertEquals(0, findingCount(analysis, "AR3-unmatched")); assertEquals(0, findingCount(analysis, "AR4-no-observed-caller"));
        assertEquals(0, metrics(analysis, "components").path("edgeCount").asInt());
    }

    @Test void savedAnalysesRejectDuplicateAndDanglingIdentities() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js"); dependency(graph, "a.js", "b.js");
        var before = analyze(snapshot); var after = before.deepCopy();
        after.withObject("graphs").withObject("modules").withArray("nodes").add(after.path("graphs").path("modules").path("nodes").get(0).deepCopy());
        assertThrows(IOException.class, () -> service.compare(before, after));
        var dangling = before.deepCopy();
        ((ObjectNode) dangling.path("graphs").path("modules").path("edges").get(0)).put("target", "missing");
        assertThrows(IOException.class, () -> service.compare(before, dangling));
        var duplicateFinding = before.deepCopy();
        duplicateFinding.withArray("findings").addObject().put("id", "same"); duplicateFinding.withArray("findings").addObject().put("id", "same");
        assertThrows(IOException.class, () -> service.compare(before, duplicateFinding));
    }

    @Test void coverageLossAndChangedBoundsCannotBecomeHistoricalCodeRemovals() throws Exception {
        var snapshot = snapshot("backend", "api"); architecture(snapshot, "a.js");
        var before = analyze(snapshot); snapshot.remove("architecture");
        var withoutEvidence = analyze(snapshot);
        assertThrows(IOException.class, () -> service.compare(before, withoutEvidence));
        var changedLimit = before.deepCopy(); changedLimit.withObject("limits").put("maxWitnessesPerGraph", 1);
        assertThrows(IOException.class, () -> service.compare(before, changedLimit));
    }

    @Test void selfLoopIsCyclicButDoesNotInflateNeighborDegree() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js"); dependency(graph, "a.js", "a.js");
        var analysis = analyze(snapshot); var metrics = metrics(analysis, "modules");
        assertEquals(1, metrics.path("selfLoops").asInt()); assertEquals(1, metrics.path("cyclicSccCount").asInt());
        assertEquals(0, metrics.path("maxAIS").asInt()); assertEquals(0, metrics.path("maxADS").asInt());
        assertEquals(0, metrics.path("reciprocalPairs").asInt()); assertTrue(metrics.path("density").isNull());
    }

    @Test void sampledChainsAreNotHistoricalFindingsAndImpactWitnessesAreBounded() throws Exception {
        var snapshot = snapshot("backend", "api"); var graph = architecture(snapshot, "a.js", "b.js", "c.js");
        dependency(graph, "a.js", "b.js"); dependency(graph, "b.js", "c.js");
        function(graph, "handler", "a.js", "a"); function(graph, "logic", "b.js", "b"); functionCall(graph, "handler", "logic", "a.js");
        for (int i = 0; i < 3; i++) {
            snapshot.withArray("endpoints").add(endpoint("e" + i, "/p" + i));
            graph.withArray("endpointHandlers").addObject().put("endpointId", "e" + i).put("functionId", "handler");
        }
        var bounded = new JavaScriptArchitectureService(new JavaScriptArchitectureService.Options(6, 3, 1, 100, 100, 100, 100));
        var before = bounded.analyze(List.of(snapshot), "system");
        assertEquals(0, findingCount(before, "dependency-chain-witness"));
        ((ObjectNode) graph.path("functions").get(1)).put("bodyFingerprint", "c".repeat(64));
        var delta = bounded.compare(before, bounded.analyze(List.of(snapshot), "system"));
        assertEquals(6, delta.path("summary").path("potentiallyAffectedEndpointObservations").asInt());
        assertEquals(2, delta.path("summary").path("emittedImpactWitnesses").asInt());
        assertTrue(delta.path("summary").path("impactWitnessesTruncated").asBoolean());
    }

    @Test void sccTraversalHandlesLongGraphsAndWitnessWorkIsBounded() throws Exception {
        var snapshot = snapshot("backend", "api"); List<String> names = new ArrayList<>();
        for (int i = 0; i < 1500; i++) names.add("m" + i + ".js");
        var graph = architecture(snapshot, names.toArray(String[]::new));
        for (int i = 1; i < names.size(); i++) dependency(graph, names.get(i - 1), names.get(i));
        var bounded = new JavaScriptArchitectureService(new JavaScriptArchitectureService.Options(6, 3, 1, 2000, 2000, 100, 100));
        var analysis = bounded.analyze(List.of(snapshot), "system");
        assertEquals(1500, metrics(analysis, "modules").path("sccCount").asInt());
        assertEquals(0, metrics(analysis, "modules").path("cyclicSccCount").asInt());
        assertEquals(1, analysis.path("graphs").path("modules").path("chainWitnesses").size());
        assertTrue(analysis.path("graphs").path("modules").path("chainWitnessSearchBoundReached").asBoolean());
    }
}
