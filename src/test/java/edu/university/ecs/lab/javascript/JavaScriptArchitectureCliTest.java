package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Runs the actual pinned parser through the Java CLI against a small source-only fixture. */
class JavaScriptArchitectureCliTest {
    @TempDir Path temporary;

    @Test void extractsAnalyzesAndComparesLocalFunctionsWithoutStartingTheApplication() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("source with spaces"));
        Files.writeString(source.resolve("app.js"), """
                const express = require('express');
                const { calculate } = require('./logic');
                const app = express();
                app.get('/sum', (req, res) => res.json(calculate()));
                module.exports = app;
                throw new Error('This target must not execute');
                """);
        Files.writeString(source.resolve("logic.js"), "exports.calculate = function calculate() { return 1; };\n");
        Path before = temporary.resolve("before.json"), after = temporary.resolve("after.json");
        snapshot(source, before);
        Files.writeString(source.resolve("logic.js"), "exports.calculate = function calculate() { return 2; };\n");
        snapshot(source, after);
        Path oldAnalysis = temporary.resolve("old-analysis.json"), newAnalysis = temporary.resolve("new-analysis.json");
        architecture(before, oldAnalysis);
        architecture(after, newAnalysis);
        JsonNode analysis = JavaScriptExtractionService.JSON.readTree(Files.readString(newAnalysis));
        assertEquals("static-javascript-architecture", analysis.path("analysis").asText());
        assertEquals(2, analysis.path("graphs").path("modules").path("metrics").path("nodeCount").asInt());
        assertEquals(1, analysis.path("graphs").path("modules").path("metrics").path("edgeCount").asInt());
        JsonNode oldSnapshot = JavaScriptExtractionService.JSON.readTree(Files.readString(before));
        JsonNode newSnapshot = JavaScriptExtractionService.JSON.readTree(Files.readString(after));
        assertEquals(1, newSnapshot.path("architecture").path("endpointHandlers").size());
        assertEquals(1, newSnapshot.path("architecture").path("calls").size());
        // A helper-body edit changes semantic evidence while keeping function identities stable.
        assertEquals(oldSnapshot.path("architecture").path("functions").get(1).path("id"),
                newSnapshot.path("architecture").path("functions").get(1).path("id"));
        assertNotEquals(oldSnapshot.path("architecture").path("functions").get(1).path("bodyFingerprint"),
                newSnapshot.path("architecture").path("functions").get(1).path("bodyFingerprint"));
        Path delta = temporary.resolve("architecture-delta.json");
        JavaScriptCli.run(new String[]{"architecture-delta", "--old", oldAnalysis.toString(),
                "--new", newAnalysis.toString(), "--output", delta.toString()});
        JsonNode difference = JavaScriptExtractionService.JSON.readTree(Files.readString(delta));
        assertEquals(1, difference.path("summary").path("changedFunctions").asInt());
        assertEquals(2, difference.path("potentiallyAffectedEndpoints").size());
        for (JsonNode impact : difference.path("potentiallyAffectedEndpoints")) {
            assertEquals("/sum", impact.path("endpoint").path("normalizedPath").asText());
            assertEquals(2, impact.path("functionPath").size());
        }
        assertThrows(IllegalArgumentException.class, () -> JavaScriptCli.run(new String[]{"architecture", "--unknown", "value"}));
    }

    private void snapshot(Path root, Path output) throws Exception {
        JavaScriptCli.run(new String[]{"snapshot", "--adapter", Path.of("adapter/javascript").toAbsolutePath().toString(),
                "--root", root.toString(), "--kind", "backend", "--name", "fixture-api", "--output", output.toString()});
    }

    private void architecture(Path snapshot, Path output) throws Exception {
        JavaScriptCli.run(new String[]{"architecture", "--snapshot", snapshot.toString(), "--name", "fixture",
                "--output", output.toString()});
    }
}
