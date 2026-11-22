package edu.university.ecs.lab.delta.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Component;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.models.ir.ProjectFile;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.ComponentDelta;
import edu.university.ecs.lab.delta.models.ModifyDelta;
import edu.university.ecs.lab.delta.models.SimpleDelta;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares complete, source-supported snapshots regardless of the language that produced them. A
 * rename is represented as a deletion and an addition, matching the existing change-type schema.
 * The optional target snapshot retains boundaries and all classes in a physical file for exact
 * merges. This is structural comparison, not a claim of behavioral equivalence or runtime impact.
 */
public final class SnapshotDeltaService {
  private static final String FINGERPRINT_DOMAIN = "cimet-snapshot-v2:";

  private SnapshotDeltaService() {}

  private record Entry(String repository, ProjectFile file) {}

  /**
   * Computes compatible file/component change records and binds a complete target to the base.
   * Partial input cannot establish deletion: a parser failure is missing evidence, not removed
   * code.
   */
  public static SystemChange compare(MicroserviceSystem before, MicroserviceSystem after) {
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(after, "after");
    if (!Objects.equals(before.getName(), after.getName()))
      throw new IllegalArgumentException("Snapshot comparison requires matching system names");
    requireCompleteSource(before);
    requireCompleteSource(after);
    SystemChange delta = new SystemChange();
    delta.setBaseFingerprint(fingerprint(before));
    delta.setTargetSnapshot(copy(after));
    recordCommits(before, delta.getOldCommits());
    recordCommits(after, delta.getNewCommits());
    Map<String, Entry> oldFiles = files(before), newFiles = files(after);
    Set<String> identities = new TreeSet<>(oldFiles.keySet());
    identities.addAll(newFiles.keySet());
    for (String identity : identities) {
      Entry old = oldFiles.get(identity), current = newFiles.get(identity);
      if (old == null) {
        delta
            .getChanges()
            .add(
                new SimpleDelta(
                    current.file().getPath(),
                    ChangeType.ADD,
                    current.repository(),
                    current.file()));
      } else if (current == null) {
        delta
            .getChanges()
            .add(new SimpleDelta(old.file().getPath(), ChangeType.DELETE, old.repository(), null));
      } else if (!canonical(old.file()).equals(canonical(current.file()))) {
        List<ComponentDelta> components = componentChanges(old.file(), current.file());
        delta
            .getChanges()
            .add(
                new ModifyDelta(
                    current.file().getPath(),
                    components,
                    current.repository(),
                    new SimpleDelta(
                        current.file().getPath(),
                        ChangeType.MODIFY,
                        current.repository(),
                        current.file())));
      }
    }
    return delta;
  }

  private static void requireCompleteSource(MicroserviceSystem system) {
    if (system.getMetadata() != null && "partial".equals(system.getMetadata().optString("status")))
      throw new IllegalArgumentException(
          "A partial source extraction cannot establish authoritative additions or deletions;"
              + " resolve parser diagnostics first");
    for (Microservice service : system.getMicroservices()) {
      if (service.getMetadata() != null
          && "partial".equals(service.getMetadata().optString("status")))
        throw new IllegalArgumentException(
            "A source contains parser failures; snapshot delta would confuse missing evidence with"
                + " deleted code");
    }
  }

  private static void recordCommits(MicroserviceSystem system, Map<String, String> commits) {
    for (Microservice service : system.getMicroservices()) {
      String previous = commits.put(service.getRepositoryURL(), service.getCommitID());
      if (previous != null && !Objects.equals(previous, service.getCommitID()))
        throw new IllegalArgumentException(
            "A repository cannot have multiple revisions in one snapshot");
    }
  }

  /**
   * Repository plus portable source identity disambiguates equal paths in different repositories.
   */
  private static Map<String, Entry> files(MicroserviceSystem system) {
    Map<String, Entry> result = new TreeMap<>();
    for (Microservice service : system.getMicroservices()) {
      for (ProjectFile file : service.getAllFiles()) put(result, service.getRepositoryURL(), file);
    }
    if (system.getOrphans() != null) {
      for (ProjectFile file : system.getOrphans()) {
        String repo =
            file.getMetadata() == null ? "" : file.getMetadata().optString("repositoryURL", "");
        put(result, repo, file);
      }
    }
    return result;
  }

  /**
   * Identical duplicate observations collapse; conflicting observations fail instead of
   * overwriting.
   */
  private static void put(Map<String, Entry> result, String repository, ProjectFile file) {
    String key = repository + "\u0000" + FileUtils.normalizePathString(file.getPath().toString());
    Entry duplicate = result.putIfAbsent(key, new Entry(repository, file));
    if (duplicate != null && !canonical(duplicate.file()).equals(canonical(file)))
      throw new IllegalArgumentException(
          "Conflicting source identities in snapshot: " + file.getPath());
  }

  /** Retains the historical component-delta view for consumers that inspect changes directly. */
  private static List<ComponentDelta> componentChanges(ProjectFile before, ProjectFile after) {
    Map<String, Component> old = components(before), current = components(after);
    Set<String> ids = new TreeSet<>(old.keySet());
    ids.addAll(current.keySet());
    List<ComponentDelta> changes = new ArrayList<>();
    for (String id : ids) {
      Component left = old.get(id), right = current.get(id);
      if (left == null) changes.add(new ComponentDelta(ChangeType.ADD, right));
      else if (right == null) changes.add(new ComponentDelta(ChangeType.DELETE, left));
      else if (!canonical(left).equals(canonical(right)))
        changes.add(new ComponentDelta(ChangeType.MODIFY, right));
    }
    return changes;
  }

  private static Map<String, Component> components(ProjectFile file) {
    Map<String, Component> result = new TreeMap<>();
    if (file instanceof AbstractClass ac) {
      for (Component component : ac.getDescendants()) result.put(component.getID(), component);
    }
    return result;
  }

  /**
   * Versioned base binding: native Set iteration is irrelevant, but ordered source evidence remains
   * significant. The domain prefix prevents a bare legacy hash from being accepted even when its
   * order-insensitive canonical bytes happen to match the new bytes. Old snapshot deltas must be
   * regenerated; old base IRs can still be compared to create a current delta.
   */
  public static String fingerprint(MicroserviceSystem system) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(canonical(system).toString().getBytes(StandardCharsets.UTF_8));
      return FINGERPRINT_DOMAIN + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * Canonicalizes object keys and only the current native IR's explicitly Set-valued properties.
   * Method-call lists, configuration data, metadata evidence and unknown arrays retain their order.
   */
  private static JsonNode canonical(Object value) {
    try {
      return sort(
          JsonReadWriteUtils.setupObjectReader()
              .readTree(JsonReadWriteUtils.setupObjectWriter().writeValueAsString(value)),
          true,
          false);
    } catch (IOException failure) {
      throw new IllegalArgumentException("IR could not be serialized", failure);
    }
  }

  private static final Set<String> CLASS_SETS =
      Set.of("imports", "annotations", "fields", "methods");

  private static JsonNode sort(JsonNode node, boolean nativeContext, boolean unorderedArray) {
    if (node.isObject()) {
      ObjectNode result = (ObjectNode) node.deepCopy();
      result.removeAll();
      List<String> keys = new ArrayList<>();
      node.fieldNames().forEachRemaining(keys::add);
      Collections.sort(keys);
      String type = node.path("type").asText();
      for (String key : keys) {
        // Open payloads can reuse IR-like field/type names; never apply native Set semantics
        // inside arbitrary configuration JSON or producer-specific metadata.
        boolean childNative = nativeContext && !key.equals("metadata") && !key.equals("data");
        result.set(
            key, sort(node.get(key), childNative, childNative && nativeSetProperty(type, key)));
      }
      return result;
    }
    if (node.isArray()) {
      List<JsonNode> items = new ArrayList<>();
      node.forEach(item -> items.add(sort(item, nativeContext, false)));
      if (unorderedArray) items.sort(Comparator.comparing(JsonNode::toString));
      ArrayNode result = (ArrayNode) node.deepCopy();
      result.removeAll();
      items.forEach(result::add);
      return result;
    }
    return node;
  }

  /**
   * Mirrors declared Set fields, rather than guessing collection semantics from arbitrary names.
   */
  private static boolean nativeSetProperty(String type, String field) {
    return switch (type) {
      case "MicroserviceSystem" -> Set.of("microservices", "orphans").contains(field);
      case "Microservice" ->
          Set.of(
                  "controllers",
                  "services",
                  "repositories",
                  "entities",
                  "unknowns",
                  "feignClients",
                  "files")
              .contains(field);
      case "JClass", "JRecord" -> CLASS_SETS.contains(field) || field.equals("implementedTypes");
      case "JEnum" ->
          CLASS_SETS.contains(field) || Set.of("implementedTypes", "enumTypes").contains(field);
      case "JInterface" -> CLASS_SETS.contains(field) || field.equals("extendedTypes");
      case "JSModule" -> CLASS_SETS.contains(field);
      case "Method", "Endpoint" ->
          Set.of("parameters", "annotations", "thrownExceptions").contains(field);
      case "Parameter" -> field.equals("annotations");
      default -> false;
    };
  }

  /**
   * Copies source evidence and restores parent links without sharing mutable IR nodes with the
   * base.
   */
  public static MicroserviceSystem copy(MicroserviceSystem system) {
    try {
      MicroserviceSystem copy =
          JsonReadWriteUtils.readFromJSONString(
              JsonReadWriteUtils.setupObjectWriter().writeValueAsString(system),
              MicroserviceSystem.class);
      MicroserviceSystem.setParentReferencesRecursively(copy, null);
      return copy;
    } catch (IOException failure) {
      throw new IllegalArgumentException("IR snapshot could not be copied", failure);
    }
  }
}
