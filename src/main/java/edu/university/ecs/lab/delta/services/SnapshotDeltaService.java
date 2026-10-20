package edu.university.ecs.lab.delta.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.*;
import edu.university.ecs.lab.delta.models.enums.ChangeType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Compares complete, source-supported snapshots regardless of the language that produced them.
 * A rename is represented as a deletion and an addition, matching the existing change-type schema.
 * The optional target snapshot retains boundaries and all classes in a physical file for exact merges.
 * This is structural comparison, not a claim of behavioral equivalence or runtime impact.
 */
public final class SnapshotDeltaService {
    private SnapshotDeltaService() {}

    private record Entry(String repository, ProjectFile file) {}

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
                delta.getChanges().add(new SimpleDelta(current.file().getPath(), ChangeType.ADD,
                        current.repository(), current.file()));
            } else if (current == null) {
                delta.getChanges().add(new SimpleDelta(old.file().getPath(), ChangeType.DELETE,
                        old.repository(), null));
            } else if (!canonical(old.file()).equals(canonical(current.file()))) {
                List<ComponentDelta> components = componentChanges(old.file(), current.file());
                delta.getChanges().add(new ModifyDelta(current.file().getPath(), components, current.repository(),
                        new SimpleDelta(current.file().getPath(), ChangeType.MODIFY, current.repository(), current.file())));
            }
        }
        return delta;
    }

    private static void requireCompleteSource(MicroserviceSystem system) {
        if (system.getMetadata() != null && "partial".equals(system.getMetadata().optString("status")))
            throw new IllegalArgumentException("A partial source extraction cannot establish authoritative additions or deletions; resolve parser diagnostics first");
        for (Microservice service : system.getMicroservices()) {
            if (service.getMetadata() != null && "partial".equals(service.getMetadata().optString("status")))
                throw new IllegalArgumentException("A source contains parser failures; snapshot delta would confuse missing evidence with deleted code");
        }
    }

    private static void recordCommits(MicroserviceSystem system, Map<String, String> commits) {
        for (Microservice service : system.getMicroservices()) {
            String previous = commits.put(service.getRepositoryURL(), service.getCommitID());
            if (previous != null && !Objects.equals(previous, service.getCommitID()))
                throw new IllegalArgumentException("A repository cannot have multiple revisions in one snapshot");
        }
    }

    private static Map<String, Entry> files(MicroserviceSystem system) {
        Map<String, Entry> result = new TreeMap<>();
        for (Microservice service : system.getMicroservices()) {
            for (ProjectFile file : service.getAllFiles()) put(result, service.getRepositoryURL(), file);
        }
        if (system.getOrphans() != null) {
            for (ProjectFile file : system.getOrphans()) {
                String repo = file.getMetadata() == null ? "" : file.getMetadata().optString("repositoryURL", "");
                put(result, repo, file);
            }
        }
        return result;
    }

    private static void put(Map<String, Entry> result, String repository, ProjectFile file) {
        String key = repository + "\u0000" + FileUtils.normalizePathString(file.getPath().toString());
        Entry duplicate = result.putIfAbsent(key, new Entry(repository, file));
        if (duplicate != null && !canonical(duplicate.file()).equals(canonical(file)))
            throw new IllegalArgumentException("Conflicting source identities in snapshot: " + file.getPath());
    }

    private static List<ComponentDelta> componentChanges(ProjectFile before, ProjectFile after) {
        Map<String, Component> old = components(before), current = components(after);
        Set<String> ids = new TreeSet<>(old.keySet()); ids.addAll(current.keySet());
        List<ComponentDelta> changes = new ArrayList<>();
        for (String id : ids) {
            Component left = old.get(id), right = current.get(id);
            if (left == null) changes.add(new ComponentDelta(ChangeType.ADD, right));
            else if (right == null) changes.add(new ComponentDelta(ChangeType.DELETE, left));
            else if (!canonical(left).equals(canonical(right))) changes.add(new ComponentDelta(ChangeType.MODIFY, right));
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

    /** Stable base binding: IR collection order is irrelevant; source evidence remains significant. */
    public static String fingerprint(MicroserviceSystem system) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical(system).toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonNode canonical(Object value) {
        try {
            return sort(JsonReadWriteUtils.setupObjectReader().readTree(JsonReadWriteUtils.setupObjectWriter().writeValueAsString(value)));
        } catch (IOException failure) { throw new IllegalArgumentException("IR could not be serialized", failure); }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = (ObjectNode) node.deepCopy(); result.removeAll();
            List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            for (String key : keys) result.set(key, sort(node.get(key)));
            return result;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>(); node.forEach(item -> items.add(sort(item)));
            items.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode result = (ArrayNode) node.deepCopy(); result.removeAll(); items.forEach(result::add);
            return result;
        }
        return node;
    }

    public static MicroserviceSystem copy(MicroserviceSystem system) {
        try {
            MicroserviceSystem copy = JsonReadWriteUtils.readFromJSONString(
                    JsonReadWriteUtils.setupObjectWriter().writeValueAsString(system), MicroserviceSystem.class);
            MicroserviceSystem.setParentReferencesRecursively(copy, null);
            return copy;
        } catch (IOException failure) { throw new IllegalArgumentException("IR snapshot could not be copied", failure); }
    }
}
