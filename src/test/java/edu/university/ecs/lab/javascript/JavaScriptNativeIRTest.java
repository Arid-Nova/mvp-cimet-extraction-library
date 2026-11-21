package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.models.enums.ClassType;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptNativeIRTest {
    @TempDir Path temporary;

    @Test void projectsOneDeclaredBackendAndFrontendOrphansWithRoundTripParentLinks() throws Exception {
        ObjectNode backend = snapshot("backend", "api"), frontend = snapshot("frontend", "web");
        backend.withArray("endpoints").add(endpoint("users", "/users/{param}"));
        frontend.withArray("calls").add(call("load", "/users/{param}"));
        MicroserviceSystem system = JavaScriptIRConverter.toIR(List.of(frontend, backend), "example");
        assertEquals(1, system.getMicroservices().size());
        assertEquals(1, system.getOrphans().size());
        assertInstanceOf(JSModule.class, system.getOrphans().iterator().next());
        assertEquals(ClassType.MODULE, ((JSModule) system.getOrphans().iterator().next()).getClassType());
        assertEquals("declared-backend-application", system.getMicroservices().iterator().next().getMetadata().getString("boundary"));
        Path file = temporary.resolve("ir.json");
        JsonReadWriteUtils.writeToJSON(file, system);
        assertTrue(Files.readString(file).contains("\"JSModule\""));
        MicroserviceSystem restored = JsonReadWriteUtils.readFromJSON(file, MicroserviceSystem.class);
        MicroserviceSystem.setParentReferencesRecursively(restored, null);
        JSModule client = (JSModule) restored.getOrphans().iterator().next();
        RestCall call = client.getRestCalls().getFirst();
        Endpoint endpoint = restored.getMicroservices().iterator().next().getEndpoints().iterator().next();
        assertSame(restored, client.getParent().orElseThrow());
        assertSame(client, call.getParent().orElseThrow().getParent().orElseThrow());
        assertNull(call.getParentMicroservice());
        assertEquals("/users/{?}", call.getUrl());
        assertEquals("/users/{?}", endpoint.getUrl());
        assertEquals("/users/{param}", call.getMetadata().getString("normalizedPath"));
        assertTrue(RestCall.matchEndpoint(call, endpoint));
        call.getMetadata().put("target", "external");
        assertFalse(RestCall.matchEndpoint(call, endpoint));
    }

    @Test void unknownUrlsRemainUnresolvedAndFrontendIsNeverAService() throws Exception {
        ObjectNode frontend = snapshot("frontend", "web");
        ObjectNode unresolved = call("dynamic", "/users");
        unresolved.putNull("path").putNull("normalizedPath").put("resolution", "unresolved").put("target", "unknown");
        frontend.withArray("calls").add(unresolved);
        MicroserviceSystem system = JavaScriptIRConverter.toIR(List.of(frontend), "example");
        assertTrue(system.getMicroservices().isEmpty());
        JSModule client = (JSModule) system.getOrphans().iterator().next();
        assertEquals(1, client.getMethodCalls().size());
        assertTrue(client.getRestCalls().isEmpty());
        assertFalse(client.getMethodCalls().getFirst().getMetadata().getBoolean("resolved"));
    }

    @Test void projectionIsRepeatableAndSourceBodiesAreNotCopied() throws Exception {
        ObjectNode backend = snapshot("backend", "api");
        ObjectNode endpoint = endpoint("users", "/users");
        endpoint.put("source", "sensitive source content must not be copied");
        backend.withArray("endpoints").add(endpoint);
        String a = JsonReadWriteUtils.setupObjectWriter().writeValueAsString(JavaScriptIRConverter.toIR(List.of(backend), "example"));
        String b = JsonReadWriteUtils.setupObjectWriter().writeValueAsString(JavaScriptIRConverter.toIR(List.of(backend), "example"));
        assertEquals(a, b);
        assertFalse(a.contains("sensitive source"));
    }
}
