package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** AR1-style temporal evidence: an unchanged observed caller loses its previously unique endpoint. */
public final class JavaScriptEndpointRemovalService {
    public record Options(int maxRecords, int maxWitnesses) {
        public Options {
            if (maxRecords < 1 || maxRecords > 500_000 || maxWitnesses < 1 || maxWitnesses > 10_000)
                throw new IllegalArgumentException("Invalid endpoint removal limits");
        }
        public static Options defaults() { return new Options(150_000, 100); }
    }
    private final Options options;
    public JavaScriptEndpointRemovalService() { this(Options.defaults()); }
    public JavaScriptEndpointRemovalService(Options options) { this.options = Objects.requireNonNull(options); }

    public ObjectNode compare(JsonNode before, JsonNode after) throws IOException {
        JavaScriptHttpAnalysisValidator.validate(before, options.maxRecords);
        JavaScriptHttpAnalysisValidator.validate(after, options.maxRecords);
        if (!before.path("name").equals(after.path("name"))) throw new IOException("Endpoint removal analysis names differ");
        Map<String, JsonNode> oldComponents = index(before.path("components")), currentComponents = index(after.path("components"));
        for (String component : oldComponents.keySet()) {
            if (!currentComponents.containsKey(component)) continue;
            String a = oldComponents.get(component).path("repositoryIdentity").asText();
            String b = currentComponents.get(component).path("repositoryIdentity").asText();
            if (!a.isBlank() && !b.isBlank() && !a.equals(b)) throw new IOException("Endpoint removal component repositories differ");
        }
        Map<String, JsonNode> oldEndpoints = index(before.path("http").path("endpoints"));
        Map<String, JsonNode> newEndpoints = index(after.path("http").path("endpoints"));
        Map<String, JsonNode> oldCalls = index(before.path("http").path("calls"));
        Map<String, JsonNode> newCalls = index(after.path("http").path("calls"));
        Set<String> newComponents = index(after.path("components")).keySet();
        ObjectNode result = object().put("schemaVersion", 1).put("rule", "AR1")
                .put("analysis", "static-endpoint-removal-impact").put("status", "partial");
        result.putObject("limits").put("maxRecords", options.maxRecords).put("maxWitnesses", options.maxWitnesses);
        ArrayNode observations = result.putArray("observations"), findings = result.putArray("findings");
        ObjectNode coverage = result.putObject("coverage");
        long removed = oldEndpoints.keySet().stream().filter(id -> !newEndpoints.containsKey(id)).count();
        coverage.put("removedEndpointRegistrations", removed).put("oldCalls", oldCalls.size())
                .put("newCalls", newCalls.size()).put("sourceAssociation", "supplied-snapshots-not-verified-deployments");
        boolean complete = before.path("http").path("summary").path("matchingComplete").asBoolean(false)
                && after.path("http").path("summary").path("matchingComplete").asBoolean(false);
        coverage.put("matchingComplete", complete);
        ObjectNode counts = result.putObject("summary");
        if (!complete) {
            result.put("reason", "Incomplete HTTP matching cannot establish loss of an endpoint relationship");
            counts.putNull("danglingCallerCandidates").putNull("removalRelatedObservations")
                    .put("witnessesTruncated", false).put("comparisonComplete", false);
            return result;
        }
        int total = 0, dangling = 0, ambiguousBefore = 0, scopeOmitted = 0;
        SortedMap<String, Integer> classifications = new TreeMap<>();
        for (JsonNode oldCall : oldCalls.values()) {
            JsonNode candidates = oldCall.path("candidateEndpointIds");
            if (!"matched".equals(oldCall.path("status").asText()) || candidates.size() != 1) {
                if ("ambiguous".equals(oldCall.path("status").asText())) ambiguousBefore++;
                continue;
            }
            String targetId = candidates.get(0).asText();
            JsonNode endpoint = oldEndpoints.get(targetId);
            if (endpoint == null) throw new IOException("Unknown previous endpoint relationship");
            if (newEndpoints.containsKey(targetId)) continue;
            // Removing an input component is not evidence that its implementation was deleted.
            if (!newComponents.contains(endpoint.path("component").asText())
                    || !newComponents.contains(oldCall.path("component").asText())) { scopeOmitted++; continue; }
            JsonNode newCall = newCalls.get(oldCall.path("id").asText());
            String classification;
            if (newCall == null) classification = "caller-no-longer-observed";
            else if (!sameRequest(oldCall, newCall)) classification = "caller-request-changed";
            else if ("matched".equals(newCall.path("status").asText())) classification = "replacement-endpoint-observed";
            else if ("unmatched".equals(newCall.path("status").asText())) classification = "unchanged-caller-lost-endpoint-candidate";
            else classification = "remaining-caller-match-uncertain";
            total++; classifications.merge(classification, 1, Integer::sum);
            boolean candidate = classification.equals("unchanged-caller-lost-endpoint-candidate");
            if (candidate) dangling++;
            String identity = identity(targetId, oldCall.path("id").asText());
            if (observations.size() < options.maxWitnesses) {
                ObjectNode record = object().put("id", identity).put("classification", classification)
                        .put("removedEndpointId", targetId).put("callerId", oldCall.path("id").asText());
                record.set("beforeEndpoint", endpoint.deepCopy()); record.set("beforeCaller", oldCall.deepCopy());
                if (newCall == null) record.putNull("afterCaller"); else record.set("afterCaller", newCall.deepCopy());
                observations.add(record);
            }
            if (candidate && findings.size() < options.maxWitnesses) {
                ObjectNode finding = object().put("id", identity).put("kind", "AR1-endpoint-removal-caller-candidate")
                        .put("scope", "http-endpoint-removal").put("confidence", "static-candidate")
                        .put("reason", "The same observed request has no compatible endpoint after its previous unique endpoint registration disappeared");
                finding.putArray("subjects").add(targetId).add(oldCall.path("id").asText());
                ObjectNode evidence = finding.putObject("evidence");
                evidence.set("beforeEndpoint", endpoint.deepCopy()); evidence.set("afterCaller", newCall.deepCopy());
                findings.add(finding);
            }
        }
        coverage.put("ambiguousPreviousCallsExcluded", ambiguousBefore).put("componentScopeOmissionsExcluded", scopeOmitted);
        counts.put("danglingCallerCandidates", dangling).put("removalRelatedObservations", total)
                .put("emittedObservations", observations.size()).put("emittedFindings", findings.size())
                .put("witnessesTruncated", total > observations.size() || dangling > findings.size())
                .put("comparisonComplete", true);
        ObjectNode categories = counts.putObject("classifications"); classifications.forEach(categories::put);
        result.put("reason", "Temporal relationship candidates distinguish retained, changed, uncertain and missing caller evidence");
        result.putArray("limitations")
                .add("Only previously unique static method/path matches and stable caller identities support this comparison.")
                .add("An unobserved caller may have moved or become unextractable; an unobserved endpoint may have changed identity.")
                .add("A still-matched caller is classified as having replacement evidence, not as broken by registration identity changes.")
                .add("Unknown, ambiguous, case-only, changed-request and omitted-component observations do not become dangling-caller findings.")
                .add("This AR1 adaptation is source evidence, not proof of runtime failure, payload incompatibility or release pairing.");
        return result;
    }

    private Map<String, JsonNode> index(JsonNode records) throws IOException {
        if (!records.isArray() || records.size() > options.maxRecords) throw new IOException("Invalid endpoint removal records");
        Map<String, JsonNode> indexed = new TreeMap<>();
        for (JsonNode record : records) {
            String id = record.path("id").asText();
            if (!record.isObject() || id.isBlank() || id.length() > 4096 || indexed.putIfAbsent(id, record) != null)
                throw new IOException("Invalid endpoint removal identity");
        }
        return indexed;
    }
    private static boolean sameRequest(JsonNode a, JsonNode b) {
        return a.path("method").equals(b.path("method")) && a.path("normalizedPath").equals(b.path("normalizedPath"))
                && a.path("component").equals(b.path("component"));
    }
    private static String identity(String endpoint, String caller) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("AR1\0" + endpoint + "\0" + caller).getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ObjectNode object() { return JavaScriptExtractionService.JSON.createObjectNode(); }
}
