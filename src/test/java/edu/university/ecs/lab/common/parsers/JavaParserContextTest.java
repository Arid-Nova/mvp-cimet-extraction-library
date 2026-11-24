package edu.university.ecs.lab.common.parsers;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.StaticJavaParser;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.delta.services.SnapshotDeltaService;
import edu.university.ecs.lab.intermediate.merge.services.MergeService;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaParserContextTest {
  @TempDir Path temporary;
  private final RepositoryConfig config =
      new RepositoryConfig(
          new RepositoryBranchPair("https://github.com/example/java-context.git", "main"),
          "a".repeat(40));

  @Test
  void everyNamedTypeKeepsItsOwnMembersAnnotationsAndPortableIdentity() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("types"));
    Path source =
        write(
            root,
            "Primary.java",
            """
            package sample;
            @RestController class Primary {
              int outer;
              @GetMapping("/outer") String route(){return "ok";}
              static class Nested { int inner; void nested(){} }
            }
            @Service class Worker { int jobs; void work(){} }
            interface Contract { void call(); }
            enum State { READY; void state(){} }
            record Data(String value) { String valueAgain(){return value;} }
            """);
    List<AbstractClass> types = parse(root, source, false);
    assertEquals(6, types.size());
    Map<String, AbstractClass> named =
        types.stream().collect(Collectors.toMap(AbstractClass::getName, type -> type));
    assertEquals(Set.of("route"), methodNames(named.get("Primary")));
    assertEquals(Set.of("nested"), methodNames(named.get("Nested")));
    assertEquals(Set.of("work"), methodNames(named.get("Worker")));
    assertEquals(Set.of("outer"), fieldNames(named.get("Primary")));
    assertEquals(Set.of("inner"), fieldNames(named.get("Nested")));
    assertEquals(Set.of("value"), fieldNames(named.get("Data")));
    assertEquals(ClassRole.CONTROLLER, named.get("Primary").getClassRole());
    assertEquals(ClassRole.SERVICE, named.get("Worker").getClassRole());
    assertEquals(ClassRole.UNKNOWN, named.get("Nested").getClassRole());
    assertEquals(
        Set.of("RestController"),
        named.get("Primary").getAnnotations().stream()
            .map(Annotation::getName)
            .collect(Collectors.toSet()));
    assertEquals(6, types.stream().map(AbstractClass::getID).distinct().count());
    assertTrue(types.stream().noneMatch(type -> type.getPath().toString().contains(":")));
    assertTrue(
        types.stream()
            .allMatch(type -> type.getMetadata().getString("sourcePath").equals("/Primary.java")));
    assertEquals("Primary.Nested", named.get("Nested").getMetadata().getString("declaredType"));
    assertEquals(
        Set.of("Primary", "Worker"),
        parse(root, source, true).stream().map(AbstractClass::getName).collect(Collectors.toSet()));
  }

  @Test
  void primaryAndSecondaryIdentitiesSurviveDeclarationReordering() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("identity"));
    Path source =
        write(
            root,
            "Primary.java",
            "class Helper { void help(){} } public class Primary { void main(){} }");
    List<AbstractClass> before = parse(root, source, false);
    assertEquals("Primary", before.getFirst().getName());
    assertEquals("/Primary.java", before.getFirst().getPath().toString().replace('\\', '/'));
    Map<String, String> identities =
        before.stream().collect(Collectors.toMap(AbstractClass::getName, AbstractClass::getID));
    Files.writeString(
        source, "public class Primary { void main(){} } class Helper { void help(){} }");
    assertEquals(
        identities,
        parse(root, source, false).stream()
            .collect(Collectors.toMap(AbstractClass::getName, AbstractClass::getID)));
    try (var scope = FileUtils.withRepositoryRoot(config.getRepoName(), root)) {
      assertEquals(
          "Primary",
          SourceToObjectUtils.parseClass(service(root), source.toFile(), config, false).getName());
    }
    Path differentlyNamed = write(root, "Legacy.java", "class ActualName { void own(){} }");
    assertEquals(
        "/Legacy.java",
        parse(root, differentlyNamed, false).getFirst().getPath().toString().replace('\\', '/'));
  }

  @Test
  void resolvedNamespacesRemainDistinctWhileUnknownSymbolsStayExplicitlyUnknown() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("identity-types"));
    write(
        root,
        "app/UserService.java",
        "package app; public class UserService { public void run(){} }");
    write(
        root,
        "external/UserService.java",
        "package external; public class UserService { public void run(){} }");
    Path source =
        write(
            root,
            "Caller.java",
            """
            class Caller {
              app.UserService first; external.UserService second; MissingType missing;
              void invoke(){ first.run(); second.run(); missing.run(); }
            }
            """);
    List<MethodCall> calls =
        parse(root, source, false).getFirst().getMethods().iterator().next().getMethodCalls();
    Set<String> qualified =
        calls.stream()
            .filter(call -> "resolved".equals(call.getMetadata().optString("objectTypeResolution")))
            .map(call -> call.getMetadata().getString("qualifiedObjectType"))
            .collect(Collectors.toSet());
    assertEquals(Set.of("app.UserService", "external.UserService"), qualified);
    assertEquals(
        2, calls.stream().filter(call -> "UserService".equals(call.getObjectType())).count());
    MethodCall unknown =
        calls.stream()
            .filter(call -> "missing".equals(call.getObjectName()))
            .findFirst()
            .orElseThrow();
    assertEquals("unresolved", unknown.getMetadata().getString("objectTypeResolution"));
    assertFalse(unknown.getMetadata().has("qualifiedObjectType"));
  }

  @Test
  void repeatedNestedShortNamesKeepDistinctComponentIdsAcrossCopiesAndBothMergeForms()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("nested-component-identities"));
    Path source =
        write(
            root,
            "Owners.java",
            """
package sample;
class OuterA { static class Worker { int value; void run(String input){input.length();} } }
class OuterB { static class Worker { int value; void run(String input){input.length();} } }
""");
    List<AbstractClass> types = parse(root, source, false);
    List<AbstractClass> workers =
        types.stream().filter(type -> type.getName().equals("Worker")).toList();
    assertEquals(2, workers.size());
    assertEquals(
        Set.of("sample.OuterA.Worker", "sample.OuterB.Worker"),
        workers.stream().map(AbstractClass::getComponentPrefix).collect(Collectors.toSet()));
    List<String> ids =
        workers.stream()
            .flatMap(type -> type.getDescendants().stream())
            .map(Component::getID)
            .toList();
    assertEquals(ids.size(), new HashSet<>(ids).size());
    assertTrue(
        ids.containsAll(
            Set.of(
                "sample.OuterA.Worker&value",
                "sample.OuterB.Worker&value",
                "sample.OuterA.Worker&run",
                "sample.OuterB.Worker&run")));

    MicroserviceSystem system =
        new MicroserviceSystem("nested-identities", new LinkedHashSet<>(), new LinkedHashSet<>());
    Microservice host =
        new Microservice(
            system,
            "fixture",
            "https://github.com/example/java-context.git",
            "a".repeat(40),
            Path.of("/"));
    types.forEach(host::addAbstractClass);
    system.getMicroservices().add(host);
    MicroserviceSystem copy = SnapshotDeltaService.copy(system);
    assertFalse(
        JsonReadWriteUtils.setupObjectWriter()
            .writeValueAsString(system)
            .contains("\"componentPrefix\""));
    assertEquals(allComponentIds(system), allComponentIds(copy));
    assertEquals(
        allComponentIds(system),
        allComponentIds(
            MergeService.create(
                SnapshotDeltaService.copy(system), SnapshotDeltaService.compare(system, copy))));

    // Exercise the existing fine-grained delta reattachment routine with genuinely flattened
    // parsed components. Snapshot merges bypass this routine, so test both identity consumers.
    java.lang.reflect.Method attach =
        MergeService.class.getDeclaredMethod("recursiveAddDescendants", List.class, Node.class);
    attach.setAccessible(true);
    MergeService legacy = new MergeService(copy, new SystemChange());
    for (AbstractClass worker : copy.getMicroservices().iterator().next().getClasses()) {
      if (!worker.getName().equals("Worker")) continue;
      List<Component> flattened = new ArrayList<>(worker.getDescendants());
      Set<String> expected = flattened.stream().map(Component::getID).collect(Collectors.toSet());
      flattened.forEach(Component::clearDescendants);
      worker.clearDescendants();
      attach.invoke(legacy, flattened, worker);
      assertEquals(
          expected,
          worker.getDescendants().stream().map(Component::getID).collect(Collectors.toSet()));
      assertTrue(flattened.isEmpty());
    }
  }

  @Test
  void componentPrefixesKeepLegacyPrimaryAndNonJavaIdentityContracts() {
    JClass type = new JClass(null, Path.of("/Worker.java"), "sample", "", Set.of());
    assertEquals("sample.Worker", type.getComponentPrefix());
    type.setMetadata(new JSONObject().put("language", "java").put("declaredType", "Worker"));
    assertEquals("sample.Worker", type.getComponentPrefix());
    type.setMetadata(
        new JSONObject().put("language", "python").put("declaredType", "Outer.Worker"));
    assertEquals("sample.Worker", type.getComponentPrefix());
  }

  private static Set<String> allComponentIds(MicroserviceSystem system) {
    return system.getMicroservices().stream()
        .flatMap(service -> service.getClasses().stream())
        .flatMap(type -> type.getDescendants().stream())
        .map(Component::getID)
        .collect(Collectors.toSet());
  }

  @Test
  void feignPrefixAndClassMappingComposeWithoutTurningClientMethodsIntoEndpoints()
      throws Exception {
    Path root = Files.createDirectory(temporary.resolve("feign"));
    Path source =
        write(
            root,
            "Orders.java",
            """
@FeignClient(name="orders", path="/api")
@RequestMapping(path="/v1")
interface Orders { @GetMapping("/items") String list(@RequestParam("email") String email); }
""");
    AbstractClass client = parse(root, source, false).getFirst();
    assertEquals(ClassRole.FEIGN_CLIENT, client.getClassRole());
    assertTrue(client.getEndpoints().isEmpty());
    assertEquals(
        Set.of("/api/v1/items?email={?}"),
        client.getRestCalls().stream().map(RestCall::getUrl).collect(Collectors.toSet()));
    Method method = client.getMethods().iterator().next();
    assertEquals("String", method.getReturnType());
    assertTrue(
        method.getAnnotations().stream()
            .anyMatch(annotation -> annotation.getName().equals("GetMapping")));
  }

  @Test
  void repositoryAnnotationsHonorExplicitPathsAndBothClassAndMethodExportFlags() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("repository"));
    Path source =
        write(
            root,
            "PeopleRepository.java",
            """
            @RepositoryRestResource(path="people")
            interface PeopleRepository {
              @RestResource(path="by-name") Object findByName(String name);
              @RestResource(path="hidden", exported=false) Object secret();
              @RestResource(exported=FLAG) Object uncertain();
            }
            @RepositoryRestResource(path="hidden", exported=false)
            interface HiddenRepository { Object hidden(); }
            """);
    List<AbstractClass> types = parse(root, source, false);
    assertEquals(
        Set.of("/people/search/by-name"),
        types.getFirst().getEndpoints().stream().map(Endpoint::getUrl).collect(Collectors.toSet()));
    assertEquals(Set.of("findByName", "secret", "uncertain"), methodNames(types.getFirst()));
    assertTrue(
        types.stream()
            .filter(type -> type.getName().equals("HiddenRepository"))
            .findFirst()
            .orElseThrow()
            .getEndpoints()
            .isEmpty());
  }

  @Test
  void nonliteralFeignPathsDoNotInventRemoteRoutes() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("dynamic"));
    Path source =
        write(
            root,
            "Client.java",
            "@FeignClient(name=\"orders\",path=PREFIX) interface Client { @GetMapping(\"/items\")"
                + " String list(); }");
    AbstractClass client = parse(root, source, false).getFirst();
    assertTrue(client.getRestCalls().isEmpty());
    assertEquals("partial", client.getMetadata().getString("springCoverage"));
  }

  @Test
  void qualifiedSpringAnnotationsKeepTheirLiteralPathsAndHttpMethods() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("qualified-annotations"));
    Path source =
        write(
            root,
            "Qualified.java",
            """
            @org.springframework.web.bind.annotation.RestController
            @org.springframework.web.bind.annotation.RequestMapping(path="/api")
            class Qualified {
              @org.springframework.web.bind.annotation.GetMapping(path="/items")
              String items(){return "ok";}
            }
            """);
    AbstractClass type = parse(root, source, false).getFirst();
    assertEquals(ClassRole.CONTROLLER, type.getClassRole());
    Endpoint endpoint = type.getEndpoints().iterator().next();
    assertEquals("/api/items", endpoint.getUrl());
    assertEquals(
        edu.university.ecs.lab.common.models.enums.HttpMethod.GET, endpoint.getHttpMethod());
  }

  @Test
  void parseStateIsPerFileAndDoesNotMutateStaticJavaParserConfiguration() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("isolation"));
    var previousConfiguration = StaticJavaParser.getConfiguration();
    var previousResolver = previousConfiguration.getSymbolResolver();
    Path packaged = write(root, "One.java", "package one; class One { void first(){} }");
    Path plain = write(root, "Two.java", "class Two { void second(){} }");
    assertEquals("one", parse(root, packaged, false).getFirst().getPackageName());
    assertEquals("", parse(root, plain, false).getFirst().getPackageName());
    Path malformed = write(root, "Broken.java", "class Broken {");
    try (var scope = FileUtils.withRepositoryRoot(config.getRepoName(), root)) {
      assertFalse(
          new JavaLanguageParser()
              .parse(service(root), malformed.toFile(), config, false)
              .isSuccess());
    }
    assertEquals(Set.of("second"), methodNames(parse(root, plain, false).getFirst()));
    assertSame(previousConfiguration, StaticJavaParser.getConfiguration());
    assertEquals(previousResolver, StaticJavaParser.getConfiguration().getSymbolResolver());
  }

  @Test
  void concurrentCallsWithTheSameRepositoryNameKeepTheirScopedRootsAndModelsSeparate()
      throws Exception {
    Path first = Files.createDirectory(temporary.resolve("parallel-a"));
    Path second = Files.createDirectory(temporary.resolve("parallel-b"));
    Path firstFile =
        write(
            first,
            "Same.java",
            "package first; class Same { String text; int one(){return text.length();} }");
    Path secondFile =
        write(
            second,
            "Same.java",
            "package second; class Same { String text; int two(){return text.length();} }");
    ExecutorService workers = Executors.newFixedThreadPool(4);
    try {
      List<Callable<AbstractClass>> tasks = new ArrayList<>();
      for (int i = 0; i < 24; i++) {
        final boolean useFirst = i % 2 == 0;
        tasks.add(
            () ->
                parse(useFirst ? first : second, useFirst ? firstFile : secondFile, false)
                    .getFirst());
      }
      List<Future<AbstractClass>> models = workers.invokeAll(tasks);
      for (int i = 0; i < models.size(); i++) {
        AbstractClass type = models.get(i).get(20, TimeUnit.SECONDS);
        assertEquals(i % 2 == 0 ? "first" : "second", type.getPackageName());
        assertEquals(Set.of(i % 2 == 0 ? "one" : "two"), methodNames(type));
        MethodCall call = type.getMethods().iterator().next().getMethodCalls().getFirst();
        assertEquals("java.lang.String", call.getMetadata().getString("qualifiedObjectType"));
      }
    } finally {
      workers.shutdownNow();
    }
  }

  private List<AbstractClass> parse(Path root, Path source, boolean filter) {
    try (var scope = FileUtils.withRepositoryRoot(config.getRepoName(), root)) {
      ParseResult parsed =
          new JavaLanguageParser().parse(service(root), source.toFile(), config, filter);
      assertTrue(parsed.isSuccess(), parsed.getFailureReason());
      return parsed.getClasses();
    }
  }

  private static Path write(Path root, String relative, String content) throws Exception {
    Path path = root.resolve(relative);
    Files.createDirectories(path.getParent());
    return Files.writeString(path, content);
  }

  private Microservice service(Path root) {
    return new Microservice(
        null, "fixture", "https://github.com/example/java-context.git", "a".repeat(40), root);
  }

  private static Set<String> methodNames(AbstractClass type) {
    return type.getMethods().stream().map(Method::getName).collect(Collectors.toSet());
  }

  private static Set<String> fieldNames(AbstractClass type) {
    return type.getFields().stream().map(Field::getName).collect(Collectors.toSet());
  }
}
