package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Evidence-scoped semantic observations. No deployment inspection, target execution, or defect verdicts. */
public final class JavaScriptSemanticService {
    public record Options(int maxRecords, int maxTraversalWork, int maxWitnesses, int maxEntityComparisons) {
        public Options {
            if (maxRecords < 1 || maxRecords > 500_000 || maxTraversalWork < 1 || maxTraversalWork > 10_000_000
                    || maxWitnesses < 1 || maxWitnesses > 1000 || maxEntityComparisons < 1 || maxEntityComparisons > 10_000_000)
                throw new IllegalArgumentException("Invalid semantic analysis limits");
        }
        public static Options defaults() { return new Options(100_000, 1_000_000, 100, 1_000_000); }
    }
    private static final String ANALYSIS = "static-javascript-semantics";
    private static final Set<String> GUARDS = Set.of("circuit-breaker", "retry", "rate-limit", "concurrency-limit");
    private static final Set<String> ACTIVE = Set.of("invoke", "fire", "schedule", "submit", "attempt", "retry", "consume", "mount");
    private final Options options;
    public JavaScriptSemanticService() { this(Options.defaults()); }
    public JavaScriptSemanticService(Options options) { this.options = Objects.requireNonNull(options); }

    public ObjectNode analyze(List<? extends JsonNode> snapshots) throws IOException {
        if (snapshots == null || snapshots.isEmpty() || snapshots.size() > options.maxRecords)
            throw new IllegalArgumentException("Semantic analysis requires bounded snapshots");
        ObjectNode report = object().put("schemaVersion", 1).put("analysis", ANALYSIS);
        report.set("limits", limits()); aliases(report);
        ArrayNode components = report.putArray("components"), findings = report.putArray("findings");
        List<JsonNode> ordered = new ArrayList<>(snapshots); ordered.sort(Comparator.comparing(s -> componentKey(s.path("component"))));
        Set<String> ids = new HashSet<>(); long records = 0; int backends = 0, availableBackends = 0;
        for (JsonNode snapshot : ordered) {
            JavaScriptExtractionService.validateSnapshot(snapshot);
            JsonNode info = snapshot.path("component"); String component = componentKey(info);
            if (!ids.add(component)) throw new IOException("Duplicate semantic component");
            boolean backend = info.path("kind").asText().equals("backend"); if (backend) backends++;
            JsonNode evidence = snapshot.path("architecture").path("semanticEvidence");
            ObjectNode entry = components.addObject().put("component", component).put("name", info.path("name").asText())
                    .put("kind", info.path("kind").asText()).put("operationCount", snapshot.path("endpoints").size());
            boolean available = evidence.isObject() && evidence.path("schemaVersion").asInt() == 1;
            ObjectNode coverage = entry.putObject("coverage").put("semanticEvidenceAvailable", available);
            entry.put("status", available ? "partial" : "unsupported");
            for (String field : List.of("dataAccess", "entities", "healthEndpoints", "proxies", "resilienceEvidence")) entry.putArray(field);
            if (!available) {
                coverage.putNull("inspectionComplete").putNull("boundsReached");
                for (String field : List.of("health", "proxyGateway", "wrongCuts", "resilience"))
                    entry.set(field, observation("unsupported", "Semantic evidence was not extracted; absence cannot be inferred"));
                continue;
            }
            if (backend) availableBackends++;
            boolean inspected = evidence.path("summary").path("inspectionComplete").asBoolean(false);
            boolean bounded = evidence.path("summary").path("boundsReached").asBoolean(true);
            coverage.put("inspectionComplete", inspected).put("boundsReached", bounded);
            for (String field : List.of("unresolvedDataAccessCandidates", "unregisteredProxyCreations"))
                if (evidence.path("summary").path(field).isIntegralNumber()) coverage.set(field, evidence.path("summary").path(field));
                else coverage.putNull(field);
            for (String field : List.of("dataAccess", "entities", "proxies", "resilience")) {
                JsonNode array = evidence.path(field);
                if (!array.isArray() || (records += array.size()) > options.maxRecords) throw new IOException("Semantic evidence record limit exceeded");
                ArrayNode target = entry.withArray(field.equals("resilience") ? "resilienceEvidence" : field);
                for (JsonNode value : sorted(array, "id")) target.add(record(value, component));
            }
            if (!evidence.path("healthEndpoints").isArray() || (records += evidence.path("healthEndpoints").size()) > options.maxRecords)
                throw new IOException("Semantic health evidence limit exceeded");
            for (JsonNode candidate : sorted(evidence.path("healthEndpoints"), "endpointId")) {
                ObjectNode health = entry.withArray("healthEndpoints").addObject().put("endpointId", qualified(component, candidate.path("endpointId").asText()))
                        .put("localEndpointId", candidate.path("endpointId").asText()).put("basis", candidate.path("basis").asText())
                        .put("classification", "health-like-route-name-candidate").put("healthBehaviorVerified", false);
                ArrayNode functions = health.putArray("functionIds");
                for (JsonNode function : candidate.path("functionIds")) functions.add(qualified(component, function.asText()));
            }
            int healthCount = entry.path("healthEndpoints").size(), proxies = entry.path("proxies").size();
            ObjectNode health = observation("partial", "Route-name candidates only; endpoint health behavior and deployment probes remain unknown");
            health.put("candidateCount", healthCount).put("confirmedHealthBehaviorCount", 0).put("inspectedSourceHasCandidate", healthCount > 0)
                    .putNull("deploymentHealthcheckAbsent").put("absenceWithinInspectedSourceKnown", inspected && !bounded);
            entry.set("health", health);
            ObjectNode gateway = observation("partial", "Recognized mounted proxy/server calls only; an external or deployment gateway may exist");
            gateway.put("sourceProxyObservationCount", proxies).put("inspectedSourceHasProxy", proxies > 0)
                    .putNull("deploymentGatewayAbsent").put("absenceWithinInspectedSourceKnown", inspected && !bounded);
            entry.set("proxyGateway", gateway);
            ObjectNode wrongCuts = observation(backend ? "partial" : "not-applicable",
                    "Legacy no-repository-role analogy: no recognized data access in inspected source is a review heuristic, not proof of wrong service boundaries");
            boolean knownAbsence = backend && inspected && !bounded && coverage.path("unresolvedDataAccessCandidates").isIntegralNumber()
                    && coverage.path("unresolvedDataAccessCandidates").asInt() == 0;
            if (backend && (!entry.path("dataAccess").isEmpty() || !entry.path("entities").isEmpty())) wrongCuts.put("noRecognizedDataAccessRole", false);
            else if (knownAbsence) wrongCuts.put("noRecognizedDataAccessRole", true);
            else wrongCuts.putNull("noRecognizedDataAccessRole");
            wrongCuts.put("boundaryViolationVerified", false); entry.set("wrongCuts", wrongCuts);
            if (wrongCuts.path("noRecognizedDataAccessRole").asBoolean()) findings.add(finding("no-recognized-data-access-role", component,
                    List.of(component), "No recognized source data-access role; a boundary review heuristic only", "AR21"));
            entry.set("resilience", resilience(entry, findings));
        }
        ArrayNode capabilities = report.putArray("capabilities");
        String status = backends == 0 ? "not-applicable" : availableBackends == 0 ? "unsupported" : "partial";
        for (String name : List.of("health-checks", "API-gateway", "WrongCuts", "wobbly-interactions", "AR7-data-access-impact"))
            capability(capabilities, name, status, "Source-scoped observations with explicit unknown deployment/runtime semantics; not verified defects");
        capability(capabilities, "AR22-entity-evolution", backends < 2 ? "not-applicable" : status,
                "Requires historical explicit same-name entity/field types in at least two declared backends; nominal identity is not shared-database proof");
        report.putArray("limitations").add("Missing semantic evidence is unsupported, never an observed absence.")
                .add("Health names and source proxy calls do not establish configured production health checks or gateway deployment.")
                .add("WrongCuts is a no-recognized-data-access-role heuristic; files and endpoints are never promoted to services.")
                .add("Wobbly uses the legacy all-four-activated-guard module analogy; no missing retry or instability rule is invented.")
                .add("Queries, schemas and fingerprints are sanitized structural observations; literal-only changes can be outside the fingerprint scope.")
                .add("AR7 follows resolved local call paths only. AR22 compares explicit nominal fields and reports candidates, not compatibility failures.");
        return report;
    }

    private ObjectNode resilience(JsonNode component, ArrayNode findings) {
        ObjectNode result = observation("partial", "Inventory plus legacy all-four activated guard kinds at module scope; co-location does not establish instability");
        result.put("rule", "circuit-breaker + retry + rate-limit + concurrency-limit activated in the same file")
                .put("legacyAnnotationResultEquivalent", false).put("missingGuardDefectsInferred", false);
        Map<String, SortedSet<String>> files = new TreeMap<>(), functions = new TreeMap<>();
        for (JsonNode record : component.path("resilienceEvidence")) {
            String kind = record.path("kind").asText(), function = record.path("functionId").asText("");
            String scope = function.isBlank() ? "module:" + record.path("file").asText() : function;
            functions.computeIfAbsent(scope, ignored -> new TreeSet<>()).add(kind);
            if (GUARDS.contains(kind) && ACTIVE.contains(record.path("operation").asText()))
                files.computeIfAbsent(record.path("file").asText(), ignored -> new TreeSet<>()).add(kind);
        }
        ArrayNode inventory = result.putArray("policyInventory");
        functions.forEach((scope, kinds) -> strings(inventory.addObject().put("scope", scope).putArray("observedKinds"), kinds));
        ArrayNode matched = result.putArray("legacyAllFourAnalogyModules");
        files.forEach((file, kinds) -> {
            if (!kinds.containsAll(GUARDS)) return;
            matched.add(file); findings.add(finding("all-four-activated-resilience-kinds-analogy", component.path("component").asText(),
                    List.of(file), "Legacy all-four-guard module analogy; review policy composition, not a demonstrated unstable interaction", null));
        });
        result.put("sourceEvidenceCount", component.path("resilienceEvidence").size()).put("legacyAnalogyCount", matched.size());
        return result;
    }

    public ObjectNode compare(JsonNode before, JsonNode after, JsonNode beforeFullAnalysis, JsonNode afterFullAnalysis) throws IOException {
        validateAnalysis(before, beforeFullAnalysis); validateAnalysis(after, afterFullAnalysis);
        Map<String, JsonNode> oldComponents = semanticIndex(before), newComponents = semanticIndex(after);
        if (!before.path("limits").equals(after.path("limits"))) throw new IOException("Semantic comparison limits differ");
        ObjectNode result = object().put("schemaVersion", 1).put("analysis", ANALYSIS + "-delta"); aliases(result);
        ArrayNode excluded = result.putArray("excludedComponents"), changes = result.putArray("changedDataAccess"), entityChanges = result.putArray("changedEntities");
        ArrayNode introduced = result.putArray("introducedFindings"), resolved = result.putArray("resolvedFindings");
        Set<String> comparable = new TreeSet<>();
        for (String component : union(oldComponents.keySet(), newComponents.keySet())) {
            JsonNode old = oldComponents.get(component), current = newComponents.get(component);
            if (old == null || current == null || !completeInspection(old) || !completeInspection(current)) {
                excluded.add(component); continue;
            }
            comparable.add(component);
            compareRecords(old.path("dataAccess"), current.path("dataAccess"), component, changes);
            compareRecords(old.path("entities"), current.path("entities"), component, entityChanges);
        }
        Map<String, JsonNode> oldFindings = index(before.path("findings")), newFindings = index(after.path("findings"));
        newFindings.forEach((id, item) -> { if (comparable.contains(item.path("component").asText()) && !oldFindings.containsKey(id)) introduced.add(item); });
        oldFindings.forEach((id, item) -> { if (comparable.contains(item.path("component").asText()) && !newFindings.containsKey(id)) resolved.add(item); });
        Set<String> oldSeeds = new TreeSet<>(), newSeeds = new TreeSet<>(); int unbound = 0;
        for (JsonNode change : changes) {
            for (String side : List.of("before", "after")) {
                JsonNode record = change.path(side); if (!record.isObject()) continue;
                String function = record.path("functionId").asText("");
                if (function.isBlank()) unbound++; else (side.equals("before") ? oldSeeds : newSeeds).add(function);
            }
        }
        boolean oldAssociationsComplete = seedEntities(entityChanges, "before", oldComponents, oldSeeds);
        boolean newAssociationsComplete = seedEntities(entityChanges, "after", newComponents, newSeeds);
        ObjectNode impact = result.putObject("AR7"); ArrayNode witnesses = impact.putArray("potentiallyAffectedEndpoints");
        Impact oldImpact = impacts(beforeFullAnalysis, oldSeeds, "before", witnesses);
        Impact newImpact = impacts(afterFullAnalysis, newSeeds, "after", witnesses);
        boolean hasBackend = comparable.stream().anyMatch(component -> component.startsWith("backend:"));
        impact.put("status", hasBackend ? "partial" : "unsupported").put("changedDataAccessObservations", changes.size())
                .put("changedEntityObservations", entityChanges.size()).put("changedDataAccessObservationsWithoutFunction", unbound)
                .put("potentiallyAffectedEndpointObservations", oldImpact.total + newImpact.total).put("emittedWitnesses", witnesses.size())
                .put("witnessesTruncated", oldImpact.truncated || newImpact.truncated)
                .put("entityAssociationComplete", oldAssociationsComplete && newAssociationsComplete)
                .put("traversalComplete", oldImpact.complete && newImpact.complete && oldAssociationsComplete && newAssociationsComplete)
                .put("countsAreLowerBounds", !(oldImpact.complete && newImpact.complete && oldAssociationsComplete && newAssociationsComplete))
                .put("reason", "Structural data/schema changes reverse-reach handlers through resolved local calls; no behavior or regression is predicted");
        if (!hasBackend) {
            for (String field : List.of("changedDataAccessObservations", "changedEntityObservations", "changedDataAccessObservationsWithoutFunction",
                    "potentiallyAffectedEndpointObservations", "traversalComplete", "entityAssociationComplete", "countsAreLowerBounds")) impact.putNull(field);
            impact.put("reason", "No backend has comparable inspected semantic evidence; unavailable impact is not zero impact");
        }
        result.set("AR22", entityEvolution(oldComponents, newComponents, comparable));
        result.put("comparisonCompleteForInspectedComponents", excluded.isEmpty());
        result.putArray("limitations").add("Incomplete/missing semantic source inspections are excluded, not interpreted as removals.")
                .add("Changed fingerprints describe sanitized structural evidence; incomplete evidence changes and literal-only query edits do not establish changed query behavior.")
                .add("AR7 counts before and after endpoint observations separately; witness arrays are bounded.")
                .add("Schema impact requires an explicit function association or exact entity/table-name association; absent associations remain unproven.")
                .add("AR22 uses exact nominal entity and field names with explicit types, not inferred shared persistence ownership.");
        return result;
    }

    private void compareRecords(JsonNode before, JsonNode after, String component, ArrayNode output) throws IOException {
        Map<String, JsonNode> old = index(before), current = index(after);
        for (String id : union(old.keySet(), current.keySet())) {
            JsonNode a = old.get(id), b = current.get(id);
            String change = a == null ? "added" : b == null ? "removed" : a.path("fingerprint").equals(b.path("fingerprint")) ? null : "modified";
            if (change == null) continue;
            ObjectNode item = output.addObject().put("id", id).put("component", component).put("change", change);
            boolean incomplete = a != null && !a.path("complete").asBoolean() || b != null && !b.path("complete").asBoolean();
            item.put("classification", incomplete ? "incomplete-structural-evidence-change" : "structural-evidence-change");
            if (a == null) item.putNull("beforeEvidenceComplete"); else item.put("beforeEvidenceComplete", a.path("complete").asBoolean());
            if (b == null) item.putNull("afterEvidenceComplete"); else item.put("afterEvidenceComplete", b.path("complete").asBoolean());
            if (a != null) item.set("before", a); if (b != null) item.set("after", b);
        }
    }

    private boolean seedEntities(JsonNode changes, String side, Map<String, JsonNode> components, Set<String> seeds) {
        Map<String, Set<String>> names = new TreeMap<>();
        long work = 0;
        for (JsonNode change : changes) {
            if (++work > options.maxTraversalWork) return false;
            JsonNode entity = change.path(side); if (!entity.isObject()) continue;
            String function = entity.path("functionId").asText(""); if (!function.isBlank()) seeds.add(function);
            names.computeIfAbsent(change.path("component").asText(), ignored -> new HashSet<>()).add(entity.path("name").asText());
        }
        for (Map.Entry<String, Set<String>> component : names.entrySet())
            for (JsonNode access : components.get(component.getKey()).path("dataAccess")) {
                if (++work > options.maxTraversalWork) return false;
                for (JsonNode table : access.path("tables")) {
                    if (++work > options.maxTraversalWork) return false;
                    if (component.getValue().contains(table.asText()) && access.path("functionId").isTextual())
                        seeds.add(access.path("functionId").asText());
                }
            }
        return true;
    }

    private Impact impacts(JsonNode full, Set<String> seeds, String side, ArrayNode output) throws IOException {
        if (!full.path("functions").isArray() || !full.path("functionCalls").isArray() || !full.path("endpointHandlers").isArray())
            throw new IOException("AR7 requires a full architecture function graph");
        if ((long) full.path("functions").size() + full.path("functionCalls").size() + full.path("endpointHandlers").size() > options.maxRecords * 3L)
            throw new IOException("AR7 function graph record limit exceeded");
        Set<String> functions = index(full.path("functions")).keySet();
        Map<String, JsonNode> endpointRecords = index(full.path("http").path("endpoints"));
        Map<String, SortedSet<String>> reverse = new TreeMap<>();
        for (String seed : seeds) if (!functions.contains(seed)) throw new IOException("Semantic function identity does not match full architecture");
        for (JsonNode call : full.path("functionCalls")) {
            String caller = call.path("callerId").asText(), target = call.path("targetId").asText();
            if (!functions.contains(caller) || !functions.contains(target)) throw new IOException("Invalid AR7 call graph reference");
            reverse.computeIfAbsent(target, ignored -> new TreeSet<>()).add(caller);
        }
        Map<String, String> origin = new TreeMap<>(), next = new HashMap<>(); Deque<String> pending = new ArrayDeque<>();
        seeds.forEach(seed -> { origin.put(seed, seed); pending.add(seed); });
        long work = 0; boolean bounded = false;
        while (!pending.isEmpty()) {
            if (++work > options.maxTraversalWork) { bounded = true; break; }
            String target = pending.removeFirst();
            for (String caller : reverse.getOrDefault(target, Collections.emptySortedSet())) {
                if (++work > options.maxTraversalWork) { bounded = true; break; }
                if (!origin.containsKey(caller)) { origin.put(caller, origin.get(target)); next.put(caller, target); pending.addLast(caller); }
            }
            if (bounded) break;
        }
        Set<String> endpoints = new HashSet<>(); int emitted = 0;
        for (JsonNode handler : sorted(full.path("endpointHandlers"), "endpointId", "functionId")) {
            String function = handler.path("functionId").asText(), endpoint = handler.path("endpointId").asText();
            if (!functions.contains(function) || !endpointRecords.containsKey(endpoint)) throw new IOException("Invalid AR7 handler reference");
            if (!origin.containsKey(function) || !endpoints.add(endpoint)) continue;
            if (emitted >= options.maxWitnesses) continue; emitted++;
            ObjectNode witness = output.addObject().put("endpointId", endpoint).put("side", side)
                    .put("changedFunctionId", origin.get(function)).put("classification", "potentially-affected-static-data-access-path");
            witness.set("endpoint", endpointRecords.get(endpoint));
            ArrayNode path = witness.putArray("functionPath");
            for (String node = function; node != null; node = next.get(node)) {
                if (path.size() >= 128) { witness.put("pathTruncated", true); break; } path.add(node);
            }
        }
        return new Impact(!bounded, endpoints.size(), endpoints.size() > emitted);
    }

    private ObjectNode entityEvolution(Map<String, JsonNode> before, Map<String, JsonNode> after, Set<String> comparable) throws IOException {
        long backends = after.values().stream().filter(component -> component.path("kind").asText().equals("backend")).count();
        ObjectNode report = observation(backends < 2 ? "not-applicable" : "partial", "Exact nominal entity/field type divergence after change; not proof of shared database or incompatibility");
        ArrayNode findings = report.putArray("candidates");
        if (backends < 2) return report.putNull("candidateCount").put("comparisonComplete", true).put("comparisons", 0);
        long eligible = comparable.stream().filter(component -> component.startsWith("backend:")).count();
        report.put("declaredBackendComponents", backends).put("eligibleBackendComponents", eligible);
        if (eligible < 2) return report.put("status", "unsupported").putNull("candidateCount")
                .put("comparisonComplete", false).put("comparisons", 0)
                .put("reason", "Fewer than two backends have comparable inspected semantic evidence");
        Map<String, Map<String, JsonNode>> oldNames = entityNames(before, comparable), newNames = entityNames(after, comparable);
        Map<JsonNode, Map<String, String>> fieldCache = new IdentityHashMap<>();
        long comparisons = 0, characterWork = 0, candidateCount = 0; boolean bounded = false;
        for (String name : new TreeSet<>(newNames.keySet())) {
            Map<String, JsonNode> current = newNames.get(name), old = oldNames.getOrDefault(name, Map.of());
            List<String> components = new ArrayList<>(current.keySet()); Collections.sort(components);
            for (int i = 0; i < components.size() && !bounded; i++) for (int j = i + 1; j < components.size() && !bounded; j++) {
                if (++comparisons > options.maxEntityComparisons) { bounded = true; break; }
                String a = components.get(i), b = components.get(j); if (!old.containsKey(a) || !old.containsKey(b)) continue;
                Map<String, String> oldA = fieldCache.computeIfAbsent(old.get(a), JavaScriptSemanticService::fields),
                        oldB = fieldCache.computeIfAbsent(old.get(b), JavaScriptSemanticService::fields),
                        newA = fieldCache.computeIfAbsent(current.get(a), JavaScriptSemanticService::fields),
                        newB = fieldCache.computeIfAbsent(current.get(b), JavaScriptSemanticService::fields);
                for (String field : new TreeSet<>(newA.keySet())) {
                    if (++comparisons > options.maxEntityComparisons) { bounded = true; break; }
                    String oa = oldA.get(field), ob = oldB.get(field), na = newA.get(field), nb = newB.get(field);
                    characterWork += field.length() * 4L + (oa == null ? 0 : oa.length()) + (ob == null ? 0 : ob.length())
                            + na.length() + (nb == null ? 0 : nb.length());
                    if (characterWork > 128L * options.maxEntityComparisons) { bounded = true; break; }
                    if (oa != null && oa.equals(ob) && nb != null && !na.equals(nb)) {
                        candidateCount++;
                        if (findings.size() >= options.maxWitnesses) continue;
                        findings.addObject()
                            .put("entityName", name).put("field", field).put("firstComponent", a).put("secondComponent", b)
                            .put("beforeType", oa).put("firstAfterType", na).put("secondAfterType", nb)
                            .put("classification", "nominal-entity-evolution-review-candidate");
                    }
                }
                if (bounded) break;
            }
            if (bounded) break;
        }
        if (bounded) findings.removeAll(); // Never publish an apparently complete count from a prefix search.
        if (bounded) report.putNull("candidateCount"); else report.put("candidateCount", candidateCount);
        return report.put("comparisonComplete", !bounded).put("boundsReached", bounded)
                .put("emittedWitnesses", findings.size()).put("witnessesTruncated", bounded || candidateCount > findings.size())
                .put("comparisons", Math.min(comparisons, options.maxEntityComparisons))
                .put("characterWork", Math.min(characterWork, 128L * options.maxEntityComparisons));
    }

    private static Map<String, Map<String, JsonNode>> entityNames(Map<String, JsonNode> components, Set<String> comparable) {
        Map<String, Map<String, JsonNode>> names = new TreeMap<>(); Set<String> duplicates = new HashSet<>();
        for (String component : comparable) {
            if (!component.startsWith("backend:")) continue;
            for (JsonNode entity : components.get(component).path("entities")) {
                String name = entity.path("name").asText(), key = name + "\0" + component;
                Map<String, JsonNode> matches = names.computeIfAbsent(name, ignored -> new TreeMap<>());
                if (matches.put(component, entity) != null) duplicates.add(key);
            }
        }
        duplicates.forEach(key -> { int split = key.indexOf('\0'); names.get(key.substring(0, split)).remove(key.substring(split + 1)); });
        return names;
    }
    private static Map<String, String> fields(JsonNode entity) {
        Map<String, String> fields = new TreeMap<>(); Set<String> duplicate = new HashSet<>();
        for (JsonNode field : entity.path("fields")) {
            String name = field.path("name").asText(), type = field.path("type").asText();
            if (name.isBlank() || type.isBlank() || type.equals("unknown")) continue;
            if (fields.put(name, type) != null) duplicate.add(name);
        }
        duplicate.forEach(fields::remove); return fields;
    }
    private static boolean completeInspection(JsonNode component) {
        return component.path("coverage").path("semanticEvidenceAvailable").asBoolean()
                && component.path("coverage").path("inspectionComplete").asBoolean()
                && !component.path("coverage").path("boundsReached").asBoolean(true);
    }
    private Map<String, JsonNode> semanticIndex(JsonNode report) throws IOException {
        if (!report.isObject() || report.path("schemaVersion").asInt() != 1 || !ANALYSIS.equals(report.path("analysis").asText())
                || !report.path("components").isArray() || !report.path("findings").isArray()) throw new IOException("Invalid semantic analysis");
        Map<String, JsonNode> entries = new TreeMap<>(); long records = 0;
        for (JsonNode component : report.path("components")) {
            String key = component.path("component").asText();
            if (key.isBlank() || entries.put(key, component) != null) throw new IOException("Invalid semantic component identity");
            for (String field : List.of("dataAccess", "entities", "resilienceEvidence", "proxies", "healthEndpoints")) {
                if (!component.path(field).isArray() || (records += component.path(field).size()) > options.maxRecords)
                    throw new IOException("Invalid or oversized semantic records");
            }
            for (String field : List.of("dataAccess", "entities")) for (JsonNode record : component.path(field))
                if (!record.path("fingerprint").asText().matches("[a-f0-9]{64}")) throw new IOException("Invalid semantic structural fingerprint");
        }
        return entries;
    }

    /** Validate saved semantic reports against the full architecture identities before any historical traversal. */
    public void validateAnalysis(JsonNode report, JsonNode full) throws IOException {
        Map<String, JsonNode> components = semanticIndex(report);
        if (!report.path("limits").equals(limits())) throw new IOException("Semantic saved report limits differ from configured analysis limits");
        if (!full.path("components").isArray() || !full.path("functions").isArray() || !full.path("http").path("endpoints").isArray())
            throw new IOException("Semantic validation requires full component/function/endpoint identities");
        if (full.path("functions").size() > options.maxRecords || full.path("http").path("endpoints").size() > options.maxRecords
                || full.path("components").size() > options.maxRecords || report.path("findings").size() > options.maxRecords)
            throw new IOException("Semantic saved report exceeds identity limits");
        Map<String, JsonNode> fullComponents = index(full.path("components")), functions = index(full.path("functions")), endpoints = index(full.path("http").path("endpoints"));
        if (!components.keySet().equals(fullComponents.keySet())) throw new IOException("Semantic and full component coverage differ");
        long fieldCount = 0;
        for (Map.Entry<String, JsonNode> entry : components.entrySet()) {
            String component = entry.getKey(); JsonNode evidence = entry.getValue();
            if (!evidence.path("kind").equals(fullComponents.get(component).path("kind"))) throw new IOException("Semantic component kind differs");
            if (!evidence.path("coverage").path("semanticEvidenceAvailable").isBoolean()) throw new IOException("Missing semantic availability flag");
            if (evidence.path("coverage").path("semanticEvidenceAvailable").asBoolean()
                    && (!evidence.path("coverage").path("inspectionComplete").isBoolean() || !evidence.path("coverage").path("boundsReached").isBoolean()))
                throw new IOException("Missing semantic inspection coverage flags");
            for (String collection : List.of("dataAccess", "entities", "proxies", "resilienceEvidence")) {
                index(evidence.path(collection));
                for (JsonNode record : evidence.path(collection)) {
                    if (!record.path("component").asText().equals(component)
                            || !record.path("localId").isTextual() || record.path("localId").asText().isBlank()
                            || !record.path("id").asText().equals(qualified(component, record.path("localId").asText()))
                            || !record.path("fingerprint").asText().matches("[a-f0-9]{64}")) throw new IOException("Invalid saved semantic identity/fingerprint");
                    for (String reference : List.of("functionId", "appliesToFunctionId")) {
                        JsonNode id = record.path(reference);
                        if (!id.isNull() && (!id.isTextual() || !functions.containsKey(id.asText())
                                || !functions.get(id.asText()).path("component").asText().equals(component)))
                            throw new IOException("Unknown semantic function reference");
                    }
                    if (collection.equals("entities")) {
                        if (!boundedText(record.path("name"))) throw new IOException("Invalid saved entity name");
                        if (!record.path("fields").isArray() || (fieldCount += record.path("fields").size()) > options.maxRecords)
                            throw new IOException("Invalid or oversized saved entity fields");
                        for (JsonNode field : record.path("fields"))
                            if (!boundedText(field.path("name")) || !boundedText(field.path("type"))) throw new IOException("Invalid saved entity field evidence");
                    }
                    if (collection.equals("dataAccess")) for (String names : List.of("tables", "columns")) {
                        if (!record.path(names).isArray() || (fieldCount += record.path(names).size()) > options.maxRecords)
                            throw new IOException("Invalid or oversized saved data-access names");
                        for (JsonNode name : record.path(names)) if (!boundedText(name)) throw new IOException("Invalid saved data-access name");
                    }
                }
            }
            Set<String> healthIds = new HashSet<>();
            for (JsonNode health : evidence.path("healthEndpoints")) {
                String endpoint = health.path("endpointId").asText();
                if (!healthIds.add(endpoint) || !endpoints.containsKey(endpoint)
                        || !endpoints.get(endpoint).path("component").asText().equals(component)
                        || !boundedText(health.path("localEndpointId")) || !endpoint.equals(qualified(component, health.path("localEndpointId").asText())))
                    throw new IOException("Unknown semantic health endpoint");
                if (!health.path("functionIds").isArray() || (fieldCount += health.path("functionIds").size()) > options.maxRecords)
                    throw new IOException("Invalid or oversized saved health functions");
                for (JsonNode function : health.path("functionIds"))
                    if (!function.isTextual() || !functions.containsKey(function.asText())
                            || !functions.get(function.asText()).path("component").asText().equals(component)) throw new IOException("Unknown semantic health function");
            }
        }
        index(report.path("findings"));
        for (JsonNode finding : report.path("findings"))
            if (!components.containsKey(finding.path("component").asText()) || !finding.path("scope").asText().equals("semantic-evidence"))
                throw new IOException("Invalid semantic finding component/scope");
    }

    private static boolean boundedText(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 4096;
    }
    private static ObjectNode record(JsonNode value, String component) {
        ObjectNode record = object().put("id", qualified(component, value.path("id").asText())).put("localId", value.path("id").asText()).put("component", component);
        for (String field : List.of("file", "line", "endLine", "kind", "library", "operation", "tables", "columns", "shape", "fingerprint", "complete", "name", "fields", "target"))
            if (value.has(field)) record.set(field, value.path(field).deepCopy());
        for (String field : List.of("functionId", "appliesToFunctionId"))
            if (value.path(field).isTextual()) record.put(field, qualified(component, value.path(field).asText())); else record.putNull(field);
        return record;
    }
    private ObjectNode limits() { return object().put("maxRecords", options.maxRecords).put("maxTraversalWork", options.maxTraversalWork)
            .put("maxWitnesses", options.maxWitnesses).put("maxEntityComparisons", options.maxEntityComparisons)
            .put("maxEntityComparisonCharacterWork", 128 * options.maxEntityComparisons); }
    private static ObjectNode observation(String status, String reason) { return object().put("status", status).put("reason", reason); }
    private static ObjectNode finding(String kind, String component, List<String> subjects, String message, String alias) {
        ObjectNode finding = object().put("id", qualified(component, kind + "\0" + String.join("\0", subjects)))
                .put("component", component).put("scope", "semantic-evidence").put("kind", kind)
                .put("classification", "heuristic-review-candidate").put("message", message);
        strings(finding.putArray("subjects"), subjects); ArrayNode aliases = finding.putArray("aliases"); if (alias != null) aliases.add(alias); return finding;
    }
    private static void aliases(ObjectNode result) { result.putObject("aliases").put("AR21", "WrongCuts").put("AR23", "API-gateway").put("AR24", "health-checks"); }
    private static void capability(ArrayNode output, String id, String status, String reason) { output.addObject().put("id", id).put("status", status).put("reason", reason); }
    private static Map<String, JsonNode> index(JsonNode values) throws IOException {
        Map<String, JsonNode> result = new TreeMap<>();
        for (JsonNode value : values) { String id = value.path("id").asText(); if (id.isBlank() || result.put(id, value) != null) throw new IOException("Invalid semantic record identity"); }
        return result;
    }
    private static List<JsonNode> sorted(JsonNode values, String... fields) {
        List<JsonNode> result = new ArrayList<>(); values.forEach(result::add);
        result.sort((a, b) -> { for (String field : fields) { int order = a.path(field).asText().compareTo(b.path(field).asText()); if (order != 0) return order; } return 0; }); return result;
    }
    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> result = new TreeSet<>(a); result.addAll(b); return result; }
    private static void strings(ArrayNode output, Collection<String> values) { values.forEach(output::add); }
    private static String componentKey(JsonNode info) { return info.path("kind").asText() + ":" + info.path("name").asText(); }
    static String qualified(String component, String local) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((component + "\0" + local).getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ObjectNode object() { return JavaScriptExtractionService.JSON.createObjectNode(); }
    private record Impact(boolean complete, int total, boolean truncated) { }
}
