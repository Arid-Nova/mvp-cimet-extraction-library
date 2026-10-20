package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

/** Validates optional source graph evidence before graph traversal or saved-snapshot analysis. */
final class JavaScriptArchitectureValidator {
    private JavaScriptArchitectureValidator() { }

    static void validate(JsonNode snapshot) throws IOException {
        JsonNode architecture = snapshot.path("architecture");
        if (!architecture.isObject() || !architecture.path("schemaVersion").isIntegralNumber()
                || architecture.path("schemaVersion").asInt() != 1)
            fail("Unsupported JavaScript architecture schema");
        JsonNode modules = array(architecture, "modules", 20_000);
        JsonNode dependencies = array(architecture, "dependencies", 500_000);
        JsonNode functions = array(architecture, "functions", 200_000);
        JsonNode calls = array(architecture, "calls", 500_000);
        JsonNode handlers = array(architecture, "endpointHandlers", 100_000);
        Set<String> moduleIds = new HashSet<>(), files = new HashSet<>();
        for (JsonNode module : modules) {
            record(module, moduleIds);
            String file = sourcePath(module, "file");
            if (!module.path("id").asText().equals(file)) fail("Architecture module identity must equal its source path");
            line(module, "line");
            if (!files.add(file)) fail("Duplicate architecture source module");
        }
        Set<String> dependencyIds = new HashSet<>();
        for (JsonNode dependency : dependencies) {
            record(dependency, dependencyIds);
            if (!files.contains(sourcePath(dependency, "source"))) fail("Unknown dependency source module");
            line(dependency, "line");
            text(dependency, "kind");
            if (!dependency.path("typeOnly").isBoolean()) fail("Dependency typeOnly must be explicit");
            String resolution = text(dependency, "resolution");
            if (!Set.of("resolved", "external", "unresolved").contains(resolution))
                fail("Unknown dependency resolution");
            if (resolution.equals("resolved")) {
                if (!files.contains(sourcePath(dependency, "target"))) fail("Unknown dependency target module");
            } else if (!dependency.path("target").isNull()) fail("Unresolved dependency must not invent a target");
        }
        Set<String> functionIds = new HashSet<>();
        for (JsonNode function : functions) {
            record(function, functionIds);
            if (!files.contains(sourcePath(function, "file"))) fail("Unknown function source module");
            line(function, "line");
            line(function, "endLine");
            if (function.path("endLine").asInt() < function.path("line").asInt()) fail("Invalid function source range");
            text(function, "name");
            if (!text(function, "bodyFingerprint").matches("[a-f0-9]{64}")) fail("Invalid function fingerprint");
            if (function.has("unresolvedCalls")) nonnegative(function, "unresolvedCalls");
        }
        Set<String> callIds = new HashSet<>();
        for (JsonNode call : calls) {
            record(call, callIds);
            if (!files.contains(sourcePath(call, "file"))) fail("Unknown function call source module");
            line(call, "line");
            if (!functionIds.contains(text(call, "callerId")) || !functionIds.contains(text(call, "targetId")))
                fail("Unknown function call graph node");
        }
        Set<String> endpointIds = new HashSet<>();
        snapshot.path("endpoints").forEach(endpoint -> endpointIds.add(endpoint.path("id").asText()));
        Set<String> linkedHandlers = new HashSet<>();
        for (JsonNode handler : handlers) {
            if (!handler.isObject() || !endpointIds.contains(text(handler, "endpointId"))
                    || !functionIds.contains(text(handler, "functionId"))) fail("Unknown endpoint handler graph node");
            String pair = handler.path("endpointId").asText() + "\n" + handler.path("functionId").asText();
            if (!linkedHandlers.add(pair)) fail("Duplicate endpoint handler link");
        }
        if (architecture.has("contracts")) validateContracts(architecture.path("contracts"), endpointIds, files);
        if (architecture.has("semanticEvidence"))
            JavaScriptSemanticValidator.validate(architecture.path("semanticEvidence"), files, functionIds, endpointIds);
    }

    private static void validateContracts(JsonNode contracts, Set<String> endpointIds, Set<String> files) throws IOException {
        if (!contracts.isObject() || !contracts.path("schemaVersion").isIntegralNumber()
                || contracts.path("schemaVersion").asInt() != 1) fail("Unsupported operation contract schema");
        Set<String> seen = new HashSet<>();
        int observations = 0;
        for (JsonNode operation : array(contracts, "operations", 100_000)) {
            String endpoint = text(operation, "endpointId");
            if (!endpointIds.contains(endpoint) || !seen.add(endpoint)) fail("Invalid operation contract endpoint");
            for (String flag : List.of("inputComplete", "outputComplete"))
                if (!operation.path(flag).isBoolean()) fail("Contract completeness must be explicit");
            for (JsonNode input : array(operation, "inputs", 4096)) {
                contractEvidence(input, files);
                if (!Set.of("body", "query", "params", "headers").contains(text(input, "location")))
                    fail("Unknown contract input location");
                text(input, "name");
                if (!Set.of("declared", "coercion", "property-read", "route-param").contains(text(input, "evidence")))
                    fail("Unknown contract input evidence");
                observations++;
            }
            for (JsonNode response : array(operation, "responses", 4096)) {
                contractEvidence(response, files);
                if (!Set.of("json", "send", "end").contains(text(response, "kind"))) fail("Unknown response contract kind");
                text(response, "shape");
                if (!response.path("complete").isBoolean()) fail("Response completeness must be explicit");
                observations++;
            }
            for (JsonNode limitation : array(operation, "limitations", 100))
                if (!limitation.isTextual() || limitation.asText().length() > 4096) fail("Invalid contract limitation");
            if (observations > 200_000) fail("Operation contract observation limit exceeded");
        }
        if (!seen.equals(endpointIds)) fail("Contracts must report every extracted endpoint, including unknowns");
        if (contracts.path("summary").has("truncatedOperations")) {
            nonnegative(contracts.path("summary"), "truncatedOperations");
            if (contracts.path("summary").path("truncatedOperations").asInt() > endpointIds.size())
                fail("Contract truncation count exceeds operation count");
        }
    }

    private static void contractEvidence(JsonNode record, Set<String> files) throws IOException {
        if (!record.isObject() || !files.contains(sourcePath(record, "file"))) fail("Unknown contract source module");
        line(record, "line");
        if (!Set.of("string", "number", "boolean", "array", "object", "null", "unknown").contains(text(record, "type")))
            fail("Unknown contract evidence type");
    }

    private static void nonnegative(JsonNode node, String field) throws IOException {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt() || node.path(field).asInt() < 0)
            fail("Invalid architecture count: " + field);
    }

    private static JsonNode array(JsonNode parent, String field, int maximum) throws IOException {
        JsonNode value = parent.path(field);
        if (!value.isArray() || value.size() > maximum) fail("Invalid or oversized architecture array: " + field);
        return value;
    }

    private static void record(JsonNode node, Set<String> ids) throws IOException {
        if (!node.isObject() || !ids.add(text(node, "id"))) fail("Invalid or duplicate architecture record");
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 4096
                || value.asText().chars().anyMatch(Character::isISOControl)) fail("Invalid architecture field: " + field);
        return value.asText();
    }

    private static String sourcePath(JsonNode node, String field) throws IOException {
        String value = text(node, field).replace('\\', '/');
        if (value.startsWith("/") || value.contains(":") || Arrays.asList(value.split("/")).contains(".."))
            fail("Architecture source paths must be relative");
        return value;
    }

    private static void line(JsonNode node, String field) throws IOException {
        if (!node.path(field).isIntegralNumber() || !node.path(field).canConvertToInt()
                || node.path(field).asInt() < 1) fail("Invalid architecture source line");
    }

    private static void fail(String message) throws IOException { throw new IOException(message); }
}
