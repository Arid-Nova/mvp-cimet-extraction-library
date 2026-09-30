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
 * Bounded, deterministic architecture observations over trusted static snapshots, never target execution.
 * Degree formulas follow CIMET2 DegreeCoupling; scopes and graph construction are explicitly different.
 * See docs/javascript-architecture.md for provenance, semantics and unsupported legacy analyses.
 */
public final class JavaScriptArchitectureService {
    public record Options(int degreeThreshold, int chainLength, int maxWitnesses, int maxNodes,
                          int maxEdges, int maxMatchComparisons, int maxTraversalSteps) {
        public Options {
            if (degreeThreshold < 1 || chainLength < 2 || chainLength > 8 || maxWitnesses < 1 || maxWitnesses > 1000
                    || maxNodes < 1 || maxNodes > 100000 || maxEdges < 1 || maxEdges > 500000
                    || maxMatchComparisons < 1 || maxMatchComparisons > 20000000
                    || maxTraversalSteps < 1 || maxTraversalSteps > 10000000)
                throw new IllegalArgumentException("Invalid architecture analysis limits");
        }
        public static Options defaults() { return new Options(6, 3, 100, 30000, 150000, 5000000, 1000000); }
    }

    private static final String ANALYSIS = "static-javascript-architecture";
    private final Options options;
    public JavaScriptArchitectureService() { this(Options.defaults()); }
    public JavaScriptArchitectureService(Options options) { this.options = Objects.requireNonNull(options); }

    public ObjectNode analyze(List<? extends JsonNode> snapshots, String name) throws IOException {
        if (snapshots == null || snapshots.isEmpty() || snapshots.size() > options.maxNodes
                || name == null || name.isBlank() || name.length() > 256)
            throw new IllegalArgumentException("Snapshots and a nonblank analysis name are required");
        ObjectNode result = object().put("schemaVersion", 1).put("analysis", ANALYSIS).put("name", name);
        ArrayNode components = result.putArray("components"), findings = result.putArray("findings"), coverage = result.putArray("sourceCoverage");
        ArrayNode functions = result.putArray("functions"), functionCalls = result.putArray("functionCalls");
        ArrayNode handlers = result.putArray("endpointHandlers");
        Graph modules = new Graph("file-modules"), componentGraph = new Graph("declared-applications-and-clients");
        Graph backends = new Graph("declared-backend-applications");
        List<Item> endpoints = new ArrayList<>(), calls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int withArchitecture = 0, dependencyExternal = 0, dependencyUnresolved = 0, dependencyTypeOnly = 0, dependencyRecords = 0;
        List<JsonNode> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator.comparing(s -> componentKey(s.path("component"))));
        for (JsonNode snapshot : ordered) {
            JavaScriptExtractionService.validateSnapshot(snapshot);
            if ((long) endpoints.size() + calls.size() + snapshot.path("endpoints").size() + snapshot.path("calls").size() > options.maxEdges)
                throw new IOException("Architecture HTTP input exceeds record limit");
            JsonNode info = snapshot.path("component");
            String component = componentKey(info);
            if (!seen.add(component)) throw new IOException("Duplicate architecture component");
            ObjectNode componentNode = object().put("id", component).put("name", info.path("name").asText())
                    .put("kind", info.path("kind").asText()).put("boundary", "declared")
                    .put("boundaryInferred", false).put("commit", info.path("commit").asText(""));
            if (!info.path("repositoryURL").asText().isBlank())
                componentNode.put("repositoryIdentity", id(info.path("repositoryURL").asText()));
            components.add(componentNode);
            componentGraph.node(componentNode);
            if ("backend".equals(info.path("kind").asText())) backends.node(componentNode);
            JsonNode architecture = snapshot.path("architecture");
            if (!architecture.isMissingNode()) {
                validateArchitecture(architecture);
                dependencyRecords += architecture.path("dependencies").size();
                if ((long) modules.nodes.size() + functions.size() + architecture.path("modules").size() + architecture.path("functions").size() > options.maxNodes
                        || (long) functionCalls.size() + architecture.path("calls").size() > options.maxEdges
                        || (long) handlers.size() + architecture.path("endpointHandlers").size() > options.maxEdges
                        || dependencyRecords > options.maxEdges) throw new IOException("Architecture input exceeds graph record limits");
                withArchitecture++;
                ObjectNode componentCoverage = coverage.addObject().put("component", component);
                for (String key : List.of("modules", "dependencies", "resolvedRuntimeDependencies", "typeOnlyDependencies",
                        "externalDependencies", "unresolvedDependencies", "functions", "candidateCallSites", "resolvedLocalCalls",
                        "unresolvedCallSites", "resolvedTopLevelCallsOmitted", "endpointHandlerBindings", "endpointsWithResolvedHandlers"))
                    if (architecture.path("summary").path(key).isNumber()) componentCoverage.set(key, architecture.path("summary").path(key));
                Set<String> localModules = new HashSet<>(), localFunctions = new HashSet<>();
                for (JsonNode module : sorted(architecture.path("modules"))) {
                    String file = module.path("id").asText();
                    if (!localModules.add(file)) throw new IOException("Duplicate architecture module");
                    ObjectNode node = evidence(module, component).put("id", qualified(component, file));
                    modules.node(node);
                }
                for (JsonNode dependency : sorted(architecture.path("dependencies"))) {
                    if (!localModules.contains(dependency.path("source").asText()))
                        throw new IOException("Architecture dependency source is missing");
                    if (dependency.path("typeOnly").asBoolean()) { dependencyTypeOnly++; continue; }
                    String resolution = dependency.path("resolution").asText();
                    if ("external".equals(resolution)) { dependencyExternal++; continue; }
                    if (!"resolved".equals(resolution)) { dependencyUnresolved++; continue; }
                    String source = dependency.path("source").asText(), target = dependency.path("target").asText();
                    if (!localModules.contains(target)) throw new IOException("Architecture dependency target is missing");
                    modules.edge(qualified(component, source), qualified(component, target),
                            evidence(dependency, component).put("file", source).put("dependencyId", dependency.path("id").asText()));
                }
                for (JsonNode function : sorted(architecture.path("functions"))) {
                    String functionId = function.path("id").asText();
                    if (!localFunctions.add(functionId) || !localModules.contains(function.path("file").asText()))
                        throw new IOException("Invalid architecture function reference");
                    ObjectNode copy = evidence(function, component).put("id", qualified(component, functionId));
                    copy.put("name", function.path("name").asText());
                    copy.put("bodyFingerprint", function.path("bodyFingerprint").asText());
                    if (function.has("unresolvedCalls")) copy.set("unresolvedCalls", function.path("unresolvedCalls"));
                    functions.add(copy);
                }
                for (JsonNode call : sorted(architecture.path("calls"))) {
                    if (!localFunctions.contains(call.path("callerId").asText())
                            || !localFunctions.contains(call.path("targetId").asText()))
                        throw new IOException("Invalid resolved function call reference");
                    functionCalls.add(evidence(call, component).put("id", qualified(component, call.path("id").asText()))
                            .put("callerId", qualified(component, call.path("callerId").asText()))
                            .put("targetId", qualified(component, call.path("targetId").asText())));
                }
                Set<String> endpointIds = index(snapshot.path("endpoints")).keySet();
                for (JsonNode handler : architecture.path("endpointHandlers")) {
                    if (!endpointIds.contains(handler.path("endpointId").asText())
                            || !localFunctions.contains(handler.path("functionId").asText()))
                        throw new IOException("Invalid endpoint handler reference");
                    handlers.add(object().put("endpointId", qualified(component, handler.path("endpointId").asText()))
                            .put("functionId", qualified(component, handler.path("functionId").asText())));
                }
            }
            // Unknown routing configuration is conservatively case-sensitive. False must be explicit evidence/configuration.
            boolean caseSensitive = !architecture.path("caseSensitiveRouting").isBoolean()
                    || architecture.path("caseSensitiveRouting").asBoolean();
            for (JsonNode endpoint : sorted(snapshot.path("endpoints")))
                endpoints.add(new Item(component, qualified(component, endpoint.path("id").asText()), endpoint, caseSensitive,
                        architecture.path("caseSensitiveRouting").isBoolean()));
            for (JsonNode call : sorted(snapshot.path("calls")))
                calls.add(new Item(component, qualified(component, call.path("id").asText()), call, true, false));
            if (modules.nodes.size() + functions.size() > options.maxNodes || functionCalls.size() > options.maxEdges
                    || endpoints.size() + calls.size() > options.maxEdges)
                throw new IOException("Architecture input exceeds node or record limit");
        }
        ObjectNode http = matchHttp(endpoints, calls, componentGraph, backends, findings);
        result.set("http", http);
        ObjectNode graphs = result.putObject("graphs");
        String moduleStatus = withArchitecture == 0 ? "unsupported" : withArchitecture < snapshots.size() ? "partial" : "supported";
        graphs.set("modules", analyzeGraph(modules, moduleStatus, findings));
        graphs.set("components", analyzeGraph(componentGraph, "partial", findings));
        graphs.set("backendApplications", analyzeGraph(backends, backends.nodes.size() < 2 ? "not-applicable" : "partial", findings));
        graphs.withObject("modules").putObject("coverage").put("snapshotsWithArchitecture", withArchitecture)
                .put("snapshotsTotal", snapshots.size()).put("excludedTypeOnlyDependencies", dependencyTypeOnly)
                .put("externalDependencies", dependencyExternal).put("unresolvedDependencies", dependencyUnresolved);
        for (String scope : List.of("components", "backendApplications"))
            graphs.withObject(scope).putObject("coverage").put("matchingComplete", http.path("summary").path("matchingComplete").asBoolean());
        for (String scope : List.of("modules", "components", "backendApplications"))
            JavaScriptWeightedMetrics.enrich(graphs.withObject(scope));
        ObjectNode cohesion = new JavaScriptCohesionService().analyze(ordered);
        result.set("cohesion", cohesion);
        ObjectNode limits = result.putObject("limits");
        limits.put("degreeThreshold", options.degreeThreshold).put("chainLengthNodes", options.chainLength)
                .put("maxWitnessesPerGraph", options.maxWitnesses).put("maxNodes", options.maxNodes)
                .put("maxEdges", options.maxEdges).put("maxMatchComparisons", options.maxMatchComparisons)
                .put("maxTraversalStepsPerGraph", options.maxTraversalSteps);
        limits.put("metricDefinitionsVersion", 2);
        limits.set("cohesion", cohesion.path("limits").deepCopy());
        capabilities(result.putArray("capabilities"), moduleStatus, backends.nodes.size(), withArchitecture, graphs, cohesion);
        result.putArray("limitations")
                .add("Static observations are incomplete: dynamic loading, reflection, callbacks and runtime dispatch may be missing.")
                .add("Application boundaries are declared input components; files and endpoints are never promoted to services.")
                .add("HTTP links are method/path candidates, not verified routing; contract observations do not establish payload compatibility.")
                .add("No observed caller is a closed-world observation, not proof that an API is unused.")
                .add("Metrics and threshold candidates do not establish design defects, behavioral regressions or a universal degradation score.")
                .add("Cycle and chain witnesses are bounded examples, not exhaustive enumeration of all simple paths or cycles.");
        sortArray(findings, "id"); sortArray(handlers, "endpointId", "functionId");
        return result;
    }

    private ObjectNode matchHttp(List<Item> endpoints, List<Item> calls, Graph components, Graph backends, ArrayNode findings) {
        ObjectNode result = object();
        ArrayNode callRecords = result.putArray("calls"), endpointRecords = result.putArray("endpoints");
        Map<String, Integer> counts = new TreeMap<>();
        Set<String> observed = new HashSet<>();
        int comparisons = 0;
        long pathCharacters = 0, pathCharacterLimit = (long) options.maxMatchComparisons * 128;
        boolean workBoundReached = false;
        for (Item call : calls) {
            List<Item> matches = new ArrayList<>(), caseOnly = new ArrayList<>(), unsupportedTemplates = new ArrayList<>();
            String target = call.node.path("target").asText(), status;
            boolean bounded = false;
            if ("external".equals(target)) status = "external";
            else if (!"backend".equals(target) || !knownMethod(call.node) || !usablePath(call.node) || whollyDynamic(call.node.path("normalizedPath").asText())
                    || "unresolved".equals(call.node.path("resolution").asText())) status = "unknown";
            else {
                for (Item endpoint : endpoints) {
                    if (!endpoint.component.startsWith("backend:")) continue;
                    if (++comparisons > options.maxMatchComparisons) { bounded = true; break; }
                    if (usablePath(endpoint.node) && methodMatches(call.node, endpoint.node)) {
                        String a = call.node.path("normalizedPath").asText(), b = endpoint.node.path("normalizedPath").asText();
                        pathCharacters += a.length() + b.length();
                        if (pathCharacters > pathCharacterLimit) { bounded = true; break; }
                        if ((embeddedTemplate(a) || embeddedTemplate(b)) && pathMatches(coarsePath(a), coarsePath(b), endpoint.caseSensitive && endpoint.routingKnown)) unsupportedTemplates.add(endpoint);
                        else if (!embeddedTemplate(a) && !embeddedTemplate(b) && pathMatches(a, b, endpoint.caseSensitive)) matches.add(endpoint);
                        else if (!endpoint.routingKnown && pathMatches(a, b, false)) caseOnly.add(endpoint);
                    }
                }
                status = bounded || embeddedTemplate(call.node.path("normalizedPath").asText()) || !unsupportedTemplates.isEmpty() ? "unknown"
                        : matches.isEmpty() ? caseOnly.isEmpty() ? "unmatched" : "case-only-candidate"
                        : matches.size() == 1 && caseOnly.isEmpty() ? "matched" : "ambiguous";
            }
            workBoundReached |= bounded;
            ObjectNode record = evidence(call.node, call.component).put("id", call.id).put("status", status);
            copyHttp(call.node, record);
            ArrayNode candidates = record.putArray("candidateEndpointIds");
            // Incomplete searches cannot support absence or unique-match assertions.
            if (!bounded) for (Item endpoint : matches) { candidates.add(endpoint.id); observed.add(endpoint.id); }
            if (!bounded && !unsupportedTemplates.isEmpty()) {
                ArrayNode alternatives = record.putArray("unsupportedTemplateCandidateEndpointIds");
                for (Item endpoint : unsupportedTemplates) { alternatives.add(endpoint.id); observed.add(endpoint.id); }
                record.put("reason", "embedded-template-matching-unsupported");
            }
            if (embeddedTemplate(call.node.path("normalizedPath").asText())) record.put("reason", "embedded-template-matching-unsupported");
            if (!bounded && !caseOnly.isEmpty()) {
                ArrayNode alternatives = record.putArray("caseOnlyCandidateEndpointIds");
                for (Item endpoint : caseOnly) { alternatives.add(endpoint.id); observed.add(endpoint.id); }
                record.put("caseOnlyReason", "Routing case sensitivity is unknown; this is not an accepted case-insensitive match.");
            }
            if (bounded) record.put("reason", "matching-work-limit");
            if ("matched".equals(status)) {
                Item endpoint = matches.getFirst();
                boolean template = call.node.path("normalizedPath").asText().contains("{param}")
                        || endpoint.node.path("normalizedPath").asText().contains("{param}");
                record.put("matchKind", template ? "template-candidate" : "literal-candidate");
                record.put("targetComponent", endpoint.component);
                if (!call.component.equals(endpoint.component)) {
                    components.edge(call.component, endpoint.component, record);
                    if (backends.nodes.containsKey(call.component)) backends.edge(call.component, endpoint.component, record);
                }
            }
            if ("unmatched".equals(status) || "ambiguous".equals(status) || "case-only-candidate".equals(status))
                findings.add(finding("AR3-" + status, "http", List.of(call.id),
                        "unmatched".equals(status) ? "No compatible endpoint observed in the supplied backend snapshots."
                                : "case-only-candidate".equals(status) ? "Only case-insensitive path candidates exist; routing configuration requires review."
                                : "Multiple endpoint registrations are compatible or case-only alternatives exist; no unique target is asserted.", record));
            counts.merge(status, 1, Integer::sum); callRecords.add(record);
        }
        int noCaller = 0, unresolvedEndpoints = 0;
        // A bounded HTTP search means unvisited caller relationships may exist for every endpoint.
        boolean matchingComplete = !workBoundReached;
        for (Item endpoint : endpoints) {
            String status = !usablePath(endpoint.node) || embeddedTemplate(endpoint.node.path("normalizedPath").asText()) ? "unknown" : observed.contains(endpoint.id) ? "observed-candidate-caller"
                    : matchingComplete ? "no-observed-caller" : "unknown";
            ObjectNode record = evidence(endpoint.node, endpoint.component).put("id", endpoint.id).put("status", status);
            copyHttp(endpoint.node, record);
            record.put("caseSensitiveRouting", endpoint.caseSensitive).put("routingSemanticsBasis", endpoint.routingKnown ? "explicit" : "conservative-default");
            if ("no-observed-caller".equals(status)) {
                noCaller++;
                findings.add(finding("AR4-no-observed-caller", "http", List.of(endpoint.id),
                        "No caller candidate in this static input set; external and unextracted consumers may exist.", record));
            } else if ("unknown".equals(status)) unresolvedEndpoints++;
            endpointRecords.add(record);
        }
        ObjectNode summary = result.putObject("summary");
        for (String key : List.of("matched", "unmatched", "ambiguous", "case-only-candidate", "external", "unknown")) summary.put(key, counts.getOrDefault(key, 0));
        summary.put("calls", calls.size()).put("endpoints", endpoints.size()).put("noObservedCaller", noCaller)
                .put("unknownEndpoints", unresolvedEndpoints).put("matchingComplete", matchingComplete)
                .put("comparisons", Math.min(comparisons, options.maxMatchComparisons))
                .put("pathComparisonCharacters", Math.min(pathCharacters, pathCharacterLimit)).put("pathCharacterWorkLimit", pathCharacterLimit);
        return result;
    }

    private ObjectNode analyzeGraph(Graph graph, String status, ArrayNode findings) throws IOException {
        if (graph.nodes.size() > options.maxNodes || graph.edges.size() > options.maxEdges)
            throw new IOException("Architecture graph exceeds configured limits");
        ObjectNode result = object().put("scope", graph.scope).put("status", status)
                .put("edgeSemantics", graph.scope.equals("file-modules") ? "distinct resolved runtime file dependencies" : "distinct unambiguous static HTTP candidate component links");
        ArrayNode nodes = result.putArray("nodes"), edges = result.putArray("edges"), degrees = result.putArray("degrees");
        graph.nodes.values().forEach(nodes::add); graph.edges.values().forEach(edges::add);
        Map<String, SortedSet<String>> adjacency = graph.adjacency(false), reverse = graph.adjacency(true);
        int reciprocal = 0, self = 0;
        List<Double> incoming = new ArrayList<>(), outgoing = new ArrayList<>(), criticality = new ArrayList<>();
        boolean applicable = !status.equals("not-applicable") && !status.equals("unsupported");
        for (String node : graph.nodes.keySet()) {
            int ais = reverse.get(node).size() - (reverse.get(node).contains(node) ? 1 : 0);
            int ads = adjacency.get(node).size() - (adjacency.get(node).contains(node) ? 1 : 0);
            long acs = (long) ais * ads;
            degrees.add(object().put("id", node).put("AIS", ais).put("ADS", ads).put("ACS", acs));
            incoming.add((double) ais); outgoing.add((double) ads); criticality.add((double) acs);
            if (adjacency.get(node).contains(node)) self++;
            for (String target : adjacency.get(node))
                if (node.compareTo(target) < 0 && adjacency.get(target).contains(node)) reciprocal++;
            if (applicable && ais >= options.degreeThreshold)
                findings.add(finding("high-incoming-degree-candidate", graph.scope, List.of(node),
                        "Distinct incoming neighbors meet the configured degree threshold; this is not weighted request load.", graph.nodes.get(node)));
            if (applicable && ads >= options.degreeThreshold)
                findings.add(finding("high-outgoing-degree-candidate", graph.scope, List.of(node),
                        "Distinct outgoing neighbors meet the configured degree threshold; this is not weighted request load.", graph.nodes.get(node)));
        }
        List<List<String>> sccs = stronglyConnected(adjacency, reverse);
        ArrayNode cyclic = result.putArray("cyclicComponents"), witnesses = result.putArray("cycleWitnesses");
        int cyclicCount = 0;
        Budget cycleBudget = new Budget(options.maxTraversalSteps);
        for (List<String> members : sccs) {
            boolean cycle = members.size() > 1 || adjacency.get(members.getFirst()).contains(members.getFirst());
            if (!cycle) continue;
            cyclicCount++;
            ObjectNode group = object().put("id", id(graph.scope, "scc", String.join("\n", members)));
            array(group, "members", members); cyclic.add(group);
            if (applicable) findings.add(finding("cyclic-dependency-component", graph.scope, members,
                    "These nodes form a strongly connected dependency component.", group));
            if (witnesses.size() < options.maxWitnesses && cycleBudget.remaining > 0) {
                List<String> witness = cycleWitness(members, adjacency, cycleBudget);
                if (!witness.isEmpty()) witnesses.add(array(object(), "nodes", witness));
            }
        }
        ArrayNode chains = result.putArray("chainWitnesses");
        Budget chainBudget = new Budget(options.maxTraversalSteps);
        if (applicable) for (String start : adjacency.keySet()) {
            if (chains.size() >= options.maxWitnesses || chainBudget.remaining <= 0) break;
            chains(start, adjacency, new ArrayList<>(), new HashSet<>(), chains, chainBudget);
        }
        // Sampled paths are deliberately not findings: bounded enumeration can displace unchanged paths across commits.
        ObjectNode metrics = result.putObject("metrics");
        metrics.put("nodeCount", graph.nodes.size()).put("edgeCount", graph.edges.size()).put("selfLoops", self)
                .put("reciprocalPairs", reciprocal).put("sccCount", sccs.size()).put("cyclicSccCount", cyclicCount);
        if (graph.nodes.size() < 2 || !applicable) metrics.putNull("density");
        else metrics.put("density", (graph.edges.size() - self) / ((double) graph.nodes.size() * (graph.nodes.size() - 1)));
        statistics(metrics, "AIS", incoming, applicable); statistics(metrics, "ADS", outgoing, applicable); statistics(metrics, "ACS", criticality, applicable);
        result.put("cycleWitnessesTruncated", cyclicCount > witnesses.size());
        result.put("chainWitnessSearchBoundReached", chains.size() >= options.maxWitnesses || chainBudget.remaining <= 0);
        result.put("chainLengthNodes", options.chainLength);
        return result;
    }

    /** Historical observations: no composite score and no causal/behavioral predictions. */
    public ObjectNode compare(JsonNode before, JsonNode after) throws IOException {
        validateAnalysis(before); validateAnalysis(after);
        if (!before.path("name").equals(after.path("name"))) throw new IOException("Cannot compare different architecture analyses");
        if (!before.path("limits").equals(after.path("limits"))) throw new IOException("Architecture analysis limits or thresholds differ");
        Map<String, JsonNode> oldComponents = index(before.path("components")), newComponents = index(after.path("components"));
        Set<String> common = new HashSet<>(oldComponents.keySet()); common.retainAll(newComponents.keySet());
        if (common.isEmpty()) throw new IOException("Architecture analyses have no shared component identity");
        for (String component : common) {
            String a = oldComponents.get(component).path("repositoryIdentity").asText(), b = newComponents.get(component).path("repositoryIdentity").asText();
            if (!a.isBlank() && !b.isBlank() && !a.equals(b)) throw new IOException("Cannot compare different component repositories");
        }
        Set<String> oldCoverage = architectureComponents(before), newCoverage = architectureComponents(after);
        for (String component : common)
            if (oldCoverage.contains(component) != newCoverage.contains(component))
                throw new IOException("Architecture source coverage differs for a shared component; missing evidence cannot be treated as removal");
        ObjectNode result = object().put("schemaVersion", 1).put("analysis", ANALYSIS + "-delta").put("name", after.path("name").asText());
        result.set("beforeComponents", before.path("components").deepCopy()); result.set("afterComponents", after.path("components").deepCopy());
        ObjectNode changes = result.putObject("findings");
        Set<String> excludedScopes = new TreeSet<>();
        if (!before.path("http").path("summary").path("matchingComplete").asBoolean()
                || !after.path("http").path("summary").path("matchingComplete").asBoolean()) {
            excludedScopes.add("http"); excludedScopes.add("declared-applications-and-clients"); excludedScopes.add("declared-backend-applications");
        }
        if (!"supported".equals(before.path("graphs").path("modules").path("status").asText())
                || !"supported".equals(after.path("graphs").path("modules").path("status").asText())) excludedScopes.add("file-modules");
        difference(comparableFindings(before, excludedScopes), comparableFindings(after, excludedScopes), changes, "introduced", "resolved");
        array(changes, "excludedScopes", excludedScopes);
        ObjectNode graphs = result.putObject("graphs");
        for (String scope : List.of("modules", "components", "backendApplications")) {
            JsonNode oldGraph = before.path("graphs").path(scope), newGraph = after.path("graphs").path(scope);
            ObjectNode delta = graphs.putObject(scope);
            delta.put("beforeStatus", oldGraph.path("status").asText()).put("afterStatus", newGraph.path("status").asText());
            for (String field : List.of("coverage", "weightedMetrics")) {
                String suffix = Character.toUpperCase(field.charAt(0)) + field.substring(1);
                delta.set("before" + suffix, nullable(oldGraph.path(field)));
                delta.set("after" + suffix, nullable(newGraph.path(field)));
            }
            difference(oldGraph.path("nodes"), newGraph.path("nodes"), delta, "nodesAdded", "nodesRemoved");
            difference(oldGraph.path("edges"), newGraph.path("edges"), delta, "edgesAdded", "edgesRemoved");
            ArrayNode weightChanges = delta.putArray("edgeWeightsChanged");
            Map<String, JsonNode> oldEdges = index(oldGraph.path("edges"));
            for (JsonNode edge : newGraph.path("edges")) {
                JsonNode oldEdge = oldEdges.get(edge.path("id").asText());
                if (oldEdge != null && !oldEdge.path("observations").equals(edge.path("observations"))) {
                    ObjectNode change = object().put("id", edge.path("id").asText());
                    change.set("before", oldEdge.path("observations")); change.set("after", edge.path("observations"));
                    weightChanges.add(change);
                }
            }
            delta.remove("persistingCount");
            ObjectNode metrics = delta.putObject("metrics");
            newGraph.path("metrics").fieldNames().forEachRemaining(key -> {
                JsonNode oldValue = oldGraph.path("metrics").path(key), newValue = newGraph.path("metrics").path(key);
                ObjectNode entry = metrics.putObject(key); entry.set("before", oldValue.isMissingNode() ? JavaScriptExtractionService.JSON.nullNode() : oldValue);
                entry.set("after", newValue);
                if (oldValue.isNumber() && newValue.isNumber()) entry.put("change", newValue.asDouble() - oldValue.asDouble());
                else entry.putNull("change");
            });
        }
        compareCohesion(before.path("cohesion"), after.path("cohesion"), result);
        ArrayNode changedFunctions = result.putArray("changedFunctions");
        Map<String, JsonNode> oldFunctions = index(before.path("functions")), newFunctions = index(after.path("functions"));
        Set<String> oldSeeds = new TreeSet<>(), newSeeds = new TreeSet<>(), all = new TreeSet<>(oldFunctions.keySet()); all.addAll(newFunctions.keySet());
        for (String function : all) {
            JsonNode oldFunction = oldFunctions.get(function), newFunction = newFunctions.get(function);
            String kind = oldFunction == null ? "added" : newFunction == null ? "removed"
                    : !oldFunction.path("bodyFingerprint").equals(newFunction.path("bodyFingerprint")) ? "implementationChanged" : null;
            if (kind == null) continue;
            ObjectNode record = object().put("id", function).put("change", kind);
            if (oldFunction != null) { oldSeeds.add(function); record.set("before", oldFunction); }
            if (newFunction != null) { newSeeds.add(function); record.set("after", newFunction); }
            changedFunctions.add(record);
        }
        ArrayNode affected = result.putArray("potentiallyAffectedEndpoints");
        ImpactReport oldImpact = impacts(before, oldSeeds, "before", affected), newImpact = impacts(after, newSeeds, "after", affected);
        sortArray(affected, "endpointId", "side");
        ObjectNode summary = result.putObject("summary");
        summary.put("introducedFindings", changes.path("introduced").size()).put("resolvedFindings", changes.path("resolved").size())
                .put("persistingFindings", changes.path("persistingCount").asInt()).put("changedFunctions", changedFunctions.size())
                .put("potentiallyAffectedEndpointObservations", oldImpact.affectedCount + newImpact.affectedCount)
                .put("emittedImpactWitnesses", affected.size()).put("impactWitnessesTruncated", oldImpact.witnessesTruncated || newImpact.witnessesTruncated)
                .put("impactTraversalComplete", oldImpact.traversalComplete && newImpact.traversalComplete)
                .put("findingComparisonComplete", excludedScopes.isEmpty());
        result.putArray("limitations")
                .add("Introduced/resolved means finding identity changed between supplied snapshots, not a confirmed defect or repair.")
                .add("Changed functions reach endpoint handlers only through statically resolved calls; one witness per endpoint per side is emitted.")
                .add("Import rewiring and closed-over/top-level value changes do not seed function impact unless a function fingerprint also changes.")
                .add("Function or file renames can appear as removal/addition; line-only changes do not change fingerprints or graph identities.")
                .add("Added/removed components and extraction coverage changes can change metrics; inspect provenance and capability status.")
                .add("Bounded chain witnesses are excluded from finding deltas; incomplete HTTP searches or missing module coverage suppress affected finding scopes.")
                .add("No behavioral prediction, payload compatibility judgment or universal architectural degradation score is produced.");
        return result;
    }

    private ImpactReport impacts(JsonNode analysis, Set<String> seeds, String side, ArrayNode output) {
        Map<String, SortedSet<String>> reverse = new TreeMap<>();
        for (JsonNode function : analysis.path("functions")) reverse.put(function.path("id").asText(), new TreeSet<>());
        for (JsonNode call : analysis.path("functionCalls"))
            reverse.computeIfAbsent(call.path("targetId").asText(), ignored -> new TreeSet<>()).add(call.path("callerId").asText());
        Map<String, String> origin = new HashMap<>(), next = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String seed : seeds) { origin.put(seed, seed); queue.add(seed); }
        Budget budget = new Budget(options.maxTraversalSteps);
        while (!queue.isEmpty() && budget.take()) {
            String current = queue.removeFirst();
            for (String caller : reverse.getOrDefault(current, Collections.emptySortedSet())) {
                if (!budget.take()) break;
                if (!origin.containsKey(caller)) { origin.put(caller, origin.get(current)); next.put(caller, current); queue.addLast(caller); }
            }
        }
        Map<String, JsonNode> endpoints = index(analysis.path("http").path("endpoints"));
        Set<String> emitted = new HashSet<>();
        int emittedWitnesses = 0;
        List<JsonNode> handlers = sortedBy(analysis.path("endpointHandlers"), "endpointId", "functionId");
        for (JsonNode handler : handlers) {
            String function = handler.path("functionId").asText(), endpoint = handler.path("endpointId").asText();
            if (!origin.containsKey(function) || !emitted.add(endpoint)) continue;
            if (emittedWitnesses >= options.maxWitnesses) continue;
            emittedWitnesses++;
            ObjectNode impact = object().put("endpointId", endpoint).put("side", side).put("changedFunctionId", origin.get(function))
                    .put("classification", "potentially-affected-static-call-path");
            if (endpoints.containsKey(endpoint)) impact.set("endpoint", endpoints.get(endpoint));
            List<String> path = new ArrayList<>();
            for (String current = function; current != null; current = next.get(current)) {
                if (path.size() >= 128) { impact.put("witnessTruncated", true); break; }
                path.add(current);
            }
            array(impact, "functionPath", path); output.add(impact);
        }
        return new ImpactReport(queue.isEmpty() && budget.remaining > 0, emitted.size(), emitted.size() > emittedWitnesses);
    }

    private static void compareCohesion(JsonNode before, JsonNode after, ObjectNode result) throws IOException {
        if (before.isMissingNode() && after.isMissingNode()) return;
        if (before.isMissingNode() || after.isMissingNode()) throw new IOException("Cohesion evidence availability differs");
        Map<String, JsonNode> oldComponents = cohesionIndex(before), newComponents = cohesionIndex(after);
        ObjectNode comparison = result.putObject("cohesion");
        comparison.put("interpretation", "Observed metric changes only; inspect coverage and operation populations. Estimates and proxies are not strict metric deltas.");
        ArrayNode components = comparison.putArray("components");
        Set<String> ids = new TreeSet<>(oldComponents.keySet()); ids.addAll(newComponents.keySet());
        for (String id : ids) {
            JsonNode oldComponent = oldComponents.getOrDefault(id, com.fasterxml.jackson.databind.node.MissingNode.getInstance());
            JsonNode newComponent = newComponents.getOrDefault(id, com.fasterxml.jackson.databind.node.MissingNode.getInstance());
            ObjectNode component = components.addObject().put("component", id);
            component.set("beforeOperationCount", nullable(oldComponent.path("operationCount")));
            component.set("afterOperationCount", nullable(newComponent.path("operationCount")));
            for (String metric : List.of("SIDC", "SSIC", "LOMLC")) {
                JsonNode a = oldComponent.path(metric), b = newComponent.path(metric);
                ObjectNode entry = component.putObject(metric);
                entry.put("beforeStatus", a.path("status").asText("unavailable"));
                entry.put("afterStatus", b.path("status").asText("unavailable"));
                JsonNode av = nullable(a.path("value")), bv = nullable(b.path("value"));
                entry.set("before", av); entry.set("after", bv);
                if (av.isNumber() && bv.isNumber()) entry.put("change", bv.asDouble() - av.asDouble());
                else entry.putNull("change");
                for (String field : List.of("coverage", "availableEvidenceEstimate")) {
                    entry.set("before" + Character.toUpperCase(field.charAt(0)) + field.substring(1), nullable(a.path(field)));
                    entry.set("after" + Character.toUpperCase(field.charAt(0)) + field.substring(1), nullable(b.path(field)));
                }
            }
            component.set("beforeObservedFieldShapeProxy", nullable(oldComponent.path("observedFieldShapeProxy")));
            component.set("afterObservedFieldShapeProxy", nullable(newComponent.path("observedFieldShapeProxy")));
        }
    }

    private static JsonNode nullable(JsonNode value) {
        return value.isMissingNode() ? JavaScriptExtractionService.JSON.nullNode() : value.deepCopy();
    }

    private static Map<String, JsonNode> cohesionIndex(JsonNode report) throws IOException {
        if (!report.isObject() || report.path("schemaVersion").asInt() != 1 || !report.path("components").isArray())
            throw new IOException("Invalid cohesion analysis");
        Map<String, JsonNode> components = new TreeMap<>();
        for (JsonNode component : report.path("components")) {
            String id = component.path("component").asText();
            if (!component.isObject() || id.isBlank() || id.length() > 4096 || components.putIfAbsent(id, component) != null)
                throw new IOException("Invalid cohesion component identity");
            for (String metric : List.of("SIDC", "SSIC", "LOMLC")) {
                JsonNode measurement = component.path(metric), value = measurement.path("value");
                if (!measurement.isObject() || !measurement.path("status").isTextual()
                        || !(value.isNull() || value.isNumber() && Double.isFinite(value.asDouble())))
                    throw new IOException("Invalid cohesion metric measurement");
            }
        }
        return components;
    }

    private void validateAnalysis(JsonNode value) throws IOException {
        if (value == null || !value.isObject() || value.path("schemaVersion").asInt() != 1 || !ANALYSIS.equals(value.path("analysis").asText())
                || !value.path("name").isTextual() || value.path("name").asText().isBlank()
                || !value.path("graphs").isObject() || !value.path("limits").isObject()) throw new IOException("Invalid architecture analysis");
        for (String group : List.of("components", "findings", "functions", "functionCalls", "endpointHandlers"))
            if (!value.path(group).isArray() || value.path(group).size() > options.maxEdges) throw new IOException("Invalid architecture analysis records");
        for (String group : List.of("components", "findings", "functions", "functionCalls")) strictIndex(value.path(group));
        if (!value.path("sourceCoverage").isArray()) throw new IOException("Missing architecture source coverage");
        if (value.has("cohesion") || value.path("limits").path("metricDefinitionsVersion").asInt() >= 2) {
            Set<String> components = strictIndex(value.path("components")).keySet();
            if (!components.equals(cohesionIndex(value.path("cohesion")).keySet()))
                throw new IOException("Cohesion component coverage differs from architecture components");
        }
        if (value.path("functions").size() > options.maxNodes) throw new IOException("Architecture function limit exceeded");
        for (String graph : List.of("modules", "components", "backendApplications")) {
            JsonNode node = value.path("graphs").path(graph);
            if (!node.path("nodes").isArray() || !node.path("edges").isArray() || !node.path("metrics").isObject()
                    || node.path("nodes").size() > options.maxNodes || node.path("edges").size() > options.maxEdges)
                throw new IOException("Invalid architecture graph");
            Set<String> nodes = strictIndex(node.path("nodes")).keySet(); strictIndex(node.path("edges"));
            Set<String> pairs = new HashSet<>();
            for (JsonNode edge : node.path("edges")) {
                String source = edge.path("source").asText(), target = edge.path("target").asText();
                if (!nodes.contains(source) || !nodes.contains(target) || !pairs.add(source + "\0" + target))
                    throw new IOException("Invalid or duplicate architecture edge");
            }
        }
        Set<String> functions = strictIndex(value.path("functions")).keySet();
        for (JsonNode function : value.path("functions"))
            if (!function.path("bodyFingerprint").asText().matches("[a-f0-9]{64}")) throw new IOException("Invalid architecture function fingerprint");
        for (JsonNode call : value.path("functionCalls"))
            if (!functions.contains(call.path("callerId").asText()) || !functions.contains(call.path("targetId").asText()))
                throw new IOException("Invalid resolved function graph reference");
        JsonNode endpoints = value.path("http").path("endpoints");
        if (!endpoints.isArray() || endpoints.size() > options.maxEdges) throw new IOException("Invalid architecture HTTP endpoints");
        Set<String> endpointIds = strictIndex(endpoints).keySet(), pairs = new HashSet<>();
        for (JsonNode handler : value.path("endpointHandlers")) {
            String endpoint = handler.path("endpointId").asText(), function = handler.path("functionId").asText();
            if (!functions.contains(function) || !endpointIds.contains(endpoint) || !pairs.add(endpoint + "\0" + function))
                throw new IOException("Invalid endpoint function graph reference");
        }
    }

    private static Map<String, JsonNode> strictIndex(JsonNode values) throws IOException {
        Map<String, JsonNode> result = new TreeMap<>();
        for (JsonNode value : values) {
            String id = value.path("id").asText();
            if (!value.isObject() || !value.path("id").isTextual() || id.isBlank() || id.length() > 4096
                    || id.chars().anyMatch(Character::isISOControl) || result.putIfAbsent(id, value) != null)
                throw new IOException("Invalid or duplicate architecture identity");
        }
        return result;
    }

    private static ArrayNode comparableFindings(JsonNode analysis, Set<String> excludedScopes) {
        ArrayNode result = JavaScriptExtractionService.JSON.createArrayNode();
        for (JsonNode finding : analysis.path("findings"))
            if (!excludedScopes.contains(finding.path("scope").asText()) && !"dependency-chain-witness".equals(finding.path("kind").asText())) result.add(finding);
        return result;
    }
    private static Set<String> architectureComponents(JsonNode analysis) {
        Set<String> result = new HashSet<>(); analysis.path("sourceCoverage").forEach(n -> result.add(n.path("component").asText())); return result;
    }

    private void validateArchitecture(JsonNode architecture) throws IOException {
        if (!architecture.isObject() || architecture.path("schemaVersion").asInt() != 1) throw new IOException("Unsupported architecture schema");
        for (String field : List.of("modules", "dependencies", "functions", "calls", "endpointHandlers")) {
            if (!architecture.path(field).isArray() || architecture.path(field).size() > options.maxEdges)
                throw new IOException("Invalid architecture records");
            for (JsonNode record : architecture.path(field)) if (!record.isObject()) throw new IOException("Invalid architecture record");
        }
        for (String field : List.of("modules", "dependencies", "functions", "calls")) {
            Set<String> ids = new HashSet<>();
            for (JsonNode record : architecture.path(field))
                if (!record.path("id").isTextual() || record.path("id").asText().isBlank() || !ids.add(record.path("id").asText()))
                    throw new IOException("Invalid or duplicate architecture record identity");
        }
    }

    private static void capabilities(ArrayNode output, String modules, int backends, int withArchitecture,
                                     JsonNode graphs, JsonNode cohesion) {
        capability(output, "module-dependency-graph", modules, "Runtime local imports/requires only; type-only, external and unresolved dependencies are excluded.");
        capability(output, "AIS-ADS-ACS-density-reciprocal-SCC", modules, "Defined over distinct graph neighbors; module analogs have a separate scope from applications.");
        capability(output, "backend-service-topology", backends < 2 ? "not-applicable" : "partial", "Requires at least two declared backend applications; HTTP edges remain static method/path candidates.");
        capability(output, "cycles-chains-degree-candidates", modules, "SCC detection is complete for the observed graph; cycle/chain witnesses are bounded; thresholds count distinct neighbors.");
        capability(output, "AR3-AR4-http-observations", "partial", "Unmatched/ambiguous calls and no observed caller are static closed-world observations, not confirmed violations.");
        capability(output, "AR6-style-function-impact", withArchitecture == 0 ? "unsupported" : "partial", "Historical comparison propagates changed function fingerprints along resolved local call edges; does not predict behavior.");
        capability(output, "historical-architecture-comparison", "supported", "Stable graph/finding identities and metric differences; no universal degradation score.");
        for (String name : List.of("SC", "LWF", "GWF", "SCCmodularity"))
            capability(output, name, graphs.path("modules").path("weightedMetrics").path("status").asText("unsupported"),
                    "Implemented over explicit static observation weights; inspect each graph's scope, weight semantics and status.");
        for (String name : List.of("SIDC", "SSIC", "LOMLC")) {
            String status = withArchitecture == 0 ? "unsupported" : backends == 0 ? "not-applicable" : "supported";
            for (JsonNode component : cohesion.path("components"))
                if ("backend".equals(component.path("kind").asText()) && !"supported".equals(component.path(name).path("status").asText())
                        && !"unsupported".equals(status)) status = "partial";
            capability(output, name, status, "Implemented per declared backend; strict values, available-evidence estimates and field/shape proxies have separate coverage.");
        }
        for (String name : List.of("WrongCuts", "health-checks", "API-gateway", "wobbly-interactions", "AR7-data-access-impact"))
            capability(output, name, "unsupported", "Required typed contracts, domain/service roles, configuration or data-access semantics are not extracted.");
    }

    private static void capability(ArrayNode array, String id, String status, String reason) { array.add(object().put("id", id).put("status", status).put("reason", reason)); }
    private static boolean usablePath(JsonNode node) { return node.path("normalizedPath").isTextual() && node.path("normalizedPath").asText().startsWith("/"); }
    private static boolean knownMethod(JsonNode node) { return Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE").contains(node.path("method").asText().toUpperCase(Locale.ROOT)); }
    private static boolean embeddedTemplate(String path) {
        for (String segment : path.split("/", -1)) if (segment.contains("{param}") && !segment.equals("{param}")) return true;
        return false;
    }
    private static String coarsePath(String path) {
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) if (segments[i].contains("{param}")) segments[i] = "{param}";
        return String.join("/", segments);
    }
    private static boolean whollyDynamic(String path) {
        boolean parameter = false;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (!segment.equals("{param}")) return false;
            parameter = true;
        }
        return parameter;
    }
    private static boolean methodMatches(JsonNode call, JsonNode endpoint) {
        String a = call.path("method").asText().toUpperCase(Locale.ROOT), b = endpoint.path("method").asText().toUpperCase(Locale.ROOT);
        return !a.isBlank() && (a.equals(b) || b.equals("ALL") || a.equals("HEAD") && b.equals("GET"));
    }
    private static boolean pathMatches(String call, String endpoint, boolean caseSensitive) {
        String[] a = call.split("/", -1), b = endpoint.split("/", -1);
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("{param}") && !b[i].isEmpty() || b[i].equals("{param}") && !a[i].isEmpty()) continue;
            if (caseSensitive ? !a[i].equals(b[i]) : !a[i].equalsIgnoreCase(b[i])) return false;
        }
        return true;
    }
    private static void copyHttp(JsonNode from, ObjectNode to) {
        for (String field : List.of("method", "normalizedPath", "target", "resolution")) if (from.has(field)) to.set(field, from.path(field));
    }
    private static ObjectNode evidence(JsonNode record, String component) {
        ObjectNode result = object().put("component", component);
        for (String key : List.of("file", "line", "endLine")) if (record.has(key) && record.path(key).isValueNode()) result.set(key, record.path(key));
        return result;
    }
    private static ObjectNode finding(String kind, String scope, List<String> subjects, String message, JsonNode evidence) {
        ObjectNode result = object().put("id", id(kind, scope, String.join("\n", subjects))).put("kind", kind).put("scope", scope)
                .put("classification", "review-candidate").put("message", message);
        array(result, "subjects", subjects); result.set("evidence", evidence.deepCopy()); return result;
    }
    private static void statistics(ObjectNode output, String name, List<Double> values, boolean applicable) {
        if (!applicable || values.isEmpty()) { for (String prefix : List.of("max", "avg", "std")) output.putNull(prefix + name); return; }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        output.put("max" + name, values.stream().mapToDouble(Double::doubleValue).max().orElse(0)); output.put("avg" + name, mean);
        output.put("std" + name, Math.sqrt(values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0)));
    }
    private static void difference(JsonNode before, JsonNode after, ObjectNode output, String added, String removed) {
        Map<String, JsonNode> a = index(before), b = index(after);
        ArrayNode additions = output.putArray(added), removals = output.putArray(removed);
        b.forEach((key, value) -> { if (!a.containsKey(key)) additions.add(value); });
        a.forEach((key, value) -> { if (!b.containsKey(key)) removals.add(value); });
        output.put("persistingCount", b.size() - additions.size());
    }

    // Iterative Kosaraju avoids JVM recursion limits on long module graphs. O(V + E), sorted traversal.
    private static List<List<String>> stronglyConnected(Map<String, SortedSet<String>> adjacency, Map<String, SortedSet<String>> reverse) {
        Set<String> visited = new HashSet<>(); List<String> order = new ArrayList<>();
        for (String start : adjacency.keySet()) {
            if (!visited.add(start)) continue;
            Deque<Frame> stack = new ArrayDeque<>(); stack.push(new Frame(start, adjacency.get(start).iterator()));
            while (!stack.isEmpty()) {
                Frame current = stack.peek();
                if (current.next.hasNext()) {
                    String target = current.next.next();
                    if (visited.add(target)) stack.push(new Frame(target, adjacency.get(target).iterator()));
                } else { order.add(current.node); stack.pop(); }
            }
        }
        Collections.reverse(order); visited.clear(); List<List<String>> groups = new ArrayList<>();
        for (String start : order) {
            if (!visited.add(start)) continue;
            List<String> group = new ArrayList<>(); Deque<String> queue = new ArrayDeque<>(); queue.push(start);
            while (!queue.isEmpty()) {
                String node = queue.pop(); group.add(node);
                for (String target : reverse.get(node)) if (visited.add(target)) queue.push(target);
            }
            Collections.sort(group); groups.add(group);
        }
        groups.sort(Comparator.comparing(List::getFirst)); return groups;
    }
    private static List<String> cycleWitness(List<String> members, Map<String, SortedSet<String>> graph, Budget budget) {
        String start = members.getFirst(); Set<String> allowed = new HashSet<>(members);
        if (graph.get(start).contains(start)) return List.of(start, start);
        Map<String, String> parent = new HashMap<>(); Deque<String> queue = new ArrayDeque<>();
        for (String target : graph.get(start)) if (allowed.contains(target)) { parent.put(target, start); queue.add(target); }
        while (!queue.isEmpty() && budget.take()) {
            String current = queue.removeFirst();
            if (graph.get(current).contains(start)) {
                List<String> path = new ArrayList<>(); path.add(start);
                for (String node = current; !node.equals(start); node = parent.get(node)) path.add(node);
                path.add(start); Collections.reverse(path); return path;
            }
            for (String target : graph.get(current)) {
                if (!budget.take()) break;
                if (allowed.contains(target) && !target.equals(start) && !parent.containsKey(target)) { parent.put(target, current); queue.addLast(target); }
            }
        }
        return List.of();
    }
    private void chains(String current, Map<String, SortedSet<String>> graph, List<String> path, Set<String> visited, ArrayNode output, Budget budget) {
        if (output.size() >= options.maxWitnesses || !budget.take() || !visited.add(current)) return;
        path.add(current);
        if (path.size() == options.chainLength) output.add(array(object(), "nodes", path));
        else for (String target : graph.get(current)) {
            if (output.size() >= options.maxWitnesses || budget.remaining <= 0) break;
            chains(target, graph, path, visited, output, budget);
        }
        path.removeLast(); visited.remove(current);
    }

    private static final class Graph {
        final String scope;
        final SortedMap<String, ObjectNode> nodes = new TreeMap<>(), edges = new TreeMap<>();
        Graph(String scope) { this.scope = scope; }
        void node(ObjectNode node) { nodes.put(node.path("id").asText(), node.deepCopy()); }
        void edge(String source, String target, JsonNode evidence) {
            String key = id(scope, source, target);
            ObjectNode edge = edges.get(key);
            if (edge == null) {
                edge = object().put("id", key).put("source", source).put("target", target).put("observations", 0);
                edge.set("evidence", evidence.deepCopy()); edges.put(key, edge);
            }
            edge.put("observations", edge.path("observations").asInt() + 1);
        }
        Map<String, SortedSet<String>> adjacency(boolean reverse) {
            Map<String, SortedSet<String>> result = new TreeMap<>(); nodes.keySet().forEach(n -> result.put(n, new TreeSet<>()));
            for (JsonNode edge : edges.values()) result.get(edge.path(reverse ? "target" : "source").asText()).add(edge.path(reverse ? "source" : "target").asText());
            return result;
        }
    }
    private static final class Budget { int remaining; Budget(int remaining) { this.remaining = remaining; } boolean take() { return remaining-- > 0; } }
    private record Frame(String node, Iterator<String> next) { }
    private record Item(String component, String id, JsonNode node, boolean caseSensitive, boolean routingKnown) { }
    private record ImpactReport(boolean traversalComplete, int affectedCount, boolean witnessesTruncated) { }
    private static ObjectNode object() { return JavaScriptExtractionService.JSON.createObjectNode(); }
    private static ObjectNode array(ObjectNode object, String key, Collection<String> values) { ArrayNode array = object.putArray(key); values.forEach(array::add); return object; }
    private static String componentKey(JsonNode component) { return component.path("kind").asText() + ":" + component.path("name").asText(); }
    private static String qualified(String component, String local) { return id(component, local); }
    private static String id(String... values) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\0", values).getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Map<String, JsonNode> index(JsonNode values) { Map<String, JsonNode> result = new TreeMap<>(); values.forEach(n -> result.put(n.path("id").asText(), n)); return result; }
    private static List<JsonNode> sorted(JsonNode values) { return sortedBy(values, "id"); }
    private static List<JsonNode> sortedBy(JsonNode values, String... fields) {
        List<JsonNode> result = new ArrayList<>(); values.forEach(result::add);
        result.sort(Comparator.comparing(n -> { StringBuilder key = new StringBuilder(); for (String field : fields) key.append(n.path(field).asText()).append('\0'); return key.toString(); })); return result;
    }
    private static void sortArray(ArrayNode array, String... fields) { List<JsonNode> sorted = sortedBy(array, fields); array.removeAll(); sorted.forEach(array::add); }
}
