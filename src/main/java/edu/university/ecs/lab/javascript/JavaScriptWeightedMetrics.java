package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jgrapht.alg.clustering.UndirectedModularityMeasurer;
import org.jgrapht.alg.connectivity.KosarajuStrongConnectivityInspector;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;

import java.io.IOException;
import java.util.*;

/**
 * CIMET2 weighted structural coupling and SCC-partition modularity on an observed JSON graph.
 * Formulas match CIMET2 revision 44dd7923eedfdbe51f602194cb8f351107556fc0,
 * StructuralCoupling and ConnectedComponentsModularity. The observations used as edge weights
 * are static source occurrences, never runtime traffic. File graphs remain module analogs.
 *
 * <p>Self loops are excluded from this projection, consistent with the original nonself-pair
 * coupling calculation and loop-free DirectedWeightedMultigraph. The enclosing architecture
 * graph retains its self-loop observations. Empty/inapplicable/incompletely extracted graphs
 * produce null metrics and an explicit status instead of the older implementation's zero defaults.
 */
public final class JavaScriptWeightedMetrics {
    private static final int MAX_NODES = 100_000;
    private static final int MAX_EDGES = 500_000;
    private static final List<String> METRICS = List.of(
            "maxSC", "avgSC", "stdSC", "maxLWF", "avgLWF", "stdLWF", "maxGWF", "avgGWF", "stdGWF", "SCCmodularity");

    private JavaScriptWeightedMetrics() { }

    /**
     * Adds metrics and weightedMetrics provenance to an existing graph without changing its scope,
     * original nodes/edges, or other metrics. Uses O((V+E) log(V+E)) sorting/indexing and O(V+E)
     * graph algorithms; never enumerates absent vertex pairs or expands occurrence weights into edges.
     */
    public static void enrich(ObjectNode graph) throws IOException {
        if (graph == null || !graph.path("nodes").isArray() || !graph.path("edges").isArray()
                || graph.path("nodes").size() > MAX_NODES || graph.path("edges").size() > MAX_EDGES)
            throw new IOException("Invalid or oversized weighted graph input");
        String status = graph.path("status").asText();
        if (!Set.of("supported", "partial", "unsupported", "not-applicable").contains(status))
            throw new IOException("Weighted graph requires an explicit capability status");
        if (graph.has("metrics") && !graph.path("metrics").isObject()) throw new IOException("Invalid weighted graph metrics object");
        TreeSet<String> nodes = new TreeSet<>();
        for (JsonNode node : graph.path("nodes"))
            if (!node.isObject() || !nodes.add(identity(node, "id"))) throw new IOException("Duplicate weighted graph node identity");

        SortedMap<Pair, Long> weights = new TreeMap<>();
        Set<String> edgeIds = new HashSet<>();
        int selfLoopRecords = 0;
        long excludedSelfLoopWeight = 0, totalWeight = 0;
        for (JsonNode edge : graph.path("edges")) {
            if (!edge.isObject() || !edgeIds.add(identity(edge, "id"))) throw new IOException("Duplicate weighted graph edge identity");
            String source = identity(edge, "source"), target = identity(edge, "target");
            if (!nodes.contains(source) || !nodes.contains(target)) throw new IOException("Dangling weighted graph endpoint");
            JsonNode count = edge.path("observations");
            if (!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 1)
                throw new IOException("Weighted graph observations must be positive integer source counts");
            long weight = count.asLong();
            if (source.equals(target)) { selfLoopRecords++; excludedSelfLoopWeight += weight; continue; }
            weights.merge(new Pair(source, target), weight, Long::sum);
            totalWeight += weight;
        }

        ObjectNode metrics = graph.withObject("metrics");
        METRICS.forEach(metrics::putNull);
        ObjectNode metadata = graph.putObject("weightedMetrics");
        metadata.put("schemaVersion", 1).put("scope", graph.path("scope").asText())
                .put("weightSemantics", "edge.observations counts static source observations; not traffic, execution frequency, or request volume")
                .put("selfLoopPolicy", "excluded from weighted coupling and SCC-modularity projection; retained in the enclosing graph")
                .put("originalRevision", "44dd7923eedfdbe51f602194cb8f351107556fc0")
                .put("modularityImplementation", "JGraphT 1.5.2: Kosaraju SCC partition, AsUndirectedGraph, UndirectedModularityMeasurer")
                .put("normalization", "SC/LWF/GWF summaries count existing ordered nonself pairs once; reciprocal directions count separately")
                .put("singletonPartitionConvention", "SCCmodularity is zero when every SCC is a singleton, matching CIMET2")
                .put("emptyGraphConvention", "No eligible nonself edges yields null metrics, not the legacy zero defaults")
                .put("inputEdgeRecords", graph.path("edges").size()).put("evaluatedOrderedPairs", weights.size())
                .put("totalObservationWeight", totalWeight).put("excludedSelfLoopRecords", selfLoopRecords)
                .put("excludedSelfLoopWeight", excludedSelfLoopWeight);
        if (status.equals("unsupported") || status.equals("not-applicable")) {
            unavailable(metadata, status, "The enclosing graph is " + status + "; weighted results cannot be interpreted.");
            return;
        }
        JsonNode coverage = graph.path("coverage");
        if (coverage.path("matchingComplete").isBoolean() && !coverage.path("matchingComplete").asBoolean()) {
            unavailable(metadata, "partial", "HTTP matching work was incomplete; weighted values are unavailable rather than treating unsearched links as absent.");
            return;
        }
        if (graph.path("scope").asText().equals("file-modules") && (status.equals("partial")
                || coverage.path("snapshotsWithArchitecture").isIntegralNumber() && coverage.path("snapshotsTotal").isIntegralNumber()
                && coverage.path("snapshotsWithArchitecture").asLong() < coverage.path("snapshotsTotal").asLong())) {
            unavailable(metadata, "partial", "One or more component module graphs are missing; absence of evidence is not an empty graph.");
            return;
        }
        if (weights.isEmpty()) {
            unavailable(metadata, "not-applicable", "No eligible nonself edges: pair coupling and weighted modularity are undefined for this projection.");
            return;
        }

        long maxPairDegree = 0;
        for (Map.Entry<Pair, Long> edge : weights.entrySet())
            maxPairDegree = Math.max(maxPairDegree, edge.getValue() + weights.getOrDefault(edge.getKey().reverse(), 0L));
        Statistics sc = new Statistics(), lwf = new Statistics(), gwf = new Statistics();
        for (Map.Entry<Pair, Long> edge : weights.entrySet()) {
            double outgoing = edge.getValue();
            double degree = outgoing + weights.getOrDefault(edge.getKey().reverse(), 0L);
            double local = (1.0 + outgoing) / (1.0 + degree);
            double global = degree / maxPairDegree;
            double structural = 1.0 - 1.0 / degree - local * global;
            lwf.add(local); gwf.add(global); sc.add(structural);
        }
        sc.write(metrics, "SC"); lwf.write(metrics, "LWF"); gwf.write(metrics, "GWF");

        DirectedWeightedMultigraph<String, DefaultWeightedEdge> weighted = new DirectedWeightedMultigraph<>(DefaultWeightedEdge.class);
        nodes.forEach(weighted::addVertex);
        for (Map.Entry<Pair, Long> edge : weights.entrySet()) {
            DefaultWeightedEdge added = weighted.addEdge(edge.getKey().source, edge.getKey().target);
            weighted.setEdgeWeight(added, edge.getValue());
        }
        List<Set<String>> partition = new KosarajuStrongConnectivityInspector<>(weighted).stronglyConnectedSets();
        double modularity = partition.size() == nodes.size() ? 0.0
                : new UndirectedModularityMeasurer<>(new AsUndirectedGraph<>(weighted)).modularity(partition);
        if (!Double.isFinite(modularity)) throw new IOException("Weighted modularity was nonfinite");
        metrics.put("SCCmodularity", modularity);
        metadata.put("status", status).put("available", true).put("sccPartitionCount", partition.size())
                .put("maxPairObservationDegree", maxPairDegree)
                .put("reason", status.equals("partial")
                        ? "Computed on the observed candidate graph; deployment routing and runtime behavior remain unverified."
                        : "Computed on the extracted graph with explicitly defined static-observation weights.");
    }

    private static void unavailable(ObjectNode metadata, String status, String reason) {
        metadata.put("status", status).put("available", false).put("reason", reason);
    }

    private static String identity(JsonNode record, String field) throws IOException {
        JsonNode value = record.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 4096
                || value.asText().chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid weighted graph identity");
        return value.asText();
    }

    private record Pair(String source, String target) implements Comparable<Pair> {
        Pair reverse() { return new Pair(target, source); }
        @Override public int compareTo(Pair other) {
            int sourceOrder = source.compareTo(other.source);
            return sourceOrder == 0 ? target.compareTo(other.target) : sourceOrder;
        }
    }

    /** Stable population moments; preserving a negative maximum is intentional. */
    private static final class Statistics {
        private int count;
        private double mean, squaredDifferences, maximum = Double.NEGATIVE_INFINITY;
        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            squaredDifferences += delta * (value - mean);
            maximum = Math.max(maximum, value);
        }
        void write(ObjectNode output, String name) {
            output.put("max" + name, maximum).put("avg" + name, mean)
                    .put("std" + name, Math.sqrt(Math.max(0.0, squaredDifferences / count)));
        }
    }
}
