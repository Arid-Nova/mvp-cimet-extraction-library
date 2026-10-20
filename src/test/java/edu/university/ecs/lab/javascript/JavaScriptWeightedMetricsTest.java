package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jgrapht.alg.clustering.UndirectedModularityMeasurer;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JavaScriptWeightedMetricsTest {
    private static final double EPSILON = 1e-12;

    private static ObjectNode graph(String... nodes) {
        ObjectNode graph = JavaScriptExtractionService.JSON.createObjectNode().put("scope", "file-modules").put("status", "supported");
        graph.putArray("nodes"); graph.putArray("edges"); graph.putObject("metrics").put("existingMetric", 42);
        for (String node : nodes) graph.withArray("nodes").addObject().put("id", node);
        return graph;
    }

    private static ObjectNode edge(ObjectNode graph, String source, String target, int observations) {
        String id = "edge-" + graph.path("edges").size();
        return graph.withArray("edges").addObject().put("id", id)
                .put("source", source).put("target", target).put("observations", observations);
    }

    private static double value(JsonNode graph, String metric) { return graph.path("metrics").path(metric).asDouble(); }

    @Test void weightedAsymmetricReciprocalPairsHaveHandCalculatedValues() throws Exception {
        ObjectNode graph = graph("a", "b"); edge(graph, "a", "b", 3); edge(graph, "b", "a", 1);
        JavaScriptWeightedMetrics.enrich(graph);
        // Degree is 4 in both directions: LWF=.8/.4, GWF=1/1, SC=-.05/.35.
        assertEquals(0.8, value(graph, "maxLWF"), EPSILON);
        assertEquals(0.6, value(graph, "avgLWF"), EPSILON);
        assertEquals(0.2, value(graph, "stdLWF"), EPSILON);
        assertEquals(1.0, value(graph, "avgGWF"), EPSILON);
        assertEquals(0.0, value(graph, "stdGWF"), EPSILON);
        assertEquals(0.35, value(graph, "maxSC"), EPSILON);
        assertEquals(0.15, value(graph, "avgSC"), EPSILON);
        assertEquals(0.2, value(graph, "stdSC"), EPSILON);
        assertEquals(0.0, value(graph, "SCCmodularity"), EPSILON);
        assertEquals(42, graph.path("metrics").path("existingMetric").asInt());
        assertTrue(graph.path("weightedMetrics").path("available").asBoolean());
        assertEquals(4, graph.path("weightedMetrics").path("totalObservationWeight").asInt());
    }

    @Test void oneDirectionCanHaveNegativeStructuralCouplingAndPreservesOriginalSccShortcut() throws Exception {
        ObjectNode graph = graph("a", "b"); edge(graph, "a", "b", 1);
        JavaScriptWeightedMetrics.enrich(graph);
        assertEquals(-1.0, value(graph, "maxSC"), EPSILON); assertEquals(-1.0, value(graph, "avgSC"), EPSILON);
        assertEquals(0.0, value(graph, "stdSC"), EPSILON);
        assertEquals(1.0, value(graph, "maxLWF"), EPSILON); assertEquals(1.0, value(graph, "maxGWF"), EPSILON);
        // CIMET2 explicitly returns zero for a singleton SCC partition, not generic singleton modularity.
        assertEquals(0.0, value(graph, "SCCmodularity"), EPSILON);
        assertEquals(2, graph.path("weightedMetrics").path("sccPartitionCount").asInt());
    }

    @Test void differentlyWeightedPairsUseGlobalMaximumDegreeAndPopulationSummaries() throws Exception {
        ObjectNode graph = graph("a", "b", "c"); edge(graph, "a", "b", 3); edge(graph, "b", "a", 1); edge(graph, "b", "c", 2);
        JavaScriptWeightedMetrics.enrich(graph);
        // LWF=.8,.4,1; GWF=1,1,.5; SC=-.05,.35,0.
        assertEquals(11.0 / 15.0, value(graph, "avgLWF"), EPSILON);
        assertEquals(5.0 / 6.0, value(graph, "avgGWF"), EPSILON);
        assertEquals(0.1, value(graph, "avgSC"), EPSILON);
        // ((-.05-.10)^2 + (.35-.10)^2 + (0-.10)^2) / 3 = 19/600.
        assertEquals(Math.sqrt(19.0 / 600.0), value(graph, "stdSC"), EPSILON);
        assertEquals(4, graph.path("weightedMetrics").path("maxPairObservationDegree").asInt());
        assertEquals(3, graph.path("weightedMetrics").path("evaluatedOrderedPairs").asInt());
    }

    @Test void twoConnectedCyclicGroupsHaveHandCalculatedModularityAndIsolateDoesNotAlterIt() throws Exception {
        ObjectNode graph = graph("a", "b", "c", "d", "isolated");
        edge(graph, "a", "b", 1); edge(graph, "b", "a", 1); edge(graph, "c", "d", 1); edge(graph, "d", "c", 1); edge(graph, "b", "c", 1);
        JavaScriptWeightedMetrics.enrich(graph);
        // m=5; each cyclic community has internal weight2 and degree sum5: 2*(2/5-(5/10)^2)=.3.
        assertEquals(0.3, value(graph, "SCCmodularity"), EPSILON);
        assertEquals(3, graph.path("weightedMetrics").path("sccPartitionCount").asInt());
        assertEquals(jGraphTReference(graph), value(graph, "SCCmodularity"), EPSILON);
    }

    @Test void negativeModularityRemainsVisible() throws Exception {
        ObjectNode graph = graph("a", "b", "c"); edge(graph, "a", "b", 1); edge(graph, "b", "a", 1); edge(graph, "a", "c", 100);
        JavaScriptWeightedMetrics.enrich(graph);
        // SCCs {a,b},{c}; m=102; community degree sums104,100; internal weights2,0.
        assertEquals(-1250.0 / 2601.0, value(graph, "SCCmodularity"), EPSILON);
        assertTrue(value(graph, "SCCmodularity") < 0);
    }

    @Test void aggregatesParallelSourceObservationsAndMatchesOriginalFormulasAndLibraryConventions() throws Exception {
        ObjectNode graph = graph("a", "b", "c", "d");
        edge(graph, "a", "b", 3); edge(graph, "a", "b", 2); edge(graph, "b", "a", 1);
        edge(graph, "b", "c", 7); edge(graph, "c", "d", 4); edge(graph, "d", "c", 3);
        Map<String, Double> reference = originalCouplingReference(graph);
        double modularity = jGraphTReference(graph);
        JavaScriptWeightedMetrics.enrich(graph);
        for (Map.Entry<String, Double> entry : reference.entrySet()) assertEquals(entry.getValue(), value(graph, entry.getKey()), EPSILON, entry.getKey());
        assertEquals(modularity, value(graph, "SCCmodularity"), EPSILON);
        assertEquals(5, graph.path("weightedMetrics").path("evaluatedOrderedPairs").asInt());
        assertEquals(6, graph.path("edges").size()); // Enrichment does not rewrite the original graph.
    }

    @Test void noEdgesAndSelfLoopOnlyProduceUnavailableNullValues() throws Exception {
        ObjectNode empty = graph(); JavaScriptWeightedMetrics.enrich(empty); assertUnavailable(empty, "not-applicable");
        ObjectNode isolated = graph("a", "b"); JavaScriptWeightedMetrics.enrich(isolated); assertUnavailable(isolated, "not-applicable");
        ObjectNode loops = graph("a"); edge(loops, "a", "a", 12); JavaScriptWeightedMetrics.enrich(loops); assertUnavailable(loops, "not-applicable");
        assertEquals(1, loops.path("weightedMetrics").path("excludedSelfLoopRecords").asInt());
        assertEquals(12, loops.path("weightedMetrics").path("excludedSelfLoopWeight").asInt());
        assertEquals(1, loops.path("edges").size());
    }

    @Test void mixedSelfLoopsAreExcludedWithoutChangingOtherWeightedMetrics() throws Exception {
        ObjectNode baseline = graph("a", "b"); edge(baseline, "a", "b", 2); edge(baseline, "b", "a", 1);
        ObjectNode withLoop = baseline.deepCopy(); edge(withLoop, "a", "a", 1000);
        JavaScriptWeightedMetrics.enrich(baseline); JavaScriptWeightedMetrics.enrich(withLoop);
        assertEquals(baseline.path("metrics"), withLoop.path("metrics"));
        assertEquals(1000, withLoop.path("weightedMetrics").path("excludedSelfLoopWeight").asInt());
    }

    @Test void unavailableScopesAndMissingCoverageAreNotZeroAsHealthy() throws Exception {
        for (String status : List.of("unsupported", "not-applicable")) {
            ObjectNode graph = graph("a", "b"); edge(graph, "a", "b", 1); graph.put("status", status);
            JavaScriptWeightedMetrics.enrich(graph); assertUnavailable(graph, status);
        }
        ObjectNode missingModule = graph("a", "b"); edge(missingModule, "a", "b", 1); missingModule.put("status", "partial");
        JavaScriptWeightedMetrics.enrich(missingModule); assertUnavailable(missingModule, "partial");
        ObjectNode missingEvidence = graph("a", "b"); edge(missingEvidence, "a", "b", 1);
        missingEvidence.putObject("coverage").put("snapshotsWithArchitecture", 1).put("snapshotsTotal", 2);
        JavaScriptWeightedMetrics.enrich(missingEvidence); assertUnavailable(missingEvidence, "partial");
        ObjectNode incompleteHttp = graph("a", "b").put("scope", "declared-applications-and-clients").put("status", "partial");
        edge(incompleteHttp, "a", "b", 1); incompleteHttp.putObject("coverage").put("matchingComplete", false);
        JavaScriptWeightedMetrics.enrich(incompleteHttp); assertUnavailable(incompleteHttp, "partial");
    }

    @Test void completeCandidateGraphRemainsPartialButReceivesMetricsAndWeightSemantics() throws Exception {
        ObjectNode graph = graph("api-a", "api-b").put("scope", "declared-backend-applications").put("status", "partial");
        edge(graph, "api-a", "api-b", 2);
        graph.putObject("coverage").put("matchingComplete", true);
        JavaScriptWeightedMetrics.enrich(graph);
        assertEquals("partial", graph.path("weightedMetrics").path("status").asText());
        assertTrue(graph.path("weightedMetrics").path("available").asBoolean());
        assertEquals(-0.5, value(graph, "avgSC"), EPSILON);
        assertTrue(graph.path("weightedMetrics").path("weightSemantics").asText().contains("not traffic"));
        assertEquals("declared-backend-applications", graph.path("scope").asText());
    }

    @Test void malformedWeightsDuplicatesAndReferencesFailClosed() throws Exception {
        for (JsonNode weight : List.of(JavaScriptExtractionService.JSON.getNodeFactory().numberNode(0),
                JavaScriptExtractionService.JSON.getNodeFactory().numberNode(-1),
                JavaScriptExtractionService.JSON.getNodeFactory().numberNode(1.5),
                JavaScriptExtractionService.JSON.getNodeFactory().numberNode(Long.MAX_VALUE))) {
            ObjectNode graph = graph("a", "b"); edge(graph, "a", "b", 1).set("observations", weight);
            assertThrows(IOException.class, () -> JavaScriptWeightedMetrics.enrich(graph));
        }
        ObjectNode missingWeight = graph("a", "b"); edge(missingWeight, "a", "b", 1).remove("observations");
        assertThrows(IOException.class, () -> JavaScriptWeightedMetrics.enrich(missingWeight));
        ObjectNode dangling = graph("a"); edge(dangling, "a", "b", 1);
        assertThrows(IOException.class, () -> JavaScriptWeightedMetrics.enrich(dangling));
        ObjectNode duplicateNode = graph("a", "a"); assertThrows(IOException.class, () -> JavaScriptWeightedMetrics.enrich(duplicateNode));
        ObjectNode duplicateEdge = graph("a", "b"); edge(duplicateEdge, "a", "b", 1); edge(duplicateEdge, "b", "a", 1).put("id", "edge-0");
        assertThrows(IOException.class, () -> JavaScriptWeightedMetrics.enrich(duplicateEdge));
    }

    @Test void inputOrderingDoesNotChangeMetricsAndEnrichmentIsIdempotent() throws Exception {
        ObjectNode a = graph("z", "a", "m"); edge(a, "z", "a", 7); edge(a, "a", "z", 2); edge(a, "a", "m", 3);
        ObjectNode b = graph("m", "a", "z"); edge(b, "a", "m", 3); edge(b, "a", "z", 2); edge(b, "z", "a", 7);
        JavaScriptWeightedMetrics.enrich(a); JavaScriptWeightedMetrics.enrich(b);
        assertEquals(a.path("metrics"), b.path("metrics")); assertEquals(a.path("weightedMetrics"), b.path("weightedMetrics"));
        JsonNode before = a.deepCopy(); JavaScriptWeightedMetrics.enrich(a); assertEquals(before, a);
    }

    @Test void sparseThirtyThousandNodeGraphDoesNotEnumerateAllVertexPairsOrRecurse() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            ObjectNode graph = graph();
            for (int i = 0; i < 30_000; i++) graph.withArray("nodes").addObject().put("id", "n" + i);
            for (int i = 1; i < 30_000; i++) edge(graph, "n" + (i - 1), "n" + i, 1);
            JavaScriptWeightedMetrics.enrich(graph);
            assertEquals(29_999, graph.path("weightedMetrics").path("evaluatedOrderedPairs").asInt());
            assertEquals(-1.0, value(graph, "avgSC"), EPSILON);
            assertEquals(0.0, value(graph, "SCCmodularity"), EPSILON);
        });
    }

    private static void assertUnavailable(JsonNode graph, String status) {
        assertEquals(status, graph.path("weightedMetrics").path("status").asText());
        assertFalse(graph.path("weightedMetrics").path("available").asBoolean());
        for (String metric : List.of("maxSC", "avgSC", "stdSC", "maxLWF", "avgLWF", "stdLWF", "maxGWF", "avgGWF", "stdGWF", "SCCmodularity"))
            assertTrue(graph.path("metrics").path(metric).isNull(), metric);
    }

    /** Small-fixture reference follows the original ordered-pair loops, unlike production sparse indexing. */
    private static Map<String, Double> originalCouplingReference(JsonNode graph) {
        List<String> nodes = new ArrayList<>(); graph.path("nodes").forEach(n -> nodes.add(n.path("id").asText()));
        Map<List<String>, Double> outgoing = new HashMap<>(), degree = new HashMap<>();
        for (String a : nodes) for (String b : nodes) {
            if (a.equals(b)) continue;
            double forward = 0, reverse = 0;
            for (JsonNode edge : graph.path("edges")) {
                if (a.equals(edge.path("source").asText()) && b.equals(edge.path("target").asText())) forward += edge.path("observations").asDouble();
                if (b.equals(edge.path("source").asText()) && a.equals(edge.path("target").asText())) reverse += edge.path("observations").asDouble();
            }
            if (forward > 0) { outgoing.put(List.of(a, b), forward); degree.put(List.of(a, b), forward + reverse); }
        }
        double maximum = degree.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
        Map<String, List<Double>> values = new LinkedHashMap<>();
        for (String name : List.of("SC", "LWF", "GWF")) values.put(name, new ArrayList<>());
        outgoing.forEach((pair, weight) -> {
            double d = degree.get(pair), local = (1 + weight) / (1 + d), global = d / maximum;
            values.get("LWF").add(local); values.get("GWF").add(global); values.get("SC").add(1 - 1 / d - local * global);
        });
        Map<String, Double> results = new HashMap<>();
        values.forEach((name, observed) -> {
            double mean = observed.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            results.put("max" + name, observed.stream().mapToDouble(Double::doubleValue).max().orElseThrow()); results.put("avg" + name, mean);
            results.put("std" + name, Math.sqrt(observed.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElseThrow()));
        });
        return results;
    }

    /** Original graph preserves parallel edge records; production aggregates them without changing weighted modularity. */
    private static double jGraphTReference(JsonNode graph) {
        DirectedWeightedMultigraph<String, DefaultWeightedEdge> reference = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);
        graph.path("nodes").forEach(n -> reference.addVertex(n.path("id").asText()));
        for (JsonNode edge : graph.path("edges")) {
            String source = edge.path("source").asText(), target = edge.path("target").asText();
            if (!source.equals(target)) reference.setEdgeWeight(reference.addEdge(source, target), edge.path("observations").asDouble());
        }
        List<Set<String>> scc = new KosarajuStrongConnectivityInspector<>(reference).stronglyConnectedSets();
        return scc.size() == reference.vertexSet().size() ? 0
                : new UndirectedModularityMeasurer<>(new AsUndirectedGraph<>(reference)).modularity(scc);
    }
}
