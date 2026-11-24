package edu.university.ecs.lab.javascript;

import static org.junit.jupiter.api.Assertions.*;

import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.Endpoint;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.parsers.JavaScriptLanguageParser;
import edu.university.ecs.lab.common.parsers.ParseResult;
import edu.university.ecs.lab.common.parsers.ParserRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaScriptParserIndexIsolationTest {
  @TempDir Path temporary;
  private final Path adapter = Path.of("adapter/javascript").toAbsolutePath();

  @Test
  void preparingAnotherRevisionReplacesItsModuleAndFailureIndexes() throws Exception {
    Path root = Files.createDirectories(temporary.resolve("source"));
    Path file = root.resolve("app.ts");
    Files.writeString(file, "function { invalid");
    JavaScriptLanguageParser parser = new JavaScriptLanguageParser(adapter, "node");
    RepositoryConfig config = config("api");
    parser.prepareRepository(root, config);
    assertFalse(parser.parse(service("api", root), file.toFile(), config, false).isSuccess());
    Files.writeString(
        file, "import express from 'express';const app=express();app.get('/fixed',()=>{});");
    parser.prepareRepository(root, config);
    ParseResult result = parser.parse(service("api", root), file.toFile(), config, false);
    assertTrue(result.isSuccess(), result.getFailureReason());
    assertEquals(
        Set.of("/fixed"),
        result.getClasses().stream()
            .flatMap(type -> type.getMethods().stream())
            .filter(Endpoint.class::isInstance)
            .map(Endpoint.class::cast)
            .map(Endpoint::getUrl)
            .collect(Collectors.toSet()));
  }

  @Test
  void independentConcurrentRegistriesCannotSwapRepositoryContexts() throws Exception {
    Path left = Files.createDirectory(temporary.resolve("left"));
    Path right = Files.createDirectory(temporary.resolve("right"));
    for (Path root : new Path[] {left, right}) {
      String name = root.getFileName().toString();
      Files.writeString(
          root.resolve("app.js"),
          "const app=require('express')();app.get('/" + name + "',()=>{});");
    }
    try (var workers = Executors.newFixedThreadPool(2)) {
      var results = workers.invokeAll(java.util.List.of(job(left), job(right)));
      assertEquals(Set.of("/left"), results.get(0).get());
      assertEquals(Set.of("/right"), results.get(1).get());
    }
  }

  private Callable<Set<String>> job(Path root) {
    return () -> {
      String name = root.getFileName().toString();
      ParserRegistry registry = ParserRegistry.createDefault(adapter, "node");
      RepositoryConfig config = config(name);
      registry.prepareRepository(root, config);
      Microservice service = service(name, root);
      Path file = root.resolve("app.js");
      ParseResult result =
          registry
              .findParserFor(file.toFile())
              .orElseThrow()
              .parse(service, file.toFile(), config, false);
      assertTrue(result.isSuccess(), result.getFailureReason());
      return result.getClasses().stream()
          .flatMap(type -> type.getMethods().stream())
          .filter(Endpoint.class::isInstance)
          .map(Endpoint.class::cast)
          .map(Endpoint::getUrl)
          .collect(Collectors.toSet());
    };
  }

  private RepositoryConfig config(String name) {
    return new RepositoryConfig(
        new RepositoryBranchPair("https://github.com/example/" + name + ".git", "main"),
        "a".repeat(40));
  }

  private Microservice service(String name, Path root) {
    return new Microservice(
        null, name, config(name).repoBranchPair().repositoryURL(), "a".repeat(40), root);
  }
}
