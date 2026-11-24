package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptDeltaTest {
    private final JavaScriptDeltaService delta = new JavaScriptDeltaService();

    @Test void ignoresLineAndCommitChangesButFindsImplementationChanges() throws Exception {
        ObjectNode before = snapshot("backend", "api");
        before.withArray("endpoints").add(endpoint("users", "/users"));
        ObjectNode after = before.deepCopy();
        ((ObjectNode) after.path("endpoints").get(0)).put("line", 80).put("endLine", 80).put("handlerLine", 100);
        after.withObject("component").put("commit", "another-commit");
        assertEquals(1, delta.compare(before, after).path("endpoints").path("unchangedCount").asInt());
        ((ObjectNode) after.path("endpoints").get(0)).put("bodyFingerprint", "body-b");
        assertEquals(1, delta.compare(before, after).path("endpoints").path("implementationChanged").size());
        assertEquals(0, delta.compare(before, after).path("endpoints").path("contractChanged").size());
    }

    @Test void pairsOnlyUnambiguousHandlerContractChanges() throws Exception {
        ObjectNode before = snapshot("backend", "api"), after = snapshot("backend", "api");
        before.withArray("endpoints").add(endpoint("old", "/users"));
        after.withArray("endpoints").add(endpoint("new", "/people"));
        var result = delta.compare(before, after);
        assertEquals(1, result.path("endpoints").path("contractChanged").size());
        assertEquals(0, result.path("endpoints").path("added").size());
        assertEquals("unambiguous-handler", result.path("endpoints").path("contractChanged").get(0).path("identityMatch").asText());
        before.withArray("endpoints").add(endpoint("second-old", "/users/active"));
        after.withArray("endpoints").add(endpoint("second-new", "/people/active"));
        result = delta.compare(before, after);
        assertEquals(0, result.path("endpoints").path("contractChanged").size());
        assertEquals(2, result.path("endpoints").path("added").size());
        assertEquals(2, result.path("endpoints").path("removed").size());
    }

    @Test void distinguishesCallTargetChangesAndRejectsUnrelatedComponents() throws Exception {
        ObjectNode before = snapshot("frontend", "web");
        before.withArray("calls").add(call("call", "/users"));
        ObjectNode after = before.deepCopy();
        ((ObjectNode) after.path("calls").get(0)).put("target", "external");
        assertEquals(1, delta.compare(before, after).path("calls").path("contractChanged").size());
        after.withObject("component").put("name", "another-web");
        assertThrows(IOException.class, () -> delta.compare(before, after));
        after.withObject("component").put("name", "web").put("repositoryURL", "https://example.com/b");
        before.withObject("component").put("repositoryURL", "https://example.com/a");
        assertThrows(IOException.class, () -> delta.compare(before, after));
    }

    @Test void deterministicOrderingAndDuplicateIdsAreChecked() throws Exception {
        ObjectNode before = snapshot("backend", "api"), a = snapshot("backend", "api"), b = snapshot("backend", "api");
        a.withArray("endpoints").add(endpoint("b", "/b")).add(endpoint("a", "/a"));
        b.withArray("endpoints").add(endpoint("a", "/a")).add(endpoint("b", "/b"));
        assertEquals(delta.compare(before, a), delta.compare(before, b));
        b.withArray("endpoints").add(endpoint("a", "/duplicate"));
        assertThrows(IOException.class, () -> delta.compare(before, b));
    }
}
