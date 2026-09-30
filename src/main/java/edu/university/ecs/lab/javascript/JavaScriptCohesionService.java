package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bounded cohesion observations for declared backend applications, never inferred endpoint services.
 * Numeric formulas follow CIMET2 ServiceInterfaceDataCohesion, LackOfMessageLevelCohesion and
 * StrictServiceImplementationCohesion. The last class's executable formula deduplicates shared
 * elements and multiplies by two, unlike its older prose description. Here implementation elements
 * are resolved local functions reachable from all bound handlers/middleware, not annotation types.
 */
public final class JavaScriptCohesionService {
    public record Options(int maxOperations, int maxReachabilityWork, int maxPairComparisons) {
        public Options {
            if (maxOperations < 1 || maxOperations > 100_000 || maxReachabilityWork < 1
                    || maxReachabilityWork > 10_000_000 || maxPairComparisons < 1
                    || maxPairComparisons > 10_000_000) throw new IllegalArgumentException("Invalid cohesion limits");
        }
        public static Options defaults() { return new Options(10_000, 1_000_000, 1_000_000); }
    }

    private static final Set<String> KNOWN_TYPES = Set.of("string", "number", "boolean", "array", "object", "null");
    private static final Pattern UNKNOWN_SHAPE_TYPE = Pattern.compile("(^|[:|,<\\[(])unknown(?=$|[|,>\\])}])");
    private final Options options;
    public JavaScriptCohesionService() { this(Options.defaults()); }
    public JavaScriptCohesionService(Options options) { this.options = Objects.requireNonNull(options); }

    public ObjectNode analyze(List<? extends JsonNode> snapshots) throws IOException {
        if (snapshots == null || snapshots.isEmpty()) throw new IllegalArgumentException("Cohesion requires snapshots");
        ObjectNode output = object().put("schemaVersion", 1).put("scope", "declared-backend-applications");
        output.putObject("limits").put("maxOperations", options.maxOperations)
                .put("maxReachabilityWork", options.maxReachabilityWork).put("maxPairComparisons", options.maxPairComparisons)
                .put("maxPairComparisonCharacterWork", 128L * options.maxPairComparisons);
        ArrayNode components = output.putArray("components");
        List<JsonNode> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator.comparing(snapshot -> componentKey(snapshot.path("component"))));
        Set<String> seen = new HashSet<>();
        long operationCount = 0;
        for (JsonNode snapshot : ordered) {
            JavaScriptExtractionService.validateSnapshot(snapshot);
            String component = componentKey(snapshot.path("component"));
            if (!seen.add(component)) throw new IOException("Duplicate cohesion component");
            if (snapshot.path("component").path("kind").asText().equals("backend")) {
                operationCount += snapshot.path("endpoints").size();
                if (operationCount > options.maxOperations) throw new IOException("Cohesion operation limit exceeded");
            }
            components.add(analyzeComponent(snapshot));
        }
        output.putArray("limitations")
                .add("Operation groups are whole declared backend applications; endpoint registrations, including duplicate routes, remain operations.")
                .add("SIDC and LOMLC use coarse extracted type tags, not validated nominal DTO equivalence. Their strict values require complete evidence for every operation.")
                .add("Available-evidence estimates use only eligible operations and are not whole-application measurements; coverage and endpoint IDs are retained.")
                .add("Observed field/shape proxies compare field identities and canonical response shapes; they are different operationalizations from the original typed metrics.")
                .add("SSIC uses reachable resolved local functions including all bound middleware; unproven calls, dynamic dispatch and external implementations are absent.")
                .add("SSIC preserves the legacy two-times-shared-distinct formula with the full operation-count denominator; its scale depends on operation count.")
                .add("Fewer than two operations, unknown evidence and empty undefined denominators yield null instead of inherited trivial or non-finite values.")
                .add("These static measurements do not establish design defects, runtime behavior, or degradation.");
        return output;
    }

    private ObjectNode analyzeComponent(JsonNode snapshot) throws IOException {
        JsonNode info = snapshot.path("component");
        String kind = info.path("kind").asText();
        ObjectNode result = object().put("component", componentKey(info)).put("name", info.path("name").asText())
                .put("kind", kind).put("operationCount", snapshot.path("endpoints").size());
        if (!kind.equals("backend")) {
            result.put("status", "not-applicable");
            for (String metric : List.of("SIDC", "LOMLC", "SSIC"))
                result.set(metric, unavailable("not-applicable", "Frontend components are not backend operation groups", 0));
            result.putArray("operations");
            return result;
        }
        List<JsonNode> endpoints = new ArrayList<>(); snapshot.path("endpoints").forEach(endpoints::add);
        endpoints.sort(Comparator.comparing(endpoint -> endpoint.path("id").asText()));
        JsonNode architecture = snapshot.path("architecture");
        Map<String, JsonNode> contracts = new HashMap<>();
        Set<String> endpointIds = new HashSet<>(); endpoints.forEach(endpoint -> endpointIds.add(endpoint.path("id").asText()));
        for (JsonNode contract : architecture.path("contracts").path("operations")) {
            String endpoint = contract.path("endpointId").asText();
            if (!endpointIds.contains(endpoint) || contracts.put(endpoint, contract) != null)
                throw new IOException("Unknown or duplicate cohesion contract endpoint");
        }
        List<Operation> operations = new ArrayList<>();
        ArrayNode evidence = result.putArray("operations");
        for (JsonNode endpoint : endpoints) {
            Operation operation = operation(endpoint.path("id").asText(), contracts.get(endpoint.path("id").asText()));
            operations.add(operation);
            ObjectNode item = evidence.addObject().put("endpointId", operation.id)
                    .put("file", endpoint.path("file").asText()).put("line", endpoint.path("line").asInt())
                    .put("contractObserved", operation.contractObserved).put("completeTypedInputs", operation.completeInput)
                    .put("completeTypedOutputs", operation.completeOutput).put("knownInputTypeCount", operation.inputTypes.size())
                    .put("observedInputFieldCount", operation.inputFields.size()).put("unknownInputFieldCount", operation.unknownInputs)
                    .put("knownResponseTypeCount", operation.outputTypes.size()).put("knownResponseShapeCount", operation.outputShapes.size())
                    .put("unknownResponseObservationCount", operation.unknownOutputs);
            strings(item.putArray("knownInputTypes"), operation.inputTypes);
            strings(item.putArray("knownResponseTypes"), operation.outputTypes);
        }
        result.put("status", operations.isEmpty() ? "not-applicable" : "partial");
        result.set("SIDC", typedSidc(operations));
        result.set("LOMLC", typedLomlc(operations));
        ObjectNode proxy = result.putObject("observedFieldShapeProxy")
                .put("definition", "Observed location-qualified input field identities and canonical known response shapes; not original typed cohesion");
        proxy.set("SIDC", sidc(operations, true));
        proxy.set("LOMLC", lomlc(operations, true));
        result.set("SSIC", implementationCohesion(operations, architecture, evidence));
        JsonNode truncated = architecture.path("contracts").path("summary").path("truncatedOperations");
        boolean truncationKnown = truncated.isIntegralNumber() && truncated.canConvertToInt()
                && truncated.asInt() >= 0 && truncated.asInt() <= operations.size();
        for (String name : List.of("SIDC", "LOMLC")) {
            ObjectNode metric = (ObjectNode) result.path(name);
            for (ObjectNode target : List.of(metric.withObject("coverage"), metric.withObject("availableEvidenceEstimate"),
                    (ObjectNode) proxy.path(name))) {
                target.put("sourceContractTruncationKnown", truncationKnown);
                if (truncationKnown) target.put("sourceContractTruncatedOperations", truncated.asInt());
                else target.putNull("sourceContractTruncatedOperations");
            }
        }
        return result;
    }

    private Operation operation(String id, JsonNode contract) {
        SortedSet<String> types = new TreeSet<>(), fields = new TreeSet<>(), outputTypes = new TreeSet<>(), shapes = new TreeSet<>();
        int unknownInputs = 0, unknownOutputs = 0;
        if (contract != null) {
            for (JsonNode input : contract.path("inputs")) {
                String type = input.path("type").asText();
                if (KNOWN_TYPES.contains(type)) types.add(type); else unknownInputs++;
                String location = input.path("location").asText(), name = input.path("name").asText();
                if (!location.isBlank() && !name.isBlank()) fields.add(location + "." + name);
            }
            for (JsonNode response : contract.path("responses")) {
                String type = response.path("type").asText();
                if (KNOWN_TYPES.contains(type)) outputTypes.add(type); else unknownOutputs++;
                String shape = response.path("shape").asText();
                if (KNOWN_TYPES.contains(type) && !shape.isBlank() && !UNKNOWN_SHAPE_TYPE.matcher(shape).find()) shapes.add(shape);
            }
        }
        boolean completeInput = contract != null && contract.path("inputComplete").asBoolean(false) && unknownInputs == 0;
        boolean completeOutput = contract != null && contract.path("outputComplete").asBoolean(false)
                && contract.path("responses").size() > 0 && unknownOutputs == 0;
        if (contract != null) for (JsonNode response : contract.path("responses")) completeOutput &= response.path("complete").asBoolean(false);
        return new Operation(id, contract != null, types, fields, outputTypes, shapes,
                completeInput, completeOutput, unknownInputs, unknownOutputs);
    }

    private ObjectNode typedSidc(List<Operation> operations) {
        ObjectNode estimate = sidc(operations, false);
        boolean complete = operations.stream().allMatch(operation -> operation.completeInput);
        ObjectNode result = typedMetric(operations, estimate, complete,
                "distinct input types observed in at least two operations / distinct input types in their union",
                "Complete coarse input type sets for every declared operation");
        result.put("inputBasis", "coarse-type-tags");
        return result;
    }

    private ObjectNode typedLomlc(List<Operation> operations) {
        ObjectNode estimate = lomlc(operations, false);
        boolean complete = operations.stream().allMatch(operation -> operation.completeInput && operation.completeOutput);
        ObjectNode result = typedMetric(operations, estimate, complete,
                "mean over unordered operation pairs of 1 - (Jaccard(input type sets) + identical response type-set indicator) / 2",
                "Complete coarse input/output type sets for every declared operation");
        result.put("inputBasis", "coarse-type-tags").put("responseIdentityBasis", "sorted-coarse-response-type-set");
        return result;
    }

    private ObjectNode typedMetric(List<Operation> operations, ObjectNode estimate, boolean complete, String formula, String requirement) {
        ObjectNode result = object().put("formula", formula).put("strictRequirement", requirement);
        if (complete) result.set("value", estimate.path("value")); else result.putNull("value");
        String status = operations.size() < 2 ? "not-applicable" : !result.path("value").isNull() ? "supported" : "partial";
        result.put("status", status).put("reason", complete ? estimate.path("reason").asText()
                : "Strict value unavailable: at least one operation lacks complete typed contract evidence");
        result.set("coverage", contractCoverage(operations));
        result.set("availableEvidenceEstimate", estimate);
        result.put("comparisonComplete", estimate.path("comparisonComplete").asBoolean())
                .put("boundsReached", estimate.path("boundsReached").asBoolean());
        return result;
    }

    /** Frequency >= 2 is exactly the union of every pairwise set intersection, without quadratic pairs. */
    private ObjectNode sidc(List<Operation> operations, boolean fields) {
        List<Operation> eligible = operations.stream().filter(operation ->
                !(fields ? operation.inputFields : operation.inputTypes).isEmpty() || operation.completeInput).toList();
        ObjectNode output = estimateBase(operations.size(), eligible);
        output.put("basis", fields ? "observed-location-qualified-field-identities" : "known-coarse-input-types");
        Map<String, Integer> frequency = new HashMap<>();
        for (Operation operation : eligible) for (String input : fields ? operation.inputFields : operation.inputTypes)
            frequency.merge(input, 1, Integer::sum);
        long shared = frequency.values().stream().filter(count -> count >= 2).count();
        output.put("distinctElements", frequency.size()).put("sharedDistinctElements", shared)
                .put("comparisonComplete", true).put("boundsReached", false);
        if (eligible.size() < 2 || frequency.isEmpty()) {
            output.putNull("value").put("status", "not-applicable").put("reason", "Fewer than two eligible operations or no known input elements");
        } else output.put("value", (double) shared / frequency.size()).put("status", "partial")
                .put("reason", "Available observed elements only; eligible subset and type/field granularity are explicit");
        return output;
    }

    /** Group identical input/output signatures, then weight group pairs by operation multiplicity. */
    private ObjectNode lomlc(List<Operation> operations, boolean fieldShape) {
        List<Operation> eligible = operations.stream().filter(operation ->
                (!(fieldShape ? operation.inputFields : operation.inputTypes).isEmpty() || operation.completeInput)
                        && !(fieldShape ? operation.outputShapes : operation.outputTypes).isEmpty()).toList();
        ObjectNode output = estimateBase(operations.size(), eligible);
        output.put("basis", fieldShape ? "observed-field-overlap-and-canonical-response-shape-identity" : "known-type-overlap-and-response-type-set-identity");
        long pairs = pairs(eligible.size()); output.put("eligibleOperationPairs", pairs);
        Map<Signature, Integer> counts = new LinkedHashMap<>();
        for (Operation operation : eligible) counts.merge(new Signature(List.copyOf(fieldShape ? operation.inputFields : operation.inputTypes),
                List.copyOf(fieldShape ? operation.outputShapes : operation.outputTypes)), 1, Integer::sum);
        List<Map.Entry<Signature, Integer>> groups = new ArrayList<>(counts.entrySet());
        long comparisonsNeeded = pairs(groups.size()) + groups.stream().filter(group -> group.getValue() >= 2).count();
        long signatureCharacters = groups.stream().mapToLong(group ->
                group.getKey().inputs.stream().mapToLong(String::length).sum()
                        + group.getKey().responses.stream().mapToLong(String::length).sum()).sum();
        // Every signature appears in g-1 cross-group comparisons. Count character widths, not just fields:
        // long common-prefix names must not turn one permitted pair into unbounded comparison work.
        long characterWork = comparisonsNeeded + Math.max(0L, groups.size() - 1L) * signatureCharacters;
        long characterLimit = 128L * options.maxPairComparisons;
        output.put("distinctSignatures", groups.size()).put("pairComparisonsRequired", comparisonsNeeded)
                .put("pairComparisonCharacterWorkRequired", characterWork).put("maxPairComparisonCharacterWork", characterLimit);
        if (pairs == 0 || comparisonsNeeded > options.maxPairComparisons || characterWork > characterLimit) {
            boolean bounded = comparisonsNeeded > options.maxPairComparisons || characterWork > characterLimit;
            return output.putNull("value").put("status", pairs == 0 ? "not-applicable" : "partial")
                    .put("reason", bounded ? "Pair-count or character-work bound exceeded; no truncated estimate emitted" : "Fewer than two eligible operations")
                    .put("comparisonComplete", !bounded).put("boundsReached", bounded).put("pairComparisons", 0);
        }
        double sum = 0;
        for (int i = 0; i < groups.size(); i++) {
            Map.Entry<Signature, Integer> first = groups.get(i);
            // Identical signatures have iDS=oDS=1 and contribute zero, including proven empty inputs.
            for (int j = i + 1; j < groups.size(); j++) {
                Map.Entry<Signature, Integer> second = groups.get(j);
                double input = jaccard(first.getKey().inputs, second.getKey().inputs);
                double outputSimilarity = first.getKey().responses.equals(second.getKey().responses) ? 1 : 0;
                sum += (1 - (input + outputSimilarity) / 2) * (long) first.getValue() * second.getValue();
            }
        }
        return output.put("value", sum / pairs).put("status", "partial")
                .put("reason", "Available observed contracts only; eligible subset and response identity are explicit")
                .put("comparisonComplete", true).put("boundsReached", false).put("pairComparisons", comparisonsNeeded);
    }

    private ObjectNode implementationCohesion(List<Operation> operations, JsonNode architecture, ArrayNode evidence) throws IOException {
        ObjectNode output = object().put("formula", "2 * sharedDistinctReachableFunctions / (distinctReachableFunctions * fullOperationCount)")
                .put("implementationBasis", "union of resolved local functions reachable from all bound endpoint handlers and middleware")
                .put("status", operations.size() < 2 ? "not-applicable" : "partial");
        ObjectNode coverage = output.putObject("coverage").put("operationCount", operations.size());
        Map<String, JsonNode> functions = new TreeMap<>();
        for (JsonNode function : architecture.path("functions")) functions.put(function.path("id").asText(), function);
        Map<String, SortedSet<String>> graph = new HashMap<>(), seeds = new HashMap<>();
        Set<String> endpointIds = new HashSet<>(); operations.forEach(operation -> endpointIds.add(operation.id));
        for (JsonNode call : architecture.path("calls")) {
            String source = call.path("callerId").asText(), target = call.path("targetId").asText();
            if (!functions.containsKey(source) || !functions.containsKey(target)) throw new IOException("Unknown cohesion call graph function");
            graph.computeIfAbsent(source, ignored -> new TreeSet<>()).add(target);
        }
        for (JsonNode handler : architecture.path("endpointHandlers")) {
            String endpoint = handler.path("endpointId").asText(), function = handler.path("functionId").asText();
            if (!endpointIds.contains(endpoint) || !functions.containsKey(function)) throw new IOException("Unknown cohesion handler reference");
            seeds.computeIfAbsent(endpoint, ignored -> new TreeSet<>()).add(function);
        }
        coverage.put("operationsWithResolvedHandlers", seeds.size()).put("operationsWithoutResolvedHandlers", operations.size() - seeds.size());
        Map<String, Integer> frequency = new TreeMap<>();
        long work = 0; int completed = 0; boolean bounded = false;
        for (int index = 0; index < operations.size(); index++) {
            Operation operation = operations.get(index);
            Set<String> start = seeds.getOrDefault(operation.id, Collections.emptySortedSet());
            ObjectNode operationEvidence = (ObjectNode) evidence.get(index);
            operationEvidence.put("resolvedHandlerCount", start.size());
            Set<String> reached = new HashSet<>(); Deque<String> pending = new ArrayDeque<>(start);
            while (!pending.isEmpty()) {
                if (++work > options.maxReachabilityWork) { bounded = true; break; }
                String function = pending.removeFirst();
                if (!reached.add(function)) continue;
                for (String target : graph.getOrDefault(function, Collections.emptySortedSet())) {
                    if (++work > options.maxReachabilityWork) { bounded = true; break; }
                    if (!reached.contains(target)) pending.addLast(target);
                }
                if (bounded) break;
            }
            if (bounded) { operationEvidence.putNull("reachableFunctionCount"); break; }
            operationEvidence.put("reachableFunctionCount", reached.size()); completed++;
            for (String function : reached) frequency.merge(function, 1, Integer::sum);
        }
        long shared = frequency.values().stream().filter(count -> count >= 2).count();
        long unresolved = 0; int missingCounters = 0;
        for (String function : frequency.keySet()) {
            JsonNode count = functions.get(function).path("unresolvedCalls");
            if (count.isIntegralNumber() && count.canConvertToLong() && count.asLong() >= 0) unresolved += count.asLong(); else missingCounters++;
        }
        coverage.put("operationsTraversed", completed).put("distinctReachableFunctions", frequency.size())
                .put("sharedDistinctReachableFunctions", shared).put("unresolvedCallsInObservedFunctionUnion", unresolved)
                .put("uniqueImplementationElements", frequency.size()).put("sharedImplementationElements", shared)
                .put("reachableFunctionsMissingUnresolvedCallCount", missingCounters)
                .put("reachabilityWork", Math.min(work, options.maxReachabilityWork)).put("reachabilityCompleteForObservedGraph", !bounded);
        if (bounded || frequency.isEmpty()) coverage.putNull("sharedElementFraction");
        else coverage.put("sharedElementFraction", (double) shared / frequency.size());
        if (operations.size() < 2) coverage.putNull("theoreticalMaximum"); else coverage.put("theoreticalMaximum", 2.0 / operations.size());
        output.put("boundsReached", bounded).put("comparisonComplete", !bounded);
        if (bounded || operations.size() < 2 || frequency.isEmpty()) output.putNull("value").put("reason", bounded
                ? "Reachability bound exceeded; no estimate from truncated operation sets emitted"
                : "Fewer than two operations or no resolved implementation elements");
        else output.put("value", 2.0 * shared / ((double) frequency.size() * operations.size()))
                .put("reason", "Observed local-function estimate with full operation denominator; unresolved/external behavior is not complete");
        return output;
    }

    private static ObjectNode contractCoverage(List<Operation> operations) {
        return object().put("operationCount", operations.size())
                .put("operationsWithContractRecords", operations.stream().filter(operation -> operation.contractObserved).count())
                .put("operationsWithKnownInputTypes", operations.stream().filter(operation -> !operation.inputTypes.isEmpty()).count())
                .put("operationsWithKnownResponseTypes", operations.stream().filter(operation -> !operation.outputTypes.isEmpty()).count())
                .put("completeTypedInputOperations", operations.stream().filter(operation -> operation.completeInput).count())
                .put("completeTypedOutputOperations", operations.stream().filter(operation -> operation.completeOutput).count())
                .put("unknownInputFieldObservations", operations.stream().mapToLong(operation -> operation.unknownInputs).sum())
                .put("unknownResponseObservations", operations.stream().mapToLong(operation -> operation.unknownOutputs).sum());
    }
    private static ObjectNode estimateBase(int total, List<Operation> eligible) {
        ObjectNode node = object().put("operationCount", total).put("eligibleOperations", eligible.size());
        if (total == 0) node.putNull("eligibleOperationFraction"); else node.put("eligibleOperationFraction", (double) eligible.size() / total);
        ArrayNode ids = node.putArray("eligibleEndpointIds"); eligible.forEach(operation -> ids.add(operation.id));
        return node;
    }
    private static ObjectNode unavailable(String status, String reason, int operations) {
        ObjectNode node = object().put("status", status).put("reason", reason).putNull("value");
        node.putObject("coverage").put("operationCount", operations); return node;
    }
    private static double jaccard(List<String> first, List<String> second) {
        int i = 0, j = 0, common = 0;
        while (i < first.size() && j < second.size()) {
            int compared = first.get(i).compareTo(second.get(j));
            if (compared == 0) { common++; i++; j++; } else if (compared < 0) i++; else j++;
        }
        int union = first.size() + second.size() - common;
        return union == 0 ? 1 : (double) common / union;
    }
    private static long pairs(long size) { return size * (size - 1) / 2; }
    private static void strings(ArrayNode target, Collection<String> values) { values.forEach(target::add); }
    private static String componentKey(JsonNode info) { return info.path("kind").asText() + ":" + info.path("name").asText(); }
    private static ObjectNode object() { return JavaScriptExtractionService.JSON.createObjectNode(); }
    private record Signature(List<String> inputs, List<String> responses) { }
    private record Operation(String id, boolean contractObserved, SortedSet<String> inputTypes, SortedSet<String> inputFields,
                             SortedSet<String> outputTypes, SortedSet<String> outputShapes,
                             boolean completeInput, boolean completeOutput, int unknownInputs, int unknownOutputs) { }
}
