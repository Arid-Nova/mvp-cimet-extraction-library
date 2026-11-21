package edu.university.ecs.lab.javascript;

import static edu.university.ecs.lab.javascript.JavaScriptTestSnapshots.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaScriptExtractionTest {
  @TempDir Path temporary;

  private Path adapter(String code) throws IOException {
    Path directory = Files.createDirectories(temporary.resolve("trusted adapter"));
    Files.writeString(directory.resolve("analyzer.cjs"), code);
    return directory;
  }

  @Test
  void acceptsBoundedJsonWithoutRunningTargetCode() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("source with spaces"));
    Files.writeString(
        root.resolve("index.js"), "throw new Error('target source must never execute');");
    String json = snapshot("backend", "api").toString();
    Path adapter =
        adapter(
            "process.stdout.write("
                + JavaScriptExtractionService.JSON.writeValueAsString(json)
                + ");");
    var result = new JavaScriptExtractionService(adapter).extract(root, "backend", "api");
    assertEquals("api", result.path("component").path("name").asText());
  }

  @Test
  void failsClosedForNonzeroInvalidJsonAndUnknownSchemaWithoutStderrDisclosure() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("source"));
    Path adapter = adapter("process.stderr.write('sensitive-stub-text');process.exit(3);");
    IOException error =
        assertThrows(
            IOException.class,
            () -> new JavaScriptExtractionService(adapter).extract(root, "backend", "api"));
    assertFalse(error.getMessage().contains("sensitive-stub-text"));
    assertTrue(error.getMessage().contains("exit code 3"));
    adapter("process.stdout.write('not JSON');");
    assertThrows(
        IOException.class,
        () -> new JavaScriptExtractionService(adapter).extract(root, "backend", "api"));
    adapter("process.stdout.write('{\"schemaVersion\":99}');");
    assertThrows(
        IOException.class,
        () -> new JavaScriptExtractionService(adapter).extract(root, "backend", "api"));
  }

  @Test
  void boundsProcessTimeAndOutput() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("source"));
    Path adapter = adapter("setInterval(()=>{},1000);");
    var shortRun = new JavaScriptExtractionService(adapter, "node", Duration.ofMillis(300), 1024);
    IOException timeout =
        assertThrows(IOException.class, () -> shortRun.extract(root, "backend", "api"));
    assertTrue(timeout.getMessage().contains("time limit"));
    adapter("process.stdout.write('x'.repeat(20000));");
    var bounded = new JavaScriptExtractionService(adapter, "node", Duration.ofSeconds(10), 1024);
    IOException output =
        assertThrows(IOException.class, () -> bounded.extract(root, "backend", "api"));
    assertTrue(output.getMessage().contains("output limit"));
  }

  @Test
  void validatesComponentAndRelativeSourcePaths() throws Exception {
    Path adapter = adapter("");
    assertThrows(
        IllegalArgumentException.class,
        () -> new JavaScriptExtractionService(adapter).extract(temporary, null, "api"));
    var snapshot = snapshot("backend", "api");
    snapshot.withArray("endpoints").add(endpoint("bad", "/users").put("file", "../escape.js"));
    assertThrows(IOException.class, () -> JavaScriptExtractionService.validateSnapshot(snapshot));
  }

  @Test
  void retainsOnlyWhitelistedResourceCodesWithoutDisclosingProcessDiagnostics() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("source"));
    for (String code :
        java.util.List.of(
            "SOURCE_FILE_LIMIT",
            "SOURCE_ENTRY_LIMIT",
            "SOURCE_BYTE_LIMIT",
            "AST_NODE_LIMIT",
            "ANALYSIS_WORK_LIMIT",
            "ANALYSIS_RECORD_LIMIT")) {
      Path trusted =
          adapter(
              "process.stderr.write('JavaScript analysis failed ["
                  + code
                  + "]: sensitive-source-or-path');process.exit(2);");
      IOException error =
          assertThrows(
              IOException.class,
              () -> new JavaScriptExtractionService(trusted).extract(root, "backend", "api"));
      assertTrue(error.getMessage().contains(code));
      assertFalse(error.getMessage().contains("sensitive-source-or-path"));
    }
    Path trusted =
        adapter(
            "process.stderr.write('JavaScript analysis failed [UNKNOWN_SECRET]:"
                + " private');process.exit(2);");
    IOException unknown =
        assertThrows(
            IOException.class,
            () -> new JavaScriptExtractionService(trusted).extract(root, "backend", "api"));
    assertFalse(unknown.getMessage().contains("UNKNOWN_SECRET"));
    assertFalse(unknown.getMessage().contains("private"));
  }
}
