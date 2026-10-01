package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaScriptEndpointRemovalTest {
    private final JavaScriptArchitectureService architecture = new JavaScriptArchitectureService();
    private final JavaScriptEndpointRemovalService removals = new JavaScriptEndpointRemovalService();
    private ObjectNode report(String endpointId, String path) throws Exception {
        var backend = snapshot("backend", "api");
        if (endpointId != null) backend.withArray("endpoints").add(endpoint(endpointId, path));
        var frontend = snapshot("frontend", "ui"); frontend.withArray("calls").add(call("client", "/users"));
        return architecture.analyze(List.of(backend, frontend), "system");
    }
    @Test void unchangedCallerLosingItsUniqueEndpointProducesTemporalEvidence() throws Exception {
        var before = report("users", "/users"); var after = report(null, null);
        var result = removals.compare(before, after);
        assertEquals(1, result.path("summary").path("danglingCallerCandidates").asInt());
        assertEquals("AR1-endpoint-removal-caller-candidate", result.path("findings").get(0).path("kind").asText());
        assertEquals("/users", result.path("findings").get(0).path("evidence").path("afterCaller").path("normalizedPath").asText());
    }
    @Test void replacementRegistrationOrMovedHandlerIsNotAnEndpointLoss() throws Exception {
        var result = removals.compare(report("old", "/users"), report("replacement", "/users"));
        assertEquals(0, result.path("findings").size());
        assertEquals("replacement-endpoint-observed", result.path("observations").get(0).path("classification").asText());
    }
    @Test void changedOrAbsentCallerCannotBeAttributedToEndpointRemoval() throws Exception {
        var before = report("users", "/users"); var after = report(null, null);
        ((ObjectNode) after.path("http").path("calls").get(0)).put("normalizedPath", "/other");
        var changed = removals.compare(before, after);
        assertEquals(0, changed.path("findings").size());
        assertEquals("caller-request-changed", changed.path("observations").get(0).path("classification").asText());
        after.withObject("http").withArray("calls").removeAll();
        assertEquals("caller-no-longer-observed", removals.compare(before, after).path("observations").get(0).path("classification").asText());
    }
    @Test void ambiguousCaseOnlyAndUnknownAfterStatesDoNotBecomeDefects() throws Exception {
        for (String status : List.of("ambiguous", "case-only-candidate", "unknown")) {
            var backend = snapshot("backend", "api"); var frontend = snapshot("frontend", "ui");
            var caller = call("client", "/users"); frontend.withArray("calls").add(caller);
            if (status.equals("ambiguous")) {
                backend.withArray("endpoints").add(endpoint("replacement-one", "/users"));
                backend.withArray("endpoints").add(endpoint("replacement-two", "/users"));
            } else if (status.equals("case-only-candidate")) backend.withArray("endpoints").add(endpoint("different-case", "/Users"));
            else caller.put("target", "unknown");
            var after = architecture.analyze(List.of(backend, frontend), "system");
            assertEquals(status, after.path("http").path("calls").get(0).path("status").asText());
            var result = removals.compare(report("users", "/users"), after);
            assertEquals(0, result.path("findings").size());
            assertEquals("remaining-caller-match-uncertain", result.path("observations").get(0).path("classification").asText());
        }
    }
    @Test void incompleteSearchAndRemovedInputScopeCannotProveRemovalImpact() throws Exception {
        var before = report("users", "/users"); var after = report(null, null);
        after.withObject("http").withObject("summary").put("matchingComplete", false);
        assertTrue(removals.compare(before, after).path("summary").path("danglingCallerCandidates").isNull());
        after.withObject("http").withObject("summary").put("matchingComplete", true);
        after.withArray("components").remove(0);
        assertEquals(0, removals.compare(before, after).path("findings").size());
    }
    @Test void witnessCapPreservesTotalAndIdentityIgnoresLineChanges() throws Exception {
        var before = report("users", "/users"); var after = report(null, null);
        var oldCopy = ((ObjectNode) before.path("http").path("calls").get(0)).deepCopy().put("id", "second");
        var newCopy = ((ObjectNode) after.path("http").path("calls").get(0)).deepCopy().put("id", "second");
        before.withObject("http").withArray("calls").add(oldCopy); after.withObject("http").withArray("calls").add(newCopy);
        var limited = new JavaScriptEndpointRemovalService(new JavaScriptEndpointRemovalService.Options(100, 1));
        var result = limited.compare(before, after);
        assertEquals(2, result.path("summary").path("danglingCallerCandidates").asInt());
        assertEquals(1, result.path("findings").size());
        assertTrue(result.path("summary").path("witnessesTruncated").asBoolean());
        var identity = result.path("findings").get(0).path("id");
        oldCopy.put("line", 99).put("endLine", 99); newCopy.put("line", 123).put("endLine", 123);
        assertEquals(identity, limited.compare(before, after).path("findings").get(0).path("id"));
    }
    @Test void directComparisonRejectsUnrelatedReportsOrRepositories() throws Exception {
        var before = report("users", "/users"); var after = report(null, null);
        after.put("name", "another-system");
        assertThrows(java.io.IOException.class, () -> removals.compare(before, after));
        after.put("name", "system");
        ((ObjectNode) before.path("components").get(0)).put("repositoryIdentity", "repository-one");
        ((ObjectNode) after.path("components").get(0)).put("repositoryIdentity", "repository-two");
        assertThrows(java.io.IOException.class, () -> removals.compare(before, after));
    }
}
