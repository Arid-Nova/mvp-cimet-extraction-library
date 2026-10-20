package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

/** Structural and work-limit checks for optional language-adapter semantic evidence. */
final class JavaScriptSemanticValidator {
    private JavaScriptSemanticValidator() { }
    static void validate(JsonNode evidence, Set<String> files, Set<String> functions, Set<String> endpoints) throws IOException {
        if (!evidence.isObject() || !evidence.path("schemaVersion").isIntegralNumber()
                || evidence.path("schemaVersion").asInt() != 1) fail("Unsupported semantic evidence schema");
        Set<String> identities = new HashSet<>();
        long records = 0, elements = 0;
        for (String group : List.of("dataAccess", "entities", "proxies", "resilience")) {
            JsonNode observations = array(evidence, group, 100_000); records += observations.size();
            for (JsonNode record : observations) {
                common(record, files, functions);
                if (!identities.add(text(record, "id", 256))) fail("Duplicate semantic observation identity");
                if (!text(record, "fingerprint", 64).matches("[a-f0-9]{64}")) fail("Invalid semantic fingerprint");
                if (group.equals("dataAccess")) {
                    oneOf(record, "kind", "sql", "orm");
                    oneOf(record, "operation", "select", "insert", "update", "delete", "ddl", "unknown");
                    text(record, "shape", 4096); bool(record, "complete");
                    for (String field : List.of("tables", "columns")) {
                        JsonNode names = array(record, field, 512); elements += names.size();
                        Set<String> unique = new HashSet<>();
                        for (JsonNode name : names) if (!name.isTextual() || !safeIdentifier(name.asText()) || !unique.add(name.asText()))
                            fail("Invalid or duplicate semantic SQL identifier");
                    }
                } else if (group.equals("entities")) {
                    if (!safeIdentifier(text(record, "name", 256))) fail("Invalid entity name");
                    bool(record, "complete");
                    JsonNode fields = array(record, "fields", 512); elements += fields.size();
                    Set<String> unique = new HashSet<>();
                    for (JsonNode field : fields) {
                        String name = text(field, "name", 256);
                        if (!safeIdentifier(name) || !unique.add(name)) fail("Invalid or duplicate entity field");
                        // Type labels are adapter-owned structural tokens, not source values.
                        if (!text(field, "type", 256).matches("[A-Za-z0-9_$<>.,?\\[\\]|:-]+")) fail("Invalid entity type label");
                    }
                } else if (group.equals("proxies")) {
                    oneOf(record, "kind", "middleware", "server");
                    oneOf(record, "operation", "mount", "web", "ws", "listen");
                    oneOf(record, "target", "literal-redacted", "dynamic", "unknown");
                } else {
                    oneOf(record, "kind", "circuit-breaker", "retry", "rate-limit", "concurrency-limit");
                    text(record, "operation", 128);
                    if (record.has("appliesToFunctionId")) reference(record, "appliesToFunctionId", functions);
                }
                if (elements > 500_000) fail("Semantic evidence element limit exceeded");
            }
        }
        Set<String> healthEndpoints = new HashSet<>();
        JsonNode health = array(evidence, "healthEndpoints", 100_000); records += health.size();
        for (JsonNode record : health) {
            String endpoint = text(record, "endpointId", 256);
            if (!endpoints.contains(endpoint) || !healthEndpoints.add(endpoint)) fail("Unknown or duplicate health endpoint");
            oneOf(record, "classification", "health-like-route"); oneOf(record, "basis", "path-name");
            Set<String> seen = new HashSet<>();
            for (JsonNode function : array(record, "functionIds", 4096))
                if (!function.isTextual() || !functions.contains(function.asText()) || !seen.add(function.asText()))
                    fail("Unknown or duplicate health handler");
        }
        if (records > 200_000) fail("Semantic evidence record limit exceeded");
        JsonNode summary = evidence.path("summary");
        if (!summary.isObject()) fail("Semantic inspection coverage required");
        bool(summary, "inspectionComplete"); bool(summary, "boundsReached");
        if (summary.path("inspectionComplete").asBoolean() && summary.path("boundsReached").asBoolean())
            fail("Truncated semantic inspection cannot be complete");
        Iterator<Map.Entry<String, JsonNode>> fields = summary.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (Set.of("inspectionComplete", "boundsReached").contains(field.getKey())) continue;
            if (!field.getValue().isIntegralNumber() || !field.getValue().canConvertToLong() || field.getValue().asLong() < 0)
                fail("Semantic summary counters must be nonnegative integers");
        }
        for (JsonNode limitation : array(evidence, "limitations", 100))
            if (!limitation.isTextual() || limitation.asText().length() > 4096) fail("Invalid semantic limitation");
    }
    private static void common(JsonNode record, Set<String> files, Set<String> functions) throws IOException {
        if (!record.isObject() || !files.contains(text(record, "file", 4096))) fail("Unknown semantic source module");
        for (String field : List.of("line", "endLine"))
            if (!record.path(field).isIntegralNumber() || !record.path(field).canConvertToInt() || record.path(field).asInt() < 1)
                fail("Invalid semantic source line");
        if (record.path("endLine").asInt() < record.path("line").asInt()) fail("Invalid semantic source range");
        reference(record, "functionId", functions);
        if (!text(record, "library", 128).matches("[A-Za-z0-9@_./-]+")) fail("Invalid semantic library label");
    }
    private static void reference(JsonNode record, String field, Set<String> allowed) throws IOException {
        JsonNode value = record.path(field);
        if (!value.isNull() && (!value.isTextual() || !allowed.contains(value.asText()))) fail("Unknown semantic function reference");
    }
    private static JsonNode array(JsonNode node, String field, int max) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isArray() || value.size() > max) fail("Invalid semantic array: " + field);
        return value;
    }
    private static String text(JsonNode node, String field, int max) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max
                || value.asText().chars().anyMatch(Character::isISOControl)) fail("Invalid semantic text: " + field);
        return value.asText();
    }
    private static void bool(JsonNode node, String field) throws IOException {
        if (!node.path(field).isBoolean()) fail("Semantic boolean must be explicit: " + field);
    }
    private static void oneOf(JsonNode node, String field, String... allowed) throws IOException {
        if (!Set.of(allowed).contains(text(node, field, 128))) fail("Invalid semantic enum: " + field);
    }
    private static boolean safeIdentifier(String value) { return value.matches("[A-Za-z_$*][A-Za-z0-9_$.*-]{0,255}"); }
    private static void fail(String message) throws IOException { throw new IOException(message); }
}
