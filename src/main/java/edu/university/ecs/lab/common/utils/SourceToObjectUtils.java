package edu.university.ecs.lab.common.utils;

import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.enums.EndpointTemplate;
import edu.university.ecs.lab.common.models.enums.RestCallTemplate;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.parsers.javaparser.JavaSourceParser;
import edu.university.ecs.lab.common.parsers.javaparser.SpringAnnotationSupport;
import java.io.File;
import java.util.*;
import org.json.JSONObject;

/**
 * Backward-compatible Java parsing facade and stateless AST-to-IR helpers. Each file owns its
 * parser, compilation unit and symbol solver; these helpers never consult a previous file's state
 * or mutate StaticJavaParser configuration.
 */
public class SourceToObjectUtils {
  /**
   * @deprecated Use the static parsing methods. This no-op constructor remains for compatibility
   *     with callers compiled against the original utility class.
   */
  @Deprecated(since = "1.3", forRemoval = false)
  public SourceToObjectUtils() {}

  /** Legacy single-container API. New callers should use {@link #parseClasses}. */
  public static AbstractClass parseClass(
      Microservice microservice,
      File sourceFile,
      RepositoryConfig config,
      Boolean filterOutUnknownClassRoles) {
    List<AbstractClass> classes =
        parseClasses(
            microservice, sourceFile, config, Boolean.TRUE.equals(filterOutUnknownClassRoles));
    return classes.isEmpty() ? null : classes.getFirst();
  }

  /** All named top-level and member declarations from one physical source file. */
  public static List<AbstractClass> parseClasses(
      Microservice microservice,
      File sourceFile,
      RepositoryConfig config,
      boolean filterOutUnknownClassRoles) {
    if (sourceFile == null || FileUtils.isConfigurationFile(sourceFile.getPath())) return List.of();
    return new JavaSourceParser(sourceFile, config).parse(microservice, filterOutUnknownClassRoles);
  }

  public static Set<Import> parseImports(
      edu.university.ecs.lab.common.models.ir.Node parent, List<ImportDeclaration> declarations) {
    Set<Import> imports = new LinkedHashSet<>();
    for (ImportDeclaration declaration : declarations) imports.add(new Import(parent, declaration));
    return imports;
  }

  public static Set<Method> parseMethods(
      edu.university.ecs.lab.common.models.ir.Node parent,
      List<MethodDeclaration> declarations,
      AnnotationExpr requestMapping) {
    Set<Method> methods = new LinkedHashSet<>();
    for (MethodDeclaration declaration : declarations) {
      Method method = new Method(parent, declaration);
      methods.add(convertValidEndpoints(declaration, method, requestMapping));
    }
    return methods;
  }

  /** Preserve the historical first-mapping API; only literal mappings become endpoints. */
  public static Method convertValidEndpoints(
      MethodDeclaration declaration, Method method, AnnotationExpr requestMapping) {
    for (AnnotationExpr annotation : declaration.getAnnotations()) {
      if (!EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(
          SpringAnnotationSupport.simpleName(annotation))) continue;
      Optional<String> prefix = SpringAnnotationSupport.mappingPath(requestMapping);
      Optional<String> suffix = SpringAnnotationSupport.mappingPath(annotation);
      if (prefix.isEmpty() || suffix.isEmpty()) return method;
      AnnotationExpr normalized = annotation.clone();
      normalized.setName(SpringAnnotationSupport.simpleName(annotation));
      EndpointTemplate template = new EndpointTemplate(null, normalized);
      return new Endpoint(
          method,
          EndpointTemplate.simplifyEndpointURL(
              SpringAnnotationSupport.joinPath(prefix.get(), suffix.get())),
          template.getHttpMethod());
    }
    return method;
  }

  public static List<MethodCall> parseMethodCalls(
      edu.university.ecs.lab.common.models.ir.Node parent, List<MethodCallExpr> expressions) {
    List<MethodCall> calls = new ArrayList<>();
    for (MethodCallExpr expression : expressions) {
      TypeIdentity identity = callingType(expression);
      MethodCall call = new MethodCall(parent, expression, identity.shortName());
      JSONObject metadata = new JSONObject().put("objectTypeResolution", identity.resolution());
      if (!identity.qualifiedName().isEmpty())
        metadata.put("qualifiedObjectType", identity.qualifiedName());
      if ("unresolved".equals(identity.resolution()) && !identity.shortName().isEmpty())
        metadata.put("unresolvedTypeHint", identity.shortName());
      call.setMetadata(metadata);
      calls.add(convertValidRestCalls(expression, call));
    }
    return calls;
  }

  /** REST URL interpretation uses the call's own AST, never another file's compilation unit. */
  public static MethodCall convertValidRestCalls(MethodCallExpr expression, MethodCall call) {
    if (!RestCallTemplate.REST_OBJECTS.contains(call.getObjectType())
        || !RestCallTemplate.REST_METHODS.contains(expression.getNameAsString())) return call;
    var compilationUnit = expression.findCompilationUnit();
    if (compilationUnit.isEmpty()) return call;
    RestCallTemplate template = new RestCallTemplate(expression, call, compilationUnit.get());
    if (template.getUrl().isEmpty()) return call;
    RestCall converted = new RestCall(call, template.getUrl(), template.getHttpMethod());
    if (call.getMetadata() != null)
      converted.setMetadata(new JSONObject(call.getMetadata().toString()));
    return converted;
  }

  /**
   * Legacy objectType remains a short name; qualified identity and uncertainty are additive
   * metadata.
   */
  private static TypeIdentity callingType(MethodCallExpr expression) {
    var scope = expression.getScope();
    if (scope.isEmpty()) return new TypeIdentity("", "", "implicit");
    try {
      var type = scope.get().calculateResolvedType();
      String qualified =
          type.isReferenceType() ? type.asReferenceType().getQualifiedName() : type.describe();
      return new TypeIdentity(shortName(qualified), qualified, "resolved");
    } catch (UnsolvedSymbolException unresolved) {
      String hint = Objects.toString(unresolved.getName(), "");
      // Exception messages can include arbitrary source text; only retain a bounded identifier
      // hint.
      if (hint.length() > 256 || !hint.matches("[\\p{L}_$][\\p{L}\\p{N}_$.]*")) hint = "";
      return new TypeIdentity(shortName(hint), "", "unresolved");
    } catch (RuntimeException unresolved) {
      return new TypeIdentity("", "", "unresolved");
    }
  }

  private static String shortName(String qualified) {
    return qualified.substring(qualified.lastIndexOf('.') + 1);
  }

  private record TypeIdentity(String shortName, String qualifiedName, String resolution) {}

  public static Set<Annotation> parseAnnotations(
      edu.university.ecs.lab.common.models.ir.Node parent, Iterable<AnnotationExpr> expressions) {
    Set<Annotation> annotations = new LinkedHashSet<>();
    for (AnnotationExpr expression : expressions)
      annotations.add(new Annotation(parent, expression));
    return annotations;
  }

  public static ConfigFile parseConfigurationFile(
      File file, RepositoryConfig config, Microservice microservice) {
    String name = file.getName();
    if (name.endsWith(".yml") || name.endsWith(".yaml"))
      return NonJsonReadWriteUtils.readFromYaml(file.getPath(), config, microservice);
    if (name.equals("Dockerfile") || name.equals("DockerFile"))
      return NonJsonReadWriteUtils.readFromDocker(file.toPath(), config, microservice);
    if (name.equals("pom.xml"))
      return NonJsonReadWriteUtils.readFromPom(file.toPath(), config, microservice);
    if (Set.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        .contains(name))
      return NonJsonReadWriteUtils.readFromGradle(file.toPath(), config, microservice);
    if (name.endsWith(".properties"))
      return NonJsonReadWriteUtils.readFromProperties(file.toPath(), config, microservice);
    return null;
  }
}
