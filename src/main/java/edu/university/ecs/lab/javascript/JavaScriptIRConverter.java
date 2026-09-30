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

    private static String language(String file) { return file.endsWith(".ts") || file.endsWith(".tsx") ? "TypeScript" : "JavaScript"; }

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
