package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaScriptHttpAnalysisValidationTest {
    private static ObjectNode report() {
        ObjectNode root = JavaScriptExtractionService.JSON.createObjectNode();
        root.putArray("components").addObject().put("id", "backend:api").put("kind", "backend");
        root.withArray("components").addObject().put("id", "frontend:ui").put("kind", "frontend");
        ObjectNode http = root.putObject("http"); http.putObject("summary").put("matchingComplete", true);
        http.putArray("endpoints").addObject().put("id", "endpoint").put("component", "backend:api")
                .put("file", "routes.js").put("line", 1).put("method", "GET").put("normalizedPath", "/users")
                .put("status", "observed-candidate-caller");
        ObjectNode call = http.putArray("calls").addObject().put("id", "call").put("component", "frontend:ui")
                .put("file", "client.js").put("line", 2).put("method", "GET").put("normalizedPath", "/users")
                .put("status", "matched").put("target", "backend").put("resolution", "literal")
                .put("targetComponent", "backend:api").put("matchKind", "literal-candidate");
        call.putArray("candidateEndpointIds").add("endpoint"); return root;
    }
    private static ObjectNode call(ObjectNode input) { return (ObjectNode) input.path("http").path("calls").get(0); }
    private static ObjectNode endpoint(ObjectNode input) { return (ObjectNode) input.path("http").path("endpoints").get(0); }
    private static void nonmatched(ObjectNode input, String status) {
        ObjectNode call = call(input); call.put("status", status); call.remove(List.of("targetComponent", "matchKind"));
        call.withArray("candidateEndpointIds").removeAll();
    }
    private static void validate(JsonNode input) throws IOException { JavaScriptHttpAnalysisValidator.validate(input, 100); }

    @Test void validMatchedUnmatchedUnknownExternalAndCaseOnlyStatesAreAccepted() throws Exception {
        validate(report());
        ObjectNode unmatched = report(); nonmatched(unmatched, "unmatched"); validate(unmatched);
        ObjectNode unknown = report(); nonmatched(unknown, "unknown"); call(unknown).put("method", "UNKNOWN").putNull("normalizedPath").put("resolution", "unresolved"); validate(unknown);
        ObjectNode external = report(); nonmatched(external, "external"); call(external).put("target", "external").putNull("normalizedPath").put("resolution", "unresolved"); validate(external);
        ObjectNode caseOnly = report(); nonmatched(caseOnly, "case-only-candidate"); call(caseOnly).putArray("caseOnlyCandidateEndpointIds").add("endpoint"); validate(caseOnly);
        ObjectNode head = report(); call(head).put("method", "HEAD"); validate(head);
        ObjectNode all = report(); endpoint(all).put("method", "ALL"); validate(all);
    }

    @Test void candidateArraysMustBeArraysOfUniqueExistingBackendEndpointIdentities() {
        ObjectNode objectArray = report(); call(objectArray).putObject("candidateEndpointIds").put("one", "endpoint");
        assertThrows(IOException.class, () -> validate(objectArray));
        ObjectNode missing = report(); call(missing).withArray("candidateEndpointIds").set(0, JavaScriptExtractionService.JSON.getNodeFactory().textNode("absent"));
        assertThrows(IOException.class, () -> validate(missing));
        ObjectNode duplicate = report(); call(duplicate).withArray("candidateEndpointIds").add("endpoint");
        assertThrows(IOException.class, () -> validate(duplicate));
        ObjectNode crossArrays = report(); call(crossArrays).putArray("caseOnlyCandidateEndpointIds").add("endpoint");
        assertThrows(IOException.class, () -> validate(crossArrays));
        ObjectNode frontendEndpoint = report(); endpoint(frontendEndpoint).put("component", "frontend:ui");
        assertThrows(IOException.class, () -> validate(frontendEndpoint));
    }

    @Test void candidateCardinalityMustAgreeWithStatusAndTargetAssertions() {
        for (String status : List.of("unmatched", "ambiguous", "case-only-candidate", "external")) {
            ObjectNode input = report(); call(input).put("status", status);
            assertThrows(IOException.class, () -> validate(input), status);
        }
        ObjectNode noMatch = report(); call(noMatch).withArray("candidateEndpointIds").removeAll();
        assertThrows(IOException.class, () -> validate(noMatch));
        ObjectNode wrongTarget = report(); call(wrongTarget).put("targetComponent", "frontend:ui");
        assertThrows(IOException.class, () -> validate(wrongTarget));
        ObjectNode unknownStatus = report(); call(unknownStatus).put("status", "healthy");
        assertThrows(IOException.class, () -> validate(unknownStatus));
    }

    @Test void realAmbiguousAndUnknownPartialEvidenceAreAccepted() throws Exception {
        ObjectNode input = report();
        input.withObject("http").withArray("endpoints").add(endpoint(input).deepCopy().put("id", "alternative"));
        nonmatched(input, "ambiguous"); call(input).withArray("candidateEndpointIds").add("endpoint").add("alternative"); validate(input);
        call(input).put("status", "unknown"); call(input).withArray("candidateEndpointIds").remove(1);
        call(input).putArray("unsupportedTemplateCandidateEndpointIds").add("alternative"); validate(input);
    }

    @Test void evaluatedRequestsRequireKnownMethodsAndUsablePaths() {
        for (String method : List.of("UNKNOWN", "ALL", "BOGUS")) {
            ObjectNode input = report(); call(input).put("method", method);
            assertThrows(IOException.class, () -> validate(input));
        }
        for (String path : List.of("https://example.test/users", "/{param}", "users", "/users\n")) {
            ObjectNode input = report(); call(input).put("normalizedPath", path);
            assertThrows(IOException.class, () -> validate(input));
        }
        ObjectNode missing = report(); call(missing).remove("normalizedPath"); assertThrows(IOException.class, () -> validate(missing));
        ObjectNode incompatible = report(); endpoint(incompatible).put("method", "POST"); assertThrows(IOException.class, () -> validate(incompatible));
        ObjectNode unresolved = report(); call(unresolved).put("resolution", "unresolved"); assertThrows(IOException.class, () -> validate(unresolved));
    }

    @Test void sourceReferencesBooleanCompletenessAndRecordBoundsAreStrict() {
        ObjectNode missingComponent = report(); call(missingComponent).put("component", "missing"); assertThrows(IOException.class, () -> validate(missingComponent));
        ObjectNode invalidBoolean = report(); invalidBoolean.withObject("http").withObject("summary").put("matchingComplete", "true"); assertThrows(IOException.class, () -> validate(invalidBoolean));
        ObjectNode badSource = report(); call(badSource).put("file", "../private.js"); assertThrows(IOException.class, () -> validate(badSource));
        ObjectNode badLine = report(); call(badLine).put("endLine", 1); assertThrows(IOException.class, () -> validate(badLine));
        ObjectNode duplicate = report(); duplicate.withObject("http").withArray("calls").add(call(duplicate).deepCopy()); assertThrows(IOException.class, () -> validate(duplicate));
        assertThrows(IOException.class, () -> JavaScriptHttpAnalysisValidator.validate(report(), 1));
        assertThrows(IllegalArgumentException.class, () -> JavaScriptHttpAnalysisValidator.validate(report(), 0));
    }
}
