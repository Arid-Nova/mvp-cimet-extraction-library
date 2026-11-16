package edu.university.ecs.lab.delta.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.ir.ConfigFile;
import edu.university.ecs.lab.common.models.ir.JSModule;
import edu.university.ecs.lab.common.models.ir.Method;
import edu.university.ecs.lab.common.models.ir.MethodCall;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SnapshotDeltaOrderingTest {
  @Test
  void legacyBareBindingsAreRejectedEvenWhenCanonicalBytesCoincide() throws Exception {
    MicroserviceSystem base =
        new MicroserviceSystem("legacy-binding", new LinkedHashSet<>(), new LinkedHashSet<>());
    // Already-sorted evidence produces the same bytes under the legacy and current algorithms.
    base.setMetadata(new JSONObject().put("steps", new JSONArray().put("first").put("second")));
    JsonNode serialized =
        JsonReadWriteUtils.setupObjectReader()
            .readTree(JsonReadWriteUtils.setupObjectWriter().writeValueAsString(base));
    String legacyBareHash =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(
                        legacyCanonical(serialized).toString().getBytes(StandardCharsets.UTF_8)));
    assertEquals("cimet-snapshot-v2:" + legacyBareHash, SnapshotDeltaService.fingerprint(base));

    var delta = SnapshotDeltaService.compare(base, SnapshotDeltaService.copy(base));
    assertTrue(delta.getBaseFingerprint().startsWith("cimet-snapshot-v2:"));
    delta.setBaseFingerprint(legacyBareHash);
    assertThrows(
        IllegalArgumentException.class,
        () -> MergeService.create(SnapshotDeltaService.copy(base), delta));
    assertEquals(
        SnapshotDeltaService.fingerprint(base),
        SnapshotDeltaService.fingerprint(
            MergeService.create(
                SnapshotDeltaService.copy(base), SnapshotDeltaService.compare(base, base))));
  }

  @Test
  void nativeSetOrderDoesNotChangeTheBaseBinding() throws Exception {
    MicroserviceSystem before = snapshot();
    MicroserviceSystem reordered = SnapshotDeltaService.copy(before);
    JSModule module = module(reordered);
    var methods = new ArrayList<>(module.getMethods());
    Collections.reverse(methods);
    module.setMethods(new LinkedHashSet<>(methods));
    assertEquals(
        SnapshotDeltaService.fingerprint(before), SnapshotDeltaService.fingerprint(reordered));

    MicroserviceSystem target = SnapshotDeltaService.copy(before);
    target.setMetadata(new JSONObject().put("revision", "target"));
    assertEquals(
        SnapshotDeltaService.fingerprint(target),
        SnapshotDeltaService.fingerprint(
            MergeService.create(reordered, SnapshotDeltaService.compare(before, target))));
  }

  @Test
  void methodCallListsAndRawArgumentOrderRemainSignificant() {
    MicroserviceSystem before = snapshot();
    MicroserviceSystem reordered = SnapshotDeltaService.copy(before);
    Collections.reverse(run(reordered).getMethodCalls());
    assertNotEquals(
        SnapshotDeltaService.fingerprint(before), SnapshotDeltaService.fingerprint(reordered));
    assertFalse(SnapshotDeltaService.compare(before, reordered).getChanges().isEmpty());

    MicroserviceSystem target = SnapshotDeltaService.copy(before);
    target.setMetadata(new JSONObject().put("revision", "target"));
    var delta = SnapshotDeltaService.compare(before, target);
    assertThrows(IllegalArgumentException.class, () -> MergeService.create(reordered, delta));

    MicroserviceSystem swappedArguments = SnapshotDeltaService.copy(before);
    run(swappedArguments).getMethodCalls().getFirst().setParameterContents("second,first");
    assertNotEquals(
        SnapshotDeltaService.fingerprint(before),
        SnapshotDeltaService.fingerprint(swappedArguments));
    assertThrows(
        IllegalArgumentException.class, () -> MergeService.create(swappedArguments, delta));
  }

  @Test
  void metadataArraysRetainOrderEvenWhenTheirNamesMimicNativeSets() {
    MicroserviceSystem before = snapshot();
    before.setMetadata(
        new JSONObject()
            .put("type", "Method")
            .put("parameters", new JSONArray().put("authenticate").put("authorize")));
    MicroserviceSystem reordered = SnapshotDeltaService.copy(before);
    reordered.setMetadata(
        new JSONObject()
            .put("type", "Method")
            .put("parameters", new JSONArray().put("authorize").put("authenticate")));
    assertNotEquals(
        SnapshotDeltaService.fingerprint(before), SnapshotDeltaService.fingerprint(reordered));
    assertThrows(
        IllegalArgumentException.class,
        () -> MergeService.create(reordered, SnapshotDeltaService.compare(before, before)));
  }

  @Test
  void configurationPayloadArraysRetainOrder() throws Exception {
    MicroserviceSystem before = snapshot();
    Microservice service = before.getMicroservices().iterator().next();
    service
        .getFiles()
        .add(
            new ConfigFile(
                service,
                Path.of("/config.json"),
                new ObjectMapper()
                    .readTree("{\"type\":\"Method\",\"parameters\":[\"first\",\"second\"]}")));
    MicroserviceSystem reordered = SnapshotDeltaService.copy(before);
    ConfigFile config = reordered.getMicroservices().iterator().next().getFiles().iterator().next();
    config.setData(
        new ObjectMapper().readTree("{\"type\":\"Method\",\"parameters\":[\"second\",\"first\"]}"));
    assertNotEquals(
        SnapshotDeltaService.fingerprint(before), SnapshotDeltaService.fingerprint(reordered));
    assertFalse(SnapshotDeltaService.compare(before, reordered).getChanges().isEmpty());
  }

  private static MicroserviceSystem snapshot() {
    MicroserviceSystem system =
        new MicroserviceSystem("ordering", new LinkedHashSet<>(), new LinkedHashSet<>());
    Microservice service =
        new Microservice(
            system, "api", "https://github.com/fixture/api.git", "a".repeat(40), Path.of("/api"));
    system.getMicroservices().add(service);
    JSModule module = new JSModule(service, Path.of("/worker.js"), ClassRole.UNKNOWN, "api");
    Method run = new Method(module, "run", null);
    MethodCall first = new MethodCall(run, "first", null);
    first.setParameterContents("first,second");
    run.getMethodCalls().add(first);
    run.getMethodCalls().add(new MethodCall(run, "second", null));
    module.setMethods(new LinkedHashSet<>());
    module.getMethods().add(run);
    module.getMethods().add(new Method(module, "helper", null));
    service.addAbstractClass(module);
    return system;
  }

  /** The old binding algorithm is retained only as a test oracle for migration rejection. */
  private static JsonNode legacyCanonical(JsonNode node) {
    ObjectMapper mapper = new ObjectMapper();
    if (node.isObject()) {
      var fields = new TreeMap<String, JsonNode>();
      node.fields()
          .forEachRemaining(field -> fields.put(field.getKey(), legacyCanonical(field.getValue())));
      return mapper.valueToTree(fields);
    }
    if (node.isArray()) {
      var values = new ArrayList<JsonNode>();
      node.forEach(item -> values.add(legacyCanonical(item)));
      values.sort(Comparator.comparing(JsonNode::toString));
      return mapper.valueToTree(values);
    }
    return node;
  }

  private static JSModule module(MicroserviceSystem system) {
    return (JSModule) system.getMicroservices().iterator().next().getUnknowns().iterator().next();
  }

  private static Method run(MicroserviceSystem system) {
    return module(system).getMethods().stream()
        .filter(method -> method.getName().equals("run"))
        .findFirst()
        .orElseThrow();
  }
}
