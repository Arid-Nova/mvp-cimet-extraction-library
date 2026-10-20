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
        assertEquals(-1.0, analysis.path("graphs").path("modules").path("metrics").path("avgSC").asDouble(), 1e-12);
        assertEquals(0.0, analysis.path("graphs").path("modules").path("metrics").path("SCCmodularity").asDouble(), 1e-12);
        assertEquals(1, analysis.path("cohesion").path("components").get(0).path("operationCount").asInt());
        assertTrue(analysis.path("cohesion").path("components").get(0).path("SIDC").path("value").isNull());
        JsonNode oldSnapshot = JavaScriptExtractionService.JSON.readTree(Files.readString(before));
        JsonNode newSnapshot = JavaScriptExtractionService.JSON.readTree(Files.readString(after));
        assertEquals(1, newSnapshot.path("architecture").path("endpointHandlers").size());
        assertEquals(1, newSnapshot.path("architecture").path("calls").size());
        assertEquals(1, newSnapshot.path("architecture").path("contracts").path("operations").size());
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
        assertEquals(1, difference.path("cohesion").path("components").size());
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

    @Test void changedTopLevelSqlSeedsAr7EvenWhenHandlerAndQueryFunctionBodiesStayIdentical() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("database fixture"));
        Files.writeString(source.resolve("app.js"), """
                const express = require('express');
                const { list } = require('./queries');
                const app = express();
                app.get('/users', (req, res) => res.json(list()));
                app.get('/health', (req, res) => res.json({status: 'ok'}));
                throw new Error('Never execute this target or connect to its database');
                """);
        String queries = """
                const mysql = require('mysql2');
                const pool = mysql.createPool({});
                const sql = 'SELECT id FROM users';
                exports.list = function list() { return pool.query(sql); };
                """;
        Files.writeString(source.resolve("queries.js"), queries);
        Path before = temporary.resolve("query-before.json"), after = temporary.resolve("query-after.json");
        snapshot(source, before);
        Files.writeString(source.resolve("queries.js"), queries.replace("SELECT id FROM", "SELECT id, name FROM"));
        snapshot(source, after);
        Path a = temporary.resolve("query-a.json"), b = temporary.resolve("query-b.json"), delta = temporary.resolve("query-delta.json");
        architecture(before, a); architecture(after, b);
        JavaScriptCli.run(new String[]{"architecture-delta", "--old", a.toString(), "--new", b.toString(), "--output", delta.toString()});
        JsonNode result = JavaScriptExtractionService.JSON.readTree(Files.readString(delta));
        assertEquals(0, result.path("changedFunctions").size(), "The query value changed outside the function bodies");
        assertEquals(1, result.path("semantics").path("changedDataAccess").size());
        assertEquals(2, result.path("semantics").path("AR7").path("potentiallyAffectedEndpointObservations").asInt());
        for (JsonNode impact : result.path("semantics").path("AR7").path("potentiallyAffectedEndpoints"))
            assertEquals("/users", impact.path("endpoint").path("normalizedPath").asText());
        JsonNode analysis = JavaScriptExtractionService.JSON.readTree(Files.readString(b));
        assertEquals(3, analysis.path("limits").path("metricDefinitionsVersion").asInt());
        assertEquals(1, analysis.path("semantics").path("components").get(0).path("health").path("candidateCount").asInt());
        assertTrue(analysis.path("additionalGraphs").path("functionGraph").path("available").asBoolean());
    }

    private void architecture(Path snapshot, Path output) throws Exception {
        JavaScriptCli.run(new String[]{"architecture", "--snapshot", snapshot.toString(), "--name", "fixture",
                "--output", output.toString()});
    }
}
