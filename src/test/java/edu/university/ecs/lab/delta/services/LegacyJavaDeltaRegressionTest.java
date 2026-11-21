package edu.university.ecs.lab.delta.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline Java compatibility evidence; not a replacement for the public-repository test profile.
 */
class LegacyJavaDeltaRegressionTest {
  @TempDir Path temporary;

  @Test
  void javaOnlyGitDeltaFromMetadataFreeBaseMergesToFreshTargetWithoutNode() throws Exception {
    Path first = temporary.resolve("application");
    Path second = temporary.resolve("health");
    String before;
    String after;
    String unchanged;
    try (Git git = Git.init().setDirectory(first.toFile()).setInitialBranch("main").call()) {
      write(
          first,
          "pom.xml",
          "<project><modelVersion>4.0.0</modelVersion><artifactId>application</artifactId></project>");
      write(first, "HelloController.java", controller("HelloController", "/before"));
      write(first, "GoneEntity.java", "@Entity public class GoneEntity { private String value; }");
      write(
          first,
          "WorkerService.java",
          "@Service public class WorkerService { public void run() {} }");
      before = commit(git, "base Java revision");
      write(first, "HelloController.java", controller("HelloController", "/after"));
      Files.delete(first.resolve("src/main/java/demo/GoneEntity.java"));
      Files.delete(first.resolve("src/main/java/demo/WorkerService.java"));
      write(
          first,
          "RenamedService.java",
          "@Service public class RenamedService { public void run() {} }");
      write(
          first,
          "NewRepository.java",
          "@Repository public interface NewRepository { String load(); }");
      after = commit(git, "change Java source");
    }
    try (Git git = Git.init().setDirectory(second.toFile()).setInitialBranch("main").call()) {
      write(
          second,
          "pom.xml",
          "<project><modelVersion>4.0.0</modelVersion><artifactId>health</artifactId></project>");
      write(second, "HealthController.java", controller("HealthController", "/health"));
      unchanged = commit(git, "unchanged Java service");
    }

    Path run = Files.createDirectory(temporary.resolve("run"));
    String executable =
        Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
            .toString();
    ProcessBuilder command =
        new ProcessBuilder(
            executable,
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            Worker.class.getName(),
            before,
            after,
            unchanged);
    command.directory(run.toFile());
    Map<String, String> environment = command.environment();
    environment.put("CONFIG_SERVER_URL", "http://127.0.0.1:9");
    environment.put("INTERNAL_SERVICE_KEY", "");
    environment.put("GIT_CONFIG_COUNT", "2");
    environment.put("GIT_CONFIG_KEY_0", "url." + first.toUri() + ".insteadOf");
    environment.put("GIT_CONFIG_VALUE_0", "https://github.com/fixture/java-application.git");
    environment.put("GIT_CONFIG_KEY_1", "url." + second.toUri() + ".insteadOf");
    environment.put("GIT_CONFIG_VALUE_1", "https://github.com/fixture/java-health.git");
    Path log = run.resolve("fixture.log");
    command.redirectErrorStream(true).redirectOutput(log.toFile());
    Process process = command.start();
    try {
      assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Offline Java delta fixture timed out");
      assertEquals(0, process.exitValue(), Files.readString(log));
      assertEquals("verified", Files.readString(run.resolve("verified.txt")));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static String controller(String name, String route) {
    return "@RestController public class "
        + name
        + " { @GetMapping(\""
        + route
        + "\") public String get(){ return \"ok\"; } }";
  }

  private static void write(Path root, String filename, String source) throws Exception {
    Path file =
        filename.endsWith(".java")
            ? root.resolve("src/main/java/demo").resolve(filename)
            : root.resolve(filename);
    Files.createDirectories(file.getParent());
    String content =
        filename.endsWith(".java")
            ? "package demo;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "import org.springframework.stereotype.*;\n"
                + "import jakarta.persistence.*;\n"
                + source
            : source;
    Files.writeString(file, content);
  }

  private static String commit(Git git, String message) throws Exception {
    git.add().addFilepattern(".").call();
    git.add().setUpdate(true).addFilepattern(".").call();
    return git.commit()
        .setMessage(message)
        .setAuthor("Fixture", "fixture@example.invalid")
        .setCommitter("Fixture", "fixture@example.invalid")
        .call()
        .getName();
  }

  public static class Worker {
    public static void main(String[] revisions) throws Exception {
      // A Java-only extraction must not start or require the JavaScript runtime.
      System.setProperty("cimet.node.executable", "nonexistent-node-for-java-regression");
      RepositoryConfig first =
          new RepositoryConfig(
              new RepositoryBranchPair("https://github.com/fixture/java-application.git", "main"),
              revisions[0]);
      RepositoryConfig second =
          new RepositoryConfig(
              new RepositoryBranchPair("https://github.com/fixture/java-health.git", "main"),
              revisions[2]);
      Config config = new Config("java-regression", List.of(first, second));
      MicroserviceSystem base = IRExtractionService.create(config);
      base.setMetadata(null);
      base.getDescendants().forEach(node -> node.setMetadata(null));
      String baseFingerprint = SnapshotDeltaService.fingerprint(base);
      Path saved = Path.of("legacy-base.json");
      JsonReadWriteUtils.writeToJSON(saved, base);
      SystemChange delta =
          DeltaExtractionService.create(config, saved, Map.of(first, revisions[1]));
      assertEquals(2, delta.getNewCommits().size());
      assertEquals(
          revisions[2], delta.getNewCommits().get(second.repoBranchPair().repositoryURL()));
      Set<String> changedPaths =
          delta.getChanges().stream()
              .map(change -> change.getChangeType() + ":" + change.getPath().getFileName())
              .collect(Collectors.toSet());
      assertTrue(changedPaths.contains("MODIFY:HelloController.java"), changedPaths.toString());
      assertTrue(changedPaths.contains("DELETE:GoneEntity.java"), changedPaths.toString());
      assertTrue(changedPaths.contains("DELETE:WorkerService.java"), changedPaths.toString());
      assertTrue(changedPaths.contains("ADD:RenamedService.java"), changedPaths.toString());
      assertTrue(changedPaths.contains("ADD:NewRepository.java"), changedPaths.toString());
      // Old snapshots lack language/provenance metadata; enrichment may produce additional
      // structural modifications even for unchanged source. Revision and target preservation,
      // rather than an empty metadata migration, are the compatibility contract here.
      Path deltaPath = Path.of("delta.json");
      JsonReadWriteUtils.writeToJSON(deltaPath, delta);
      SystemChange restored = DeltaExtractionService.read(deltaPath);
      MicroserviceSystem merged = MergeService.create(SnapshotDeltaService.copy(base), restored);
      MicroserviceSystem target =
          IRExtractionService.create(
              new Config(
                  "java-regression",
                  List.of(new RepositoryConfig(first.repoBranchPair(), revisions[1]), second)));
      assertEquals(
          SnapshotDeltaService.fingerprint(target), SnapshotDeltaService.fingerprint(merged));
      assertEquals(baseFingerprint, SnapshotDeltaService.fingerprint(base));
      Set<String> urls =
          merged.getMicroservices().stream()
              .flatMap(service -> service.getEndpoints().stream())
              .map(endpoint -> endpoint.getUrl())
              .collect(Collectors.toSet());
      assertEquals(Set.of("/after", "/health"), urls);
      assertFalse(restored.getChanges().isEmpty());
      Files.writeString(Path.of("verified.txt"), "verified");
    }
  }
}
