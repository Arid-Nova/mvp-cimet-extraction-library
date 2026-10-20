package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

/** Structural and relationship validation of persisted HTTP observations; does not re-run matching. */
public final class JavaScriptHttpAnalysisValidator {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE");
    private static final Set<String> CALL_STATUSES = Set.of("matched", "unmatched", "ambiguous", "case-only-candidate", "unknown", "external");
    private static final Set<String> ENDPOINT_STATUSES = Set.of("observed-candidate-caller", "no-observed-caller", "unknown");
    private static final Set<String> TARGETS = Set.of("backend", "external", "unknown");
    private static final Set<String> RESOLUTIONS = Set.of("literal", "template", "unresolved");
    private static final int MAX_REFERENCES = 20_000_000;

    private JavaScriptHttpAnalysisValidator() { }

    /** Fail closed for malformed persisted state before temporal rules interpret a missing match. */
    public static void validate(JsonNode full, int maxRecords) throws IOException {
        if (maxRecords < 1 || maxRecords > 500000) throw new IllegalArgumentException("Invalid HTTP record limit");
        if (full == null || !full.isObject() || !full.path("http").isObject()
                || !full.path("http").path("summary").isObject()
                || !full.path("http").path("summary").path("matchingComplete").isBoolean())
            throw new IOException("HTTP analysis requires an explicit boolean matchingComplete");
        Map<String, JsonNode> components = index(full.path("components"), maxRecords, "component");
        for (JsonNode component : components.values())
            if (!Set.of("backend", "frontend").contains(text(component, "kind", 32))) throw new IOException("Invalid HTTP component kind");
        JsonNode http = full.path("http");
        Map<String, JsonNode> endpoints = index(http.path("endpoints"), maxRecords, "endpoint");
        Map<String, JsonNode> calls = index(http.path("calls"), maxRecords, "call");
        for (JsonNode endpoint : endpoints.values()) {
            source(endpoint, components);
            String status = text(endpoint, "status", 64), method = method(endpoint, true);
            if (!ENDPOINT_STATUSES.contains(status)) throw new IOException("Unknown saved HTTP endpoint status");
            path(endpoint, !status.equals("unknown"));
            if (!status.equals("unknown") && !METHODS.contains(method) && !method.equals("ALL"))
                throw new IOException("Known HTTP endpoint status requires a known method");
            if (endpoint.has("caseSensitiveRouting") && !endpoint.path("caseSensitiveRouting").isBoolean())
                throw new IOException("Invalid endpoint routing semantics");
            if (endpoint.has("routingSemanticsBasis") && !Set.of("explicit", "conservative-default").contains(text(endpoint, "routingSemanticsBasis", 32)))
                throw new IOException("Invalid endpoint routing semantics basis");
        }
        int[] references = {0};
        for (JsonNode call : calls.values()) {
            source(call, components);
            String status = text(call, "status", 64), method = method(call, false), target = text(call, "target", 32);
            String resolution = text(call, "resolution", 32);
            if (!CALL_STATUSES.contains(status) || !TARGETS.contains(target) || !RESOLUTIONS.contains(resolution))
                throw new IOException("Unknown saved HTTP call classification");
            boolean evaluated = Set.of("matched", "unmatched", "ambiguous", "case-only-candidate").contains(status);
            path(call, evaluated);
            if (evaluated && (!target.equals("backend") || !METHODS.contains(method) || resolution.equals("unresolved")))
                throw new IOException("Evaluated HTTP call requires a known backend request");
            if (evaluated && whollyDynamic(call.path("normalizedPath").asText()))
                throw new IOException("A wholly dynamic call path cannot establish an evaluated relationship");
            if (status.equals("external") != target.equals("external"))
                throw new IOException("External HTTP target and status disagree");
            Set<String> used = new HashSet<>();
            List<JsonNode> matches = candidates(call, "candidateEndpointIds", true, endpoints, components, used, maxRecords, references);
            List<JsonNode> caseOnly = candidates(call, "caseOnlyCandidateEndpointIds", false, endpoints, components, used, maxRecords, references);
            List<JsonNode> unsupported = candidates(call, "unsupportedTemplateCandidateEndpointIds", false, endpoints, components, used, maxRecords, references);
            int count = matches.size(), alternatives = caseOnly.size(), uncertain = unsupported.size();
            switch (status) {
                case "matched" -> {
                    if (count != 1 || alternatives != 0 || uncertain != 0) throw new IOException("Matched HTTP call requires one unique endpoint");
                    JsonNode endpoint = matches.getFirst();
                    if (!text(call, "targetComponent", 4096).equals(endpoint.path("component").asText()))
                        throw new IOException("Matched HTTP target component disagrees with its endpoint");
                    if (!Set.of("literal-candidate", "template-candidate").contains(text(call, "matchKind", 32)))
                        throw new IOException("Invalid matched HTTP candidate kind");
                    if (!methodCompatible(method, endpoint.path("method").asText().toUpperCase(Locale.ROOT)))
                        throw new IOException("Matched HTTP methods are incompatible");
                }
                case "unmatched", "external" -> {
                    if (!used.isEmpty()) throw new IOException("Unmatched/external HTTP call cannot have endpoint candidates");
                }
                case "ambiguous" -> {
                    if (count < 1 || count + alternatives < 2 || uncertain != 0)
                        throw new IOException("Ambiguous HTTP call requires multiple eligible alternatives");
                }
                case "case-only-candidate" -> {
                    if (count != 0 || alternatives < 1 || uncertain != 0) throw new IOException("Invalid case-only HTTP candidates");
                }
                default -> { /* Unknown permits partial candidate evidence, but never a unique-target assertion. */ }
            }
            if (!status.equals("matched") && (call.has("targetComponent") || call.has("matchKind")))
                throw new IOException("Only matched HTTP calls may assert a target component or match kind");
        }
    }

    private static List<JsonNode> candidates(JsonNode call, String field, boolean required, Map<String, JsonNode> endpoints,
                                              Map<String, JsonNode> components, Set<String> used, int maxRecords, int[] references) throws IOException {
        JsonNode values = call.path(field);
        if (!required && values.isMissingNode()) return List.of();
        if (!values.isArray() || values.size() > maxRecords) throw new IOException("Invalid HTTP candidate array");
        List<JsonNode> output = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            if (++references[0] > MAX_REFERENCES) throw new IOException("HTTP candidate reference budget exceeded");
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 4096)
                throw new IOException("Invalid HTTP candidate identity");
            JsonNode endpoint = endpoints.get(value.asText());
            if (endpoint == null || !used.add(value.asText())) throw new IOException("Missing or duplicate HTTP candidate endpoint");
            JsonNode component = components.get(endpoint.path("component").asText());
            if (component == null || !component.path("kind").asText().equals("backend"))
                throw new IOException("HTTP endpoint candidate must belong to a supplied backend component");
            path(endpoint, true);
            String method = endpoint.path("method").asText().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method) && !method.equals("ALL")) throw new IOException("Candidate endpoint has an unknown method");
            output.add(endpoint);
        }
        return output;
    }

    private static void source(JsonNode record, Map<String, JsonNode> components) throws IOException {
        if (!components.containsKey(text(record, "component", 4096))) throw new IOException("HTTP source component is missing");
        String file = text(record, "file", 4096).replace('\\', '/');
        if (file.startsWith("/") || file.contains(":") || Arrays.asList(file.split("/", -1)).contains(".."))
            throw new IOException("HTTP source file must be repository relative");
        for (String field : List.of("line", "endLine"))
            if (record.has(field) && (!record.path(field).isIntegralNumber() || !record.path(field).canConvertToInt() || record.path(field).asInt() < 1))
                throw new IOException("Invalid HTTP source line");
        if (record.has("line") && record.has("endLine") && record.path("endLine").asInt() < record.path("line").asInt())
            throw new IOException("Invalid HTTP source line range");
    }
    private static String method(JsonNode record, boolean endpoint) throws IOException {
        String method = text(record, "method", 32).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method) && !method.equals("UNKNOWN") && !(endpoint && method.equals("ALL")))
            throw new IOException("Invalid saved HTTP method");
        return method;
    }
    private static void path(JsonNode record, boolean required) throws IOException {
        JsonNode value = record.path("normalizedPath");
        if (value.isNull() || value.isMissingNode()) { if (required) throw new IOException("Known HTTP relationship requires a normalized path"); return; }
        if (!value.isTextual() || value.asText().length() > 16384 || !value.asText().startsWith("/")
                || value.asText().chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid saved normalized HTTP path");
    }
    private static boolean whollyDynamic(String path) {
        boolean parameter = false;
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) continue;
            if (!segment.equals("{param}")) return false;
            parameter = true;
        }
        return parameter;
    }
    private static boolean methodCompatible(String call, String endpoint) {
        return call.equals(endpoint) || endpoint.equals("ALL") || call.equals("HEAD") && endpoint.equals("GET");
    }
    private static Map<String, JsonNode> index(JsonNode array, int maxRecords, String label) throws IOException {
        if (!array.isArray() || array.size() > maxRecords) throw new IOException("Invalid HTTP " + label + " records");
        Map<String, JsonNode> output = new TreeMap<>();
        for (JsonNode record : array)
            if (!record.isObject() || output.putIfAbsent(text(record, "id", 4096), record) != null)
                throw new IOException("Duplicate HTTP " + label + " identity");
        return output;
    }
    private static String text(JsonNode record, String field, int limit) throws IOException {
        JsonNode value = record.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit
                || value.asText().chars().anyMatch(Character::isISOControl)) throw new IOException("Invalid saved HTTP field: " + field);
        return value.asText();
    }
}
