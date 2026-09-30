package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Deterministic static snapshot comparison; classifications are observations, not breakage predictions. */
public final class JavaScriptDeltaService {
    public ObjectNode compare(JsonNode before, JsonNode after) throws IOException {
        JavaScriptExtractionService.validateSnapshot(before);
        JavaScriptExtractionService.validateSnapshot(after);
        JsonNode oldComponent = before.path("component"), newComponent = after.path("component");
        for (String field : List.of("kind", "name")) {
            if (!oldComponent.path(field).equals(newComponent.path(field)))
                throw new IOException("Cannot compare different JavaScript components");
        }
        String oldRepo = oldComponent.path("repositoryURL").asText(), newRepo = newComponent.path("repositoryURL").asText();
        if (!oldRepo.isBlank() && !newRepo.isBlank() && !oldRepo.equals(newRepo))
            throw new IOException("Cannot compare snapshots from different repositories");
        ObjectNode result = JavaScriptExtractionService.JSON.createObjectNode();
        result.put("schemaVersion", 1);
        result.put("analysis", "static-javascript-snapshot-delta");
        result.set("component", newComponent.deepCopy());
        result.put("oldCommit", oldComponent.path("commit").asText(""));
        result.put("newCommit", newComponent.path("commit").asText(""));
        result.set("endpoints", compareGroup(before.path("endpoints"), after.path("endpoints"), true));
        result.set("calls", compareGroup(before.path("calls"), after.path("calls"), false));
        ObjectNode summary = result.putObject("summary");
        for (String group : List.of("endpoints", "calls")) {
            ObjectNode counts = summary.putObject(group);
            for (String key : List.of("added", "removed", "contractChanged", "implementationChanged"))
                counts.put(key, result.path(group).path(key).size());
            counts.put("unchanged", result.path(group).path("unchangedCount").asInt());
        }
        result.putArray("limitations")
                .add("Contract changes cover extracted HTTP method, path and target observations; payload schemas are not inferred.")
                .add("Implementation fingerprints observe static token changes, not behavioral equivalence or confirmed regressions.")
                .add("Ambiguous identity changes remain additions/removals; no heuristic many-to-many rename pairing.")
                .add("Line numbers, comments, source ordering and provenance commits are not change criteria.");
        return result;
    }

    /** Alias for callers using extraction-service naming. */
    public ObjectNode extract(JsonNode before, JsonNode after) throws IOException { return compare(before, after); }

    private ObjectNode compareGroup(JsonNode oldArray, JsonNode newArray, boolean endpoints) {
        Map<String, JsonNode> oldItems = index(oldArray), newItems = index(newArray);
        List<Pair> pairs = new ArrayList<>();
        for (String id : new TreeSet<>(oldItems.keySet())) {
            if (newItems.containsKey(id)) pairs.add(new Pair(oldItems.remove(id), newItems.remove(id), "stable-id"));
        }
        if (endpoints) {
            pairUnambiguous(oldItems, newItems, pairs, n -> usablePath(n)
                    ? n.path("method").asText() + " " + n.path("normalizedPath").asText() : null, "http-identity");
            pairUnambiguous(oldItems, newItems, pairs, n -> {
                String handler = n.path("handler").asText();
                if (handler.isBlank() || handler.startsWith("<") || handler.startsWith("inline")) return null;
                return n.path("file").asText() + "\n" + handler;
            }, "unambiguous-handler");
        } else {
            pairUnambiguous(oldItems, newItems, pairs, n -> {
                String caller = n.path("caller").asText();
                return caller.isBlank() ? null : n.path("file").asText() + "\n" + caller;
            }, "unambiguous-caller");
        }
        ObjectNode result = JavaScriptExtractionService.JSON.createObjectNode();
        ArrayNode added = result.putArray("added"), removed = result.putArray("removed");
        ArrayNode contract = result.putArray("contractChanged"), implementation = result.putArray("implementationChanged");
        newItems.values().forEach(n -> added.add(safeRecord(n)));
        oldItems.values().forEach(n -> removed.add(safeRecord(n)));
        int unchanged = 0;
        pairs.sort(Comparator.comparing(p -> p.after.path("id").asText()));
        for (Pair pair : pairs) {
            List<String> changed = new ArrayList<>();
            for (String field : List.of("method", "path", "normalizedPath", "target"))
                if (!Objects.equals(pair.before.get(field), pair.after.get(field))) changed.add(field);
            if (bothPresent(pair, "contractFingerprint") && different(pair, "contractFingerprint"))
                changed.add("contractFingerprint");
            boolean bodyChanged = bothPresent(pair, "bodyFingerprint") ? different(pair, "bodyFingerprint")
                    : bothPresent(pair, "fingerprint") && different(pair, "fingerprint");
            bodyChanged |= different(pair, "handler");
            if (!changed.isEmpty()) {
                ObjectNode entry = change(pair);
                ArrayNode fields = entry.putArray("changedFields");
                changed.forEach(fields::add);
                entry.put("implementationChanged", bodyChanged);
                contract.add(entry);
            } else if (bodyChanged) implementation.add(change(pair));
            else unchanged++;
        }
        result.put("unchangedCount", unchanged);
        return result;
    }

    private static boolean usablePath(JsonNode node) { return node.path("normalizedPath").isTextual(); }
    private static boolean bothPresent(Pair pair, String field) { return pair.before.hasNonNull(field) && pair.after.hasNonNull(field); }
    private static boolean different(Pair pair, String field) { return !Objects.equals(pair.before.get(field), pair.after.get(field)); }

    private static ObjectNode change(Pair pair) {
        ObjectNode node = JavaScriptExtractionService.JSON.createObjectNode();
        node.set("before", safeRecord(pair.before));
        node.set("after", safeRecord(pair.after));
        node.put("identityMatch", pair.reason);
        return node;
    }

    private static ObjectNode safeRecord(JsonNode item) {
        ObjectNode copy = JavaScriptExtractionService.JSON.createObjectNode();
        for (String key : List.of("id", "file", "line", "endLine", "method", "path", "normalizedPath", "handler",
                "handlerFile", "handlerLine", "caller", "target", "resolution", "fingerprint", "bodyFingerprint", "contractFingerprint"))
            if (item.has(key) && item.path(key).isValueNode()) copy.set(key, item.get(key));
        return copy;
    }

    private static Map<String, JsonNode> index(JsonNode records) {
        Map<String, JsonNode> result = new TreeMap<>();
        records.forEach(record -> result.put(record.path("id").asText(), record));
        return result;
    }

    private static void pairUnambiguous(Map<String, JsonNode> oldItems, Map<String, JsonNode> newItems,
                                        List<Pair> pairs, Function<JsonNode, String> key, String reason) {
        Map<String, List<String>> oldGroups = group(oldItems, key), newGroups = group(newItems, key);
        for (String match : new TreeSet<>(oldGroups.keySet())) {
            List<String> a = oldGroups.get(match), b = newGroups.get(match);
            if (a.size() == 1 && b != null && b.size() == 1)
                pairs.add(new Pair(oldItems.remove(a.getFirst()), newItems.remove(b.getFirst()), reason));
        }
    }

    private static Map<String, List<String>> group(Map<String, JsonNode> items, Function<JsonNode, String> key) {
        Map<String, List<String>> groups = new TreeMap<>();
        items.forEach((id, item) -> {
            String match = key.apply(item);
            if (match != null) groups.computeIfAbsent(match, ignored -> new ArrayList<>()).add(id);
        });
        return groups;
    }

    private record Pair(JsonNode before, JsonNode after, String reason) { }
}
