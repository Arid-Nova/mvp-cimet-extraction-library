package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import edu.university.ecs.lab.common.models.ir.*;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Projects static observations into native CIMET nodes without inventing service boundaries. */
public final class JavaScriptIRConverter {
    private JavaScriptIRConverter() { }

    /** Projects one physical source module into an existing service without creating service boundaries. */
    public static JSModule projectModule(JsonNode snapshot, String file, Microservice service, Path gitPath) {
        JsonNode info = snapshot.path("component");
        JSModule module = new JSModule(service, gitPath, ClassRole.UNKNOWN, service.getName());
        module.setMethods(new LinkedHashSet<>());
        module.setMetadata(componentMetadata(info).put("language", language(file)).put("sourceFile", file)
                .put("sourcePath", gitPath.toString().replace('\\', '/')).put("extractor", "cimet-javascript")
                .put("componentName", service.getName()).put("repositoryURL", service.getRepositoryURL())
                .put("commit", service.getCommitID())
                .put("boundaryInferred", false));
        Set<String> endpointIds = new HashSet<>();
        for (JsonNode endpoint : snapshot.path("endpoints")) {
            if (!file.equals(endpoint.path("file").asText())) continue;
            endpointIds.add(endpoint.path("id").asText());
            String url = nullableText(endpoint, "normalizedPath");
            Method base = new Method(module, endpoint.path("id").asText(), location(endpoint));
            Method node = url == null ? base : new Endpoint(base, nativePath(url), httpMethod(endpoint));
            node.setMetadata(provenance(endpoint).put("observation", "endpoint").put("resolved", url != null)
                    .put("componentKind", "backend"));
            module.getMethods().add(node);
            if (url != null) module.setClassRole(ClassRole.CONTROLLER);
        }
        for (JsonNode call : snapshot.path("calls")) {
            if (!file.equals(call.path("file").asText())) continue;
            String caller = call.path("caller").asText("<module>");
            Method container = new Method(module, "client-call:" + call.path("id").asText(), location(call));
            container.setMetadata(new JSONObject().put("caller", caller).put("syntheticCallContainer", true));
            MethodCall base = new MethodCall(container, call.path("id").asText(), location(call));
            base.setCalledFrom(caller);
            base.setObjectType("JavaScript HTTP client");
            String url = nullableText(call, "normalizedPath");
            MethodCall node = url == null ? base : new RestCall(base, nativePath(url), httpMethod(call));
            node.setMetadata(provenance(call).put("observation", "http-call").put("resolved", url != null)
                    .put("componentKind", "backend"));
            container.getMethodCalls().add(node);
            module.getMethods().add(container);
        }
        // Keep architectural evidence in the native module, without claiming Java role parity.
        JSONObject architecture = new JSONObject().put("schemaVersion", 1);
        JsonNode evidence = snapshot.path("architecture");
        for (String group : List.of("modules", "dependencies", "functions", "calls")) {
            org.json.JSONArray selected = new org.json.JSONArray();
            for (JsonNode item : evidence.path(group)) {
                if (file.equals(item.path("file").asText())) selected.put(new JSONObject(item.toString()));
            }
            architecture.put(group, selected);
        }
        org.json.JSONArray bindings = new org.json.JSONArray();
        for (JsonNode binding : evidence.path("endpointHandlers")) {
            if (endpointIds.contains(binding.path("endpointId").asText())) bindings.put(new JSONObject(binding.toString()));
        }
        architecture.put("endpointHandlers", bindings);
        if (evidence.path("contracts").isObject()) {
            JSONObject contracts = new JSONObject().put("schemaVersion", 1);
            org.json.JSONArray operations = new org.json.JSONArray();
            for (JsonNode operation : evidence.path("contracts").path("operations")) {
                if (endpointIds.contains(operation.path("endpointId").asText())) operations.put(new JSONObject(operation.toString()));
            }
            architecture.put("contracts", contracts.put("operations", operations));
        }
        if (evidence.path("semanticEvidence").isObject()) {
            JSONObject semantics = new JSONObject().put("schemaVersion", 1);
            JsonNode semanticEvidence = evidence.path("semanticEvidence");
            for (String group : List.of("dataAccess", "entities", "proxies", "resilience", "healthEndpoints")) {
                org.json.JSONArray selected = new org.json.JSONArray();
                for (JsonNode item : semanticEvidence.path(group)) {
                    if (file.equals(item.path("file").asText()) || endpointIds.contains(item.path("endpointId").asText())) {
                        selected.put(new JSONObject(item.toString()));
                    }
                }
                semantics.put(group, selected);
            }
            // These flags apply to the complete repository analysis; retain their scope explicitly.
            semantics.put("inspectionScope", "repository");
            if (semanticEvidence.has("summary")) semantics.put("repositoryInspection", new JSONObject(semanticEvidence.path("summary").toString()));
            if (semanticEvidence.has("limitations")) semantics.put("limitations", new org.json.JSONArray(semanticEvidence.path("limitations").toString()));
            architecture.put("semanticEvidence", semantics);
        }
        if (evidence.has("limitations")) architecture.put("limitations", new org.json.JSONArray(evidence.path("limitations").toString()));
        module.getMetadata().put("architectureEvidence", architecture);
        return module;
    }

    public static MicroserviceSystem toIR(List<? extends JsonNode> snapshots, String systemName) throws IOException {
        if (snapshots == null || snapshots.isEmpty() || systemName == null || systemName.isBlank())
            throw new IllegalArgumentException("At least one snapshot and a system name are required");
        MicroserviceSystem system = new MicroserviceSystem(systemName, new LinkedHashSet<>(), new LinkedHashSet<>());
        system.setMetadata(new JSONObject().put("extractor", "cimet-javascript")
                .put("schemaVersion", 1).put("boundaryPolicy", "explicit-component-kind")
                .put("analysis", "static-source-observations")
                .put("frontendPolicy", "client modules are orphans, not microservices"));
        Set<String> components = new HashSet<>();
        List<JsonNode> ordered = new ArrayList<>(snapshots);
        ordered.sort(Comparator.comparing(s -> s.path("component").path("kind").asText()
                + ":" + s.path("component").path("name").asText()));
        for (JsonNode snapshot : ordered) {
            JavaScriptExtractionService.validateSnapshot(snapshot);
            JsonNode info = snapshot.path("component");
            String name = info.path("name").asText(), kind = info.path("kind").asText();
            if (!components.add(kind + ":" + name)) throw new IOException("Duplicate JavaScript component identity");
            String prefix = kind + "/" + name.replaceAll("[^a-zA-Z0-9._-]", "_") + "-" + digest(name).substring(0, 8);
            Microservice service = null;
            if ("backend".equals(kind)) {
                service = new Microservice(system, name, info.path("repositoryURL").asText(""),
                        info.path("commit").asText(""), Path.of(prefix));
                service.setMetadata(componentMetadata(info).put("boundary", "declared-backend-application")
                        .put("boundaryInferred", false).put("independentlyDeployed", JSONObject.NULL));
                // Linked sets make repeated serialization of a projection deterministic.
                service.setControllers(new LinkedHashSet<>());
                service.setServices(new LinkedHashSet<>());
                service.setRepositories(new LinkedHashSet<>());
                service.setEntities(new LinkedHashSet<>());
                service.setUnknowns(new LinkedHashSet<>());
                service.setFeignClients(new LinkedHashSet<>());
                service.setFiles(new LinkedHashSet<>());
            }
            Map<String, JSModule> modules = new TreeMap<>();
            for (JsonNode endpoint : sorted(snapshot.path("endpoints"))) {
                JSModule module = module(modules, endpoint, service == null ? system : service, prefix, info,
                        "backend".equals(kind) ? ClassRole.CONTROLLER : ClassRole.UNKNOWN);
                Method base = new Method(module, endpoint.path("id").asText(), location(endpoint));
                String url = nullableText(endpoint, "normalizedPath");
                Method node = url == null ? base : new Endpoint(base, nativePath(url), httpMethod(endpoint));
                node.setMetadata(provenance(endpoint).put("observation", "endpoint")
                        .put("resolved", url != null).put("componentKind", kind));
                module.getMethods().add(node);
            }
            for (JsonNode call : sorted(snapshot.path("calls"))) {
                JSModule module = module(modules, call, service == null ? system : service, prefix, info, ClassRole.UNKNOWN);
                String caller = call.path("caller").asText("<module>");
                Method container = new Method(module, "client-call:" + call.path("id").asText(), location(call));
                container.setMetadata(new JSONObject().put("caller", caller).put("syntheticCallContainer", true));
                MethodCall base = new MethodCall(container, call.path("id").asText(), location(call));
                base.setCalledFrom(caller);
                base.setObjectType("JavaScript HTTP client");
                String url = nullableText(call, "normalizedPath");
                // Unresolved URLs remain MethodCalls: emitting a concrete RestCall would claim a false target.
                MethodCall node = url == null ? base : new RestCall(base, nativePath(url), httpMethod(call));
                node.setMetadata(provenance(call).put("observation", "http-call")
                        .put("resolved", url != null).put("componentKind", kind));
                container.getMethodCalls().add(node);
                module.getMethods().add(container);
            }
            for (JSModule module : modules.values()) {
                if (service == null) system.getOrphans().add(module);
                else service.addAbstractClass(module);
            }
            if (service != null) system.getMicroservices().add(service);
        }
        return system;
    }

    private static List<JsonNode> sorted(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        list.sort(Comparator.comparing(n -> n.path("file").asText() + "\n" + n.path("id").asText()));
        return list;
    }

    private static JSModule module(Map<String, JSModule> modules, JsonNode observation, Node parent,
                                   String prefix, JsonNode info, ClassRole role) {
        String file = observation.path("file").asText().replace('\\', '/');
        JSModule module = modules.computeIfAbsent(file, key -> {
            JSModule created = new JSModule(parent, Path.of(prefix, key), role, prefix);
            created.setMethods(new LinkedHashSet<>());
            created.setMetadata(componentMetadata(info).put("language", language(file)).put("sourceFile", file));
            return created;
        });
        if (role == ClassRole.CONTROLLER) module.setClassRole(role);
        return module;
    }

    private static String language(String file) { return file.endsWith(".ts") || file.endsWith(".tsx") || file.endsWith(".mts") || file.endsWith(".cts") ? "TypeScript" : "JavaScript"; }

    private static JSONObject componentMetadata(JsonNode info) {
        return new JSONObject().put("componentName", info.path("name").asText())
                .put("componentKind", info.path("kind").asText())
                .put("repositoryURL", info.path("repositoryURL").asText(""))
                .put("commit", info.path("commit").asText(""));
    }

    private static JSONObject provenance(JsonNode item) {
        JSONObject metadata = new JSONObject().put("extractor", "cimet-javascript");
        // Explicit whitelist avoids copying arbitrary source, request bodies, or configuration secrets.
        for (String field : List.of("id", "file", "method", "path", "normalizedPath", "handler",
                "handlerFile", "handlerLine", "caller", "line", "endLine", "target", "resolution",
                "fingerprint", "bodyFingerprint", "contractFingerprint")) {
            JsonNode value = item.get(field);
            if (value != null && value.isValueNode()) {
                metadata.put(field, value.isNull() ? JSONObject.NULL : value.isNumber() ? value.numberValue()
                        : value.isBoolean() ? value.booleanValue() : value.asText());
            }
        }
        return metadata;
    }

    private static Location location(JsonNode item) {
        Location location = new Location();
        location.setStartLine(item.path("line").asInt(-1));
        location.setEndLine(item.path("endLine").asInt(location.getStartLine()));
        return location;
    }

    private static HttpMethod httpMethod(JsonNode item) {
        try { return HttpMethod.valueOf(item.path("method").asText().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return HttpMethod.NONE; }
    }

    private static String nullableText(JsonNode node, String key) {
        return node.path(key).isTextual() && !node.path(key).asText().isBlank() ? node.path(key).asText() : null;
    }

    private static String nativePath(String path) { return path.replace("{param}", "{?}"); }

    private static String digest(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
