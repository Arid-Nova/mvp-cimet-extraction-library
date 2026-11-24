package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.node.ObjectNode;

final class JavaScriptTestSnapshots {
    static ObjectNode snapshot(String kind, String name) {
        ObjectNode snapshot = JavaScriptExtractionService.JSON.createObjectNode();
        snapshot.put("schemaVersion", 1);
        snapshot.putObject("component").put("name", name).put("kind", kind);
        snapshot.put("filesAnalyzed", 1);
        snapshot.putArray("endpoints");
        snapshot.putArray("calls");
        snapshot.putArray("diagnostics");
        return snapshot;
    }

    static ObjectNode endpoint(String id, String path) {
        ObjectNode item = JavaScriptExtractionService.JSON.createObjectNode();
        item.put("id", id).put("file", "routes/users.js").put("line", 4).put("endLine", 4)
                .put("method", "GET").put("path", path).put("normalizedPath", path)
                .put("handler", "controller.users").put("handlerFile", "controllers/users.js").put("handlerLine", 3)
                .put("bodyFingerprint", "body-a").put("contractFingerprint", "GET:" + path).put("fingerprint", "combined-a");
        return item;
    }

    static ObjectNode call(String id, String path) {
        ObjectNode item = endpoint(id, path);
        item.remove("handler"); item.remove("handlerFile"); item.remove("handlerLine");
        item.put("file", "src/api.js").put("caller", "loadUsers").put("target", "backend").put("resolution", "literal");
        return item;
    }
}
