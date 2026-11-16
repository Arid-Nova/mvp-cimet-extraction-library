package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only lookup tables for one analyzer snapshot. Construction visits each observation once;
 * projecting a source file then visits only that file's records, not every repository observation.
 *
 * <p>The index owns a defensive snapshot copy. JSON nodes exposed inside this package are internal
 * read-only views; public callers can obtain a defensive snapshot copy instead. A prepared parser
 * must discard the entire index when the repository/revision changes.
 */
public final class JavaScriptSnapshotIndex {
  private static final List<String> ARCHITECTURE_GROUPS =
      List.of("modules", "dependencies", "functions", "calls");
  private static final List<String> SEMANTIC_GROUPS =
      List.of("dataAccess", "entities", "proxies", "resilience", "healthEndpoints");

  private final ObjectNode snapshot;
  private final Map<String, List<JsonNode>> endpoints;
  private final Map<String, List<JsonNode>> calls;
  private final Map<String, String> exclusions;
  private final Map<String, Map<String, List<JsonNode>>> architecture;
  private final Map<String, List<JsonNode>> handlers;
  private final Map<String, List<JsonNode>> contracts;
  private final Map<String, Map<String, List<JsonNode>>> semantics;

  private JavaScriptSnapshotIndex(ObjectNode source) {
    snapshot = source.deepCopy();
    endpoints = byFile(snapshot.path("endpoints"));
    calls = byFile(snapshot.path("calls"));

    Map<String, String> failures = new HashMap<>();
    for (JsonNode diagnostic : snapshot.path("diagnostics")) {
      String code = diagnostic.path("code").asText();
      if (code.startsWith("PARSE_") || "FILE_TOO_LARGE".equals(code)) {
        failures.putIfAbsent(diagnostic.path("file").asText(), code);
      }
    }
    exclusions = Map.copyOf(failures);

    JsonNode evidence = snapshot.path("architecture");
    Map<String, Map<String, List<JsonNode>>> groups = new HashMap<>();
    for (String group : ARCHITECTURE_GROUPS) {
      groups.put(group, byFile(evidence.path(group)));
    }
    architecture = Map.copyOf(groups);

    Map<String, String> endpointFiles = new HashMap<>();
    endpoints.forEach(
        (file, values) ->
            values.forEach(endpoint -> endpointFiles.put(endpoint.path("id").asText(), file)));
    handlers = byEndpointFile(evidence.path("endpointHandlers"), endpointFiles);
    contracts = byEndpointFile(evidence.path("contracts").path("operations"), endpointFiles);

    Map<String, Map<String, List<JsonNode>>> semanticGroups = new HashMap<>();
    for (String group : SEMANTIC_GROUPS) {
      Map<String, List<JsonNode>> files = new HashMap<>();
      for (JsonNode record : evidence.path("semanticEvidence").path(group)) {
        // The legacy projection selects a fact by physical file OR endpoint ownership. Preserve
        // source order and include it only once when both selectors refer to the same file.
        Set<String> owners = new LinkedHashSet<>();
        String physicalFile = record.path("file").asText();
        if (!physicalFile.isEmpty()) owners.add(physicalFile);
        String endpointFile = endpointFiles.get(record.path("endpointId").asText());
        if (endpointFile != null) owners.add(endpointFile);
        owners.forEach(
            file -> files.computeIfAbsent(file, ignored -> new ArrayList<>()).add(record));
      }
      semanticGroups.put(group, freeze(files));
    }
    semantics = Map.copyOf(semanticGroups);
  }

  public static JavaScriptSnapshotIndex create(JsonNode snapshot) {
    if (!(snapshot instanceof ObjectNode object)) {
      throw new IllegalArgumentException("A JavaScript snapshot object is required");
    }
    return new JavaScriptSnapshotIndex(object);
  }

  public ObjectNode snapshotCopy() {
    return snapshot.deepCopy();
  }

  /** Module inventory remains authoritative for declaration/test/symlink exclusions. */
  public boolean includesModule(String file) {
    return architecture.get("modules").containsKey(file);
  }

  /** Returns the first source-exclusion code, preserving analyzer diagnostic ordering. */
  public String exclusionCode(String file) {
    return exclusions.get(file);
  }

  JsonNode component() {
    return snapshot.path("component");
  }

  JsonNode architecture() {
    return snapshot.path("architecture");
  }

  List<JsonNode> endpoints(String file) {
    return endpoints.getOrDefault(file, List.of());
  }

  List<JsonNode> calls(String file) {
    return calls.getOrDefault(file, List.of());
  }

  List<JsonNode> architecture(String group, String file) {
    return architecture.getOrDefault(group, Map.of()).getOrDefault(file, List.of());
  }

  List<JsonNode> handlers(String file) {
    return handlers.getOrDefault(file, List.of());
  }

  List<JsonNode> contracts(String file) {
    return contracts.getOrDefault(file, List.of());
  }

  List<JsonNode> semantics(String group, String file) {
    return semantics.getOrDefault(group, Map.of()).getOrDefault(file, List.of());
  }

  private static Map<String, List<JsonNode>> byFile(JsonNode records) {
    Map<String, List<JsonNode>> files = new HashMap<>();
    for (JsonNode record : records) {
      files.computeIfAbsent(record.path("file").asText(), ignored -> new ArrayList<>()).add(record);
    }
    return freeze(files);
  }

  private static Map<String, List<JsonNode>> byEndpointFile(
      JsonNode records, Map<String, String> endpointFiles) {
    Map<String, List<JsonNode>> files = new HashMap<>();
    for (JsonNode record : records) {
      String file = endpointFiles.get(record.path("endpointId").asText());
      if (file != null) files.computeIfAbsent(file, ignored -> new ArrayList<>()).add(record);
    }
    return freeze(files);
  }

  private static Map<String, List<JsonNode>> freeze(Map<String, List<JsonNode>> files) {
    Map<String, List<JsonNode>> result = new HashMap<>();
    files.forEach((file, records) -> result.put(file, List.copyOf(records)));
    return Map.copyOf(result);
  }
}
