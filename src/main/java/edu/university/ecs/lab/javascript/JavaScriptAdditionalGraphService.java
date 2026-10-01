package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Additional static graph observations; never executes analyzed code.
 * Weighted sums/thresholds port CIMET2 GreedyService, HubLikeService and AR20 at
 * 44dd7923eedfdbe51f602194cb8f351107556fc0. File graphs are module analogs;
 * declared application HTTP graphs are candidate links, not discovered service boundaries.
 * Function SCCs and bounded simple paths adapt its method-level cycle/chain concepts to
 * the resolved local JavaScript function graph. SCC membership is exhaustive for that graph;
 * witnesses and static call resolution are explicitly incomplete.
 */
public final class JavaScriptAdditionalGraphService {
    public record Options(int weightedThreshold, int ar20Threshold, int chainLength, int maxWitnesses,
                          int maxNodes, int maxEdges, int maxTraversalSteps) {
        public Options {
            if (weightedThreshold < 1 || ar20Threshold < 1 || chainLength < 2 || chainLength > 8
                    || maxWitnesses < 1 || maxWitnesses > 1000 || maxNodes < 1 || maxNodes > 100000
                    || maxEdges < 1 || maxEdges > 500000 || maxTraversalSteps < 1 || maxTraversalSteps > 10000000)
                throw new IllegalArgumentException("Invalid additional graph analysis limits");
        }
        public static Options defaults() { return new Options(6, 4, 3, 100, 30000, 150000, 1000000); }
    }

    private static final String FUNCTION_SCOPE = "resolved-local-functions";
    private static final Map<String, String> SCOPES = Map.of(
            "modules", "file-modules", "components", "declared-applications-and-clients",
            "backendApplications", "declared-backend-applications");
    private static final Set<String> STATUSES = Set.of("supported", "partial", "unsupported", "not-applicable");
    private final Options options;

    public JavaScriptAdditionalGraphService() { this(Options.defaults()); }
    public JavaScriptAdditionalGraphService(Options options) { this.options = Objects.requireNonNull(options); }

    /**
     * Validates a persisted additionalGraphs report by bounded deterministic recomputation from
     * its source graphs and its exact recorded options. This also rejects stale or manipulated
     * findings, scores, witness identities and coverage rather than trusting saved zero counts.
     */
    public static void validateSavedAnalysis(JsonNode fullAnalysis) throws IOException {
        if (fullAnalysis == null || !fullAnalysis.isObject() || !fullAnalysis.path("additionalGraphs").isObject())
            throw new IOException("Saved architecture analysis requires additionalGraphs");
        JsonNode saved = fullAnalysis.path("additionalGraphs"), limits = saved.path("limits");
        Set<String> expected = Set.of("definitionsVersion", "weightedThreshold", "ar20Threshold", "chainLengthNodes",
                "maxWitnessesPerKind", "maxNodesPerGraph", "maxEdgesPerGraph", "maxTraversalStepsPerWitnessKind");
        if (!limits.isObject() || limits.size() != expected.size()) throw new IOException("Invalid saved additional graph limits");
        Iterator<String> names = limits.fieldNames();
        while (names.hasNext()) if (!expected.contains(names.next())) throw new IOException("Unknown saved additional graph limit");
        for (String name : expected)
            if (!limits.path(name).isIntegralNumber() || !limits.path(name).canConvertToInt())
                throw new IOException("Additional graph limits must be bounded integers");
        if (limits.path("definitionsVersion").asInt() != 1) throw new IOException("Unsupported additional graph definitions version");
        Options options;
        try {
            options = new Options(limits.path("weightedThreshold").asInt(), limits.path("ar20Threshold").asInt(),
                    limits.path("chainLengthNodes").asInt(), limits.path("maxWitnessesPerKind").asInt(),
                    limits.path("maxNodesPerGraph").asInt(), limits.path("maxEdgesPerGraph").asInt(),
                    limits.path("maxTraversalStepsPerWitnessKind").asInt());
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid saved additional graph limits", invalid); }
        // JSON does not preserve Jackson's in-memory IntNode/LongNode distinction.
        // Limits remain strictly integral above; other numeric leaves compare by exact value.
        Comparator<JsonNode> values = (a, b) -> a.isNumber() && b.isNumber()
                ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1;
        if (!new JavaScriptAdditionalGraphService(options).analyze(fullAnalysis).equals(values, saved))
            throw new IOException("Saved additional graph report disagrees with its source graphs or recorded options");
    }

    /** Does not mutate input. Sorted identities, never source lines, determine finding identities. */
    public ObjectNode analyze(JsonNode fullAnalysis) throws IOException {
        if (fullAnalysis == null || !fullAnalysis.isObject() || fullAnalysis.path("schemaVersion").asInt() != 1
                || !fullAnalysis.path("analysis").asText().equals("static-javascript-architecture"))
            throw new IOException("Expected a version 1 static JavaScript architecture analysis");
        ObjectNode result = object().put("schemaVersion", 1).put("analysis", "static-javascript-additional-graphs");
        result.putObject("limits").put("definitionsVersion", 1).put("weightedThreshold", options.weightedThreshold)
                .put("ar20Threshold", options.ar20Threshold).put("chainLengthNodes", options.chainLength)
                .put("maxWitnessesPerKind", options.maxWitnesses).put("maxNodesPerGraph", options.maxNodes)
                .put("maxEdgesPerGraph", options.maxEdges).put("maxTraversalStepsPerWitnessKind", options.maxTraversalSteps);
        ArrayNode findings = result.putArray("findings");
        ObjectNode weighted = result.putObject("weighted");
        for (String key : List.of("modules", "components", "backendApplications"))
            weighted.set(key, weighted(fullAnalysis.path("graphs").path(key), key, findings));
        result.set("functionGraph", functions(fullAnalysis, findings));
        sort(findings);
        result.putArray("limitations")
                .add("Threshold results are review candidates, not demonstrated design defects or a degradation score.")
                .add("Weights count static source observations, not traffic or runtime execution frequency.")
                .add("Weighted self loops are excluded, matching the existing weighted metric projection; function recursion is included.")
                .add("Function graphs omit unresolved dispatch, omitted top-level calls, and unpropagated runtime callbacks/middleware.")
                .add("A function cycle may be intentional recursion. Simple chains are bounded examples, not exhaustive paths.")
                .add("Bounded witnesses are excluded from findings: changing a sample is not evidence that a chain or cycle was resolved.")
                .add("Missing extraction or incomplete HTTP matching yields unavailable weighted findings; empty observations do not prove absence in the running system.");
        return result;
    }

    private ObjectNode weighted(JsonNode graph, String key, ArrayNode findings) throws IOException {
        ObjectNode out = object().put("scope", SCOPES.get(key)).put("weightSemantics", "static source observations; not traffic")
                .put("selfLoopPolicy", "exclude from weighted sums; retain in original graph")
                .put("interpretation", key.equals("modules") ? "module analog" : "declared application candidate")
                .put("originalRevision", "44dd7923eedfdbe51f602194cb8f351107556fc0");
        out.putObject("thresholds").put("greedy", options.weightedThreshold).put("hub", options.weightedThreshold)
                .put("AR20", options.ar20Threshold);
        out.put("ar20Relationship", "Same incoming-weight family as hub; original AR20 uses threshold 4, normal hub uses 6.");
        ArrayNode scores = out.putArray("scores");
        ObjectNode counts = out.putObject("counts");
        for (String field : List.of("greedy", "hub", "AR20")) counts.putNull(field);
        if (graph.isMissingNode()) return unavailable(out, "unsupported", "This saved analysis has no graph for this scope.");
        String status = graph.path("status").asText();
        if (!graph.isObject() || !STATUSES.contains(status) || !SCOPES.get(key).equals(graph.path("scope").asText()))
            throw new IOException("Invalid additional weighted graph scope or status");
        TreeMap<String, JsonNode> nodes = index(graph.path("nodes"), options.maxNodes, "weighted node");
        TreeMap<String, JsonNode> edges = index(graph.path("edges"), options.maxEdges, "weighted edge");
        Map<String, Score> values = new TreeMap<>();
        for (String id : nodes.keySet()) values.put(id, new Score());
        long excludedWeight = 0, totalWeight = 0;
        int excludedRecords = 0;
        for (JsonNode edge : edges.values()) {
            String source = identity(edge, "source"), target = identity(edge, "target");
            if (!nodes.containsKey(source) || !nodes.containsKey(target)) throw new IOException("Dangling weighted graph endpoint");
            JsonNode observations = edge.path("observations");
            if (!observations.isIntegralNumber() || !observations.canConvertToInt() || observations.asInt() < 1)
                throw new IOException("Expected positive integer static observation weight");
            long weight = observations.asLong();
            if (source.equals(target)) { excludedWeight += weight; excludedRecords++; continue; }
            totalWeight += weight;
            Score from = values.get(source), to = values.get(target);
            from.outgoing += weight; to.incoming += weight;
            if (from.outEvidence.size() < 3) from.outEvidence.add(edgeEvidence(edge));
            if (to.inEvidence.size() < 3) to.inEvidence.add(edgeEvidence(edge));
        }
        out.put("nodes", nodes.size()).put("inputEdgeRecords", edges.size()).put("totalObservationWeight", totalWeight)
                .put("excludedSelfLoopRecords", excludedRecords).put("excludedSelfLoopWeight", excludedWeight);
        if (status.equals("unsupported")) return unavailable(out, status, "Source architecture is unavailable for this scope.");
        if (status.equals("not-applicable") || key.equals("backendApplications") && nodes.size() < 2)
            return unavailable(out, "not-applicable", "At least two declared backend applications are needed for inter-application findings.");
        JsonNode coverage = graph.path("coverage");
        if (!key.equals("modules") && !coverage.path("matchingComplete").asBoolean(false))
            return unavailable(out, "partial", "HTTP candidate matching was incomplete or its completion was not recorded; absence is not evaluated.");
        if (key.equals("modules") && (status.equals("partial")
                || coverage.path("snapshotsWithArchitecture").asInt(Integer.MAX_VALUE) < coverage.path("snapshotsTotal").asInt(0)))
            return unavailable(out, "partial", "A component module graph is missing; threshold counts are unavailable.");
        out.put("status", status).put("available", true).put("absenceProven", false)
                .put("reason", "Thresholds apply only to the extracted graph and its declared scope.");
        int greedy = 0, hub = 0, ar20 = 0;
        for (Map.Entry<String, Score> entry : values.entrySet()) {
            String id = entry.getKey(); Score score = entry.getValue();
            ObjectNode value = scores.addObject().put("id", id).put("incomingObservationWeight", score.incoming)
                    .put("outgoingObservationWeight", score.outgoing);
            value.set("source", provenance(nodes.get(id)));
            if (score.outgoing >= options.weightedThreshold) {
                greedy++; weightedFinding(findings, "weighted-greedy", "greedy", out.path("scope").asText(), id,
                        score.outgoing, options.weightedThreshold, nodes.get(id), score.outEvidence);
            }
            if (score.incoming >= options.weightedThreshold) {
                hub++; weightedFinding(findings, "weighted-hub", "hub", out.path("scope").asText(), id,
                        score.incoming, options.weightedThreshold, nodes.get(id), score.inEvidence);
            }
            if (score.incoming >= options.ar20Threshold) {
                ar20++; weightedFinding(findings, "AR20-weighted-hub", "AR20", out.path("scope").asText(), id,
                        score.incoming, options.ar20Threshold, nodes.get(id), score.inEvidence);
            }
        }
        counts.put("greedy", greedy).put("hub", hub).put("AR20", ar20);
        return out;
    }

    private static void weightedFinding(ArrayNode findings, String kind, String rule, String scope, String subject,
                                        long score, int threshold, JsonNode node, List<ObjectNode> edges) {
        ObjectNode finding = finding(kind, scope, List.of(subject));
        finding.put("rule", rule).put("family", kind.equals("weighted-greedy") ? "weighted-greedy" : "weighted-hub")
                .put("score", score).put("threshold", threshold).put("comparison", ">=").put("interpretation", "review candidate");
        ObjectNode evidence = finding.putObject("evidence"); evidence.set("node", provenance(node));
        ArrayNode observations = evidence.putArray("edgeSamples"); edges.forEach(observations::add);
        evidence.put("edgeSamplesLimit", 3);
        findings.add(finding);
    }

    private ObjectNode functions(JsonNode analysis, ArrayNode findings) throws IOException {
        ObjectNode out = object().put("scope", FUNCTION_SCOPE).put("edgeSemantics", "resolved static local caller-to-function links")
                .put("selfLoopPolicy", "include direct recursion as a cyclic singleton")
                .put("chainLengthNodes", options.chainLength).put("absenceProven", false);
        ArrayNode cyclic = out.putArray("cyclicComponents"), cycleWitnesses = out.putArray("cycleWitnesses"), chains = out.putArray("chainWitnesses");
        ObjectNode metrics = out.putObject("metrics");
        TreeMap<String, JsonNode> nodes = index(analysis.path("functions"), options.maxNodes, "function");
        TreeMap<String, JsonNode> calls = index(analysis.path("functionCalls"), options.maxEdges, "function call");
        TreeMap<String, JsonNode> components = index(analysis.path("components"), options.maxNodes, "component");
        if (!analysis.path("sourceCoverage").isArray() || analysis.path("sourceCoverage").size() > options.maxNodes)
            throw new IOException("Invalid source coverage");
        Set<String> covered = new TreeSet<>();
        long unresolved = 0, omitted = 0;
        boolean countersComplete = true;
        for (JsonNode record : analysis.path("sourceCoverage")) {
            String component = identity(record, "component");
            if (!components.containsKey(component) || !covered.add(component)) throw new IOException("Invalid source coverage component");
            for (String field : List.of("unresolvedCallSites", "resolvedTopLevelCallsOmitted")) {
                JsonNode count = record.path(field);
                if (count.isMissingNode()) { countersComplete = false; continue; }
                if (!count.isIntegralNumber() || !count.canConvertToInt() || count.asInt() < 0) throw new IOException("Invalid call coverage count");
                if (field.equals("unresolvedCallSites")) unresolved += count.asLong(); else omitted += count.asLong();
            }
        }
        ObjectNode coverage = out.putObject("coverage").put("componentsWithArchitecture", covered.size())
                .put("componentsTotal", components.size()).put("unresolvedCallSites", unresolved)
                .put("resolvedTopLevelCallsOmitted", omitted).put("coverageCountersComplete", countersComplete);
        ArrayNode missing = coverage.putArray("componentsWithoutArchitecture");
        components.keySet().stream().filter(id -> !covered.contains(id)).forEach(missing::add);
        Map<String, SortedSet<String>> adjacency = new TreeMap<>(), reverse = new TreeMap<>();
        for (Map.Entry<String, JsonNode> node : nodes.entrySet()) {
            if (!covered.contains(identity(node.getValue(), "component"))) throw new IOException("Function belongs to a component without source coverage");
            adjacency.put(node.getKey(), new TreeSet<>()); reverse.put(node.getKey(), new TreeSet<>());
        }
        Map<Pair, JsonNode> evidence = new HashMap<>();
        int selfCalls = 0;
        for (JsonNode call : calls.values()) {
            String source = identity(call, "callerId"), target = identity(call, "targetId");
            if (!nodes.containsKey(source) || !nodes.containsKey(target)) throw new IOException("Dangling resolved function call");
            if (!identity(nodes.get(source), "component").equals(identity(call, "component"))
                    || !identity(nodes.get(source), "component").equals(identity(nodes.get(target), "component")))
                throw new IOException("Resolved local function call crosses declared component boundaries");
            adjacency.get(source).add(target); reverse.get(target).add(source);
            evidence.putIfAbsent(new Pair(source, target), call);
            if (source.equals(target)) selfCalls++;
        }
        out.put("nodes", nodes.size()).put("callObservations", calls.size()).put("edges", evidence.size());
        if (covered.isEmpty()) {
            for (String key : List.of("sccCount", "cyclicSccCount", "cyclicFunctions", "recursiveFunctions", "selfCallObservations")) metrics.putNull(key);
            out.put("cycleWitnessesTruncated", false).put("chainWitnessesTruncated", false);
            return unavailable(out, "unsupported", "No component has an extracted architecture/function graph; empty data is not a clean result.");
        }
        out.put("status", "partial").put("available", true)
                .put("reason", "SCC membership is complete only for the resolved local function graph; static call coverage is incomplete.");
        List<List<String>> groups = stronglyConnected(adjacency, reverse);
        int cyclicFunctions = 0, recursiveFunctions = 0;
        Budget cycleBudget = new Budget(options.maxTraversalSteps);
        boolean cyclesTruncated = false;
        for (List<String> members : groups) {
            boolean recursion = members.size() == 1 && adjacency.get(members.getFirst()).contains(members.getFirst());
            for (String member : members) if (adjacency.get(member).contains(member)) recursiveFunctions++;
            if (members.size() == 1 && !recursion) continue;
            cyclicFunctions += members.size();
            ObjectNode group = object().put("id", stable("function-cycle", FUNCTION_SCOPE, members)).put("directRecursionOnly", recursion);
            ArrayNode memberArray = group.putArray("members"); members.forEach(memberArray::add);
            cyclic.add(group);
            ObjectNode finding = finding("function-cycle", FUNCTION_SCOPE, members).put("family", "function-cycle")
                    .put("interpretation", "resolved call-cycle observation; recursion may be intentional");
            ArrayNode sample = finding.putObject("evidence").putArray("functions");
            members.stream().limit(8).map(nodes::get).map(JavaScriptAdditionalGraphService::provenance).forEach(sample::add);
            finding.withObject("evidence").put("functionSamplesLimit", 8).put("totalFunctions", members.size());
            findings.add(finding);
            if (cycleWitnesses.size() >= options.maxWitnesses || cycleBudget.exhausted) { cyclesTruncated = true; continue; }
            List<String> witness = cycle(members, adjacency, cycleBudget);
            if (witness == null) { cyclesTruncated = true; continue; }
            cycleWitnesses.add(witness("function-cycle-witness", witness, nodes, evidence));
        }
        metrics.put("sccCount", groups.size()).put("cyclicSccCount", cyclic.size()).put("cyclicFunctions", cyclicFunctions)
                .put("recursiveFunctions", recursiveFunctions).put("selfCallObservations", selfCalls);
        Budget chainBudget = new Budget(options.maxTraversalSteps);
        boolean chainsTruncated = chains(adjacency, nodes, evidence, chains, chainBudget);
        out.put("cycleWitnessesTruncated", cyclesTruncated).put("cycleTraversalSteps", cycleBudget.used)
                .put("cycleTraversalBoundReached", cycleBudget.exhausted).put("chainWitnessesTruncated", chainsTruncated)
                .put("chainTraversalSteps", chainBudget.used).put("chainTraversalBoundReached", chainBudget.exhausted)
                .put("chainWitnessCount", chains.size()).put("cycleWitnessCount", cycleWitnesses.size())
                .put("chainWitnessesAreFindings", false).put("sccMembershipCompleteForObservedGraph", true);
        return out;
    }

    /** Iterative Kosaraju: O(V+E), separate from optional bounded witness searches. */
    private static List<List<String>> stronglyConnected(Map<String, SortedSet<String>> adjacency, Map<String, SortedSet<String>> reverse) {
        Set<String> seen = new HashSet<>(); List<String> finished = new ArrayList<>();
        for (String start : adjacency.keySet()) {
            if (!seen.add(start)) continue;
            Deque<Frame> stack = new ArrayDeque<>(); stack.push(new Frame(start, adjacency.get(start).iterator()));
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!frame.next.hasNext()) { finished.add(frame.node); stack.pop(); continue; }
                String next = frame.next.next();
                if (seen.add(next)) stack.push(new Frame(next, adjacency.get(next).iterator()));
            }
        }
        seen.clear(); List<List<String>> groups = new ArrayList<>();
        for (int i = finished.size() - 1; i >= 0; i--) {
            String start = finished.get(i); if (!seen.add(start)) continue;
            Deque<String> stack = new ArrayDeque<>(); stack.push(start); List<String> members = new ArrayList<>();
            while (!stack.isEmpty()) {
                String node = stack.pop(); members.add(node);
                for (String next : reverse.get(node)) if (seen.add(next)) stack.push(next);
            }
            Collections.sort(members); groups.add(members);
        }
        groups.sort(Comparator.comparing(List::getFirst)); return groups;
    }

    /** At most one closed witness per SCC, preserving recursion as [function,function]. */
    private static List<String> cycle(List<String> members, Map<String, SortedSet<String>> adjacency, Budget budget) {
        String start = members.getFirst(); Set<String> allowed = new HashSet<>(members);
        Deque<String> queue = new ArrayDeque<>(); Map<String, String> parent = new HashMap<>();
        queue.add(start); parent.put(start, null);
        while (!queue.isEmpty()) {
            String node = queue.remove();
            for (String next : adjacency.get(node)) {
                if (!budget.take()) return null;
                if (!allowed.contains(next)) continue;
                if (next.equals(start)) {
                    LinkedList<String> path = new LinkedList<>();
                    for (String step = node; step != null; step = parent.get(step)) path.addFirst(step);
                    path.add(start); return path;
                }
                if (!parent.containsKey(next)) { parent.put(next, node); queue.add(next); }
            }
        }
        return null;
    }

    /** Iterative bounded simple-path search. Discovering a (limit+1)th witness marks truncation. */
    private boolean chains(Map<String, SortedSet<String>> adjacency, Map<String, JsonNode> nodes,
                           Map<Pair, JsonNode> evidence, ArrayNode output, Budget budget) {
        for (String start : adjacency.keySet()) {
            if (!budget.take()) return true;
            List<String> path = new ArrayList<>(); Set<String> onPath = new HashSet<>();
            Deque<Frame> stack = new ArrayDeque<>();
            path.add(start); onPath.add(start); stack.push(new Frame(start, adjacency.get(start).iterator()));
            while (!stack.isEmpty()) {
                Frame current = stack.peek();
                if (!current.next.hasNext()) { stack.pop(); onPath.remove(path.removeLast()); continue; }
                if (!budget.take()) return true;
                String next = current.next.next(); if (onPath.contains(next)) continue;
                path.add(next); onPath.add(next);
                if (path.size() == options.chainLength) {
                    if (output.size() == options.maxWitnesses) return true;
                    output.add(witness("function-chain-witness", path, nodes, evidence));
                    onPath.remove(path.removeLast());
                } else stack.push(new Frame(next, adjacency.get(next).iterator()));
            }
        }
        return false;
    }

    private static ObjectNode witness(String kind, List<String> path, Map<String, JsonNode> nodes, Map<Pair, JsonNode> edges) {
        ObjectNode output = object().put("id", stable(kind, FUNCTION_SCOPE, path)).put("scope", FUNCTION_SCOPE);
        ArrayNode ids = output.putArray("nodes"), nodeEvidence = output.putArray("functions"), edgeEvidence = output.putArray("calls");
        for (String node : path) { ids.add(node); nodeEvidence.add(provenance(nodes.get(node))); }
        for (int i = 1; i < path.size(); i++) edgeEvidence.add(provenance(edges.get(new Pair(path.get(i - 1), path.get(i)))));
        return output;
    }

    private static ObjectNode finding(String kind, String scope, List<String> subjects) {
        ObjectNode output = object().put("id", stable(kind, scope, subjects)).put("kind", kind).put("scope", scope);
        ArrayNode values = output.putArray("subjects"); subjects.forEach(values::add); return output;
    }
    private static ObjectNode edgeEvidence(JsonNode edge) {
        ObjectNode result = provenance(edge);
        if (edge.path("evidence").isObject()) result.set("sourceEvidence", provenance(edge.path("evidence")));
        return result;
    }
    private static ObjectNode provenance(JsonNode source) {
        ObjectNode result = object();
        for (String field : List.of("id", "component", "file", "line", "endLine", "name", "kind", "source", "target", "observations", "callerId", "targetId", "dependencyId", "callId"))
            if (source.has(field) && (source.path(field).isTextual() || source.path(field).isIntegralNumber())) result.set(field, source.path(field));
        return result;
    }
    private static ObjectNode unavailable(ObjectNode output, String status, String reason) {
        return output.put("status", status).put("available", false).put("absenceProven", false).put("reason", reason);
    }
    private static TreeMap<String, JsonNode> index(JsonNode array, int maximum, String label) throws IOException {
        if (!array.isArray() || array.size() > maximum) throw new IOException("Invalid or oversized " + label + " array");
        TreeMap<String, JsonNode> result = new TreeMap<>();
        for (JsonNode node : array) {
            if (!node.isObject() || result.putIfAbsent(identity(node, "id"), node) != null) throw new IOException("Duplicate " + label + " identity");
        }
        return result;
    }
    private static String identity(JsonNode source, String field) throws IOException {
        JsonNode value = source.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 4096
                || value.asText().chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid graph identity: " + field);
        return value.asText();
    }
    private static String stable(String kind, String scope, List<String> subjects) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : concat(kind, scope, subjects)) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII)); digest.update((byte) ':'); digest.update(bytes);
            }
            return "additional-" + HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static List<String> concat(String kind, String scope, List<String> subjects) {
        List<String> values = new ArrayList<>(subjects.size() + 2); values.add(kind); values.add(scope); values.addAll(subjects); return values;
    }
    private static ObjectNode object() { return JavaScriptExtractionService.JSON.createObjectNode(); }
    private static void sort(ArrayNode array) {
        List<JsonNode> sorted = new ArrayList<>(); array.forEach(sorted::add);
        sorted.sort(Comparator.comparing(n -> n.path("id").asText())); array.removeAll(); sorted.forEach(array::add);
    }
    private static final class Score {
        long incoming, outgoing;
        final List<ObjectNode> inEvidence = new ArrayList<>(), outEvidence = new ArrayList<>();
    }
    private static final class Budget {
        final int maximum; int used; boolean exhausted;
        Budget(int maximum) { this.maximum = maximum; }
        boolean take() { if (used >= maximum) { exhausted = true; return false; } used++; return true; }
    }
    private record Pair(String source, String target) { }
    private record Frame(String node, Iterator<String> next) { }
}
