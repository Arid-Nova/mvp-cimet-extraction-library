package edu.university.ecs.lab.common.parsers.javaparser;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.*;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.EndpointTemplate;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import java.util.*;
import org.json.JSONObject;

/**
 * Bounded Spring source observations. Annotation attributes are read structurally; unresolved
 * expressions are not evaluated. Name-based framework recognition and default repository naming
 * remain explicitly qualified source heuristics.
 */
public final class SpringAnnotationSupport {
  private SpringAnnotationSupport() {}

  public static String simpleName(AnnotationExpr annotation) {
    String name = annotation.getNameAsString();
    return name.substring(name.lastIndexOf('.') + 1);
  }

  private static Optional<AnnotationExpr> annotation(
      Iterable<AnnotationExpr> annotations, String name) {
    for (AnnotationExpr candidate : annotations)
      if (simpleName(candidate).equals(name)) return Optional.of(candidate);
    return Optional.empty();
  }

  public static ClassRole role(TypeDeclaration<?> declaration) {
    Map<String, ClassRole> roles = new LinkedHashMap<>();
    roles.put("FeignClient", ClassRole.FEIGN_CLIENT);
    roles.put("RepositoryRestResource", ClassRole.REP_REST_RSC);
    roles.put("RestController", ClassRole.CONTROLLER);
    roles.put("Controller", ClassRole.CONTROLLER);
    roles.put("Service", ClassRole.SERVICE);
    roles.put("Repository", ClassRole.REPOSITORY);
    roles.put("Entity", ClassRole.ENTITY);
    roles.put("Embeddable", ClassRole.ENTITY);
    for (var role : roles.entrySet())
      if (annotation(declaration.getAnnotations(), role.getKey()).isPresent())
        return role.getValue();
    if (declaration instanceof ClassOrInterfaceDeclaration named
        && named.getExtendedTypes().stream()
            .anyMatch(
                type ->
                    Set.of("MongoRepository", "CrudRepository").contains(type.getNameAsString())))
      return ClassRole.REPOSITORY;
    return ClassRole.UNKNOWN;
  }

  public static Set<Method> methods(AbstractClass owner, TypeDeclaration<?> declaration) {
    AnnotationExpr classMapping =
        annotation(declaration.getAnnotations(), "RequestMapping").orElse(null);
    List<MethodDeclaration> methods = declaration.getMethods();
    if (owner.getClassRole() == ClassRole.FEIGN_CLIENT)
      return feignMethods(owner, declaration, methods, classMapping);
    if (owner.getClassRole() == ClassRole.REP_REST_RSC)
      return repositoryMethods(owner, declaration, methods);
    Set<Method> result = SourceToObjectUtils.parseMethods(owner, methods, classMapping);
    for (MethodDeclaration method : methods) {
      boolean mapping =
          method.getAnnotations().stream()
              .anyMatch(item -> EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(simpleName(item)));
      if (mapping
          && (mappingPath(classMapping).isEmpty()
              || method.getAnnotations().stream()
                  .filter(item -> EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(simpleName(item)))
                  .anyMatch(item -> mappingPath(item).isEmpty())))
        partial(owner, "Dynamic or multiple Spring route paths are not evaluated.");
    }
    return result;
  }

  private static Set<Method> feignMethods(
      AbstractClass owner,
      TypeDeclaration<?> declaration,
      List<MethodDeclaration> declarations,
      AnnotationExpr classMapping) {
    AnnotationExpr client = annotation(declaration.getAnnotations(), "FeignClient").orElseThrow();
    Optional<String> prefix = literalAttribute(client, "path");
    if (prefix.isEmpty())
      partial(owner, "Dynamic Feign path is unresolved; no concrete remote route is invented.");
    Set<Method> methods = new LinkedHashSet<>();
    for (MethodDeclaration declarationMethod : declarations) {
      Method method = new Method(owner, declarationMethod);
      Method mapped =
          SourceToObjectUtils.convertValidEndpoints(declarationMethod, method, classMapping);
      if (prefix.isPresent() && mapped instanceof Endpoint endpoint) {
        String query = queryParameters(declarationMethod);
        MethodCall call = new MethodCall(method, "exchange", method.getLocation());
        call.setCalledFrom(method.getName());
        call.setObjectType("RestCallTemplate");
        call.setObjectName("restCallTemplate");
        RestCall remote =
            new RestCall(
                call,
                EndpointTemplate.simplifyEndpointURL(joinPath(prefix.get(), endpoint.getUrl()))
                    + query,
                endpoint.getHttpMethod());
        JSONObject evidence =
            new JSONObject()
                .put("remoteClientKind", "feign")
                .put("pathResolution", "literal-source");
        literalAttribute(client, "name", "value")
            .filter(value -> !value.isEmpty())
            .ifPresent(value -> evidence.put("configuredServiceName", value));
        remote.setMetadata(evidence);
        method.getMethodCalls().add(remote);
      }
      methods.add(method);
    }
    return methods;
  }

  private static String queryParameters(MethodDeclaration declaration) {
    List<String> query = new ArrayList<>();
    for (var parameter : declaration.getParameters()) {
      Optional<AnnotationExpr> request = annotation(parameter.getAnnotations(), "RequestParam");
      if (request.isEmpty()) continue;
      Optional<String> name = literalAttribute(request.get(), "name", "value");
      if (name.isPresent())
        query.add((name.get().isEmpty() ? parameter.getNameAsString() : name.get()) + "={?}");
    }
    return query.isEmpty() ? "" : "?" + String.join("&", query);
  }

  private static Set<Method> repositoryMethods(
      AbstractClass owner, TypeDeclaration<?> declaration, List<MethodDeclaration> declarations) {
    AnnotationExpr repository =
        annotation(declaration.getAnnotations(), "RepositoryRestResource").orElseThrow();
    Optional<Boolean> classExported = booleanAttribute(repository, "exported");
    Optional<String> configuredPath = literalAttribute(repository, "path");
    boolean convention = configuredPath.isPresent() && configuredPath.get().isEmpty();
    String path = configuredPath.orElse("");
    if (convention) {
      path =
          declaration.getNameAsString().replaceFirst("Repository$", "").toLowerCase(Locale.ROOT)
              + "s";
      partial(owner, "Default repository collection naming is a legacy unverified convention.");
    }
    if (classExported.isEmpty() || configuredPath.isEmpty())
      partial(owner, "Dynamic repository export/path attributes are unresolved.");
    Set<Method> methods = new LinkedHashSet<>();
    for (MethodDeclaration methodDeclaration : declarations) {
      Method method = new Method(owner, methodDeclaration);
      AnnotationExpr resource =
          annotation(methodDeclaration.getAnnotations(), "RestResource").orElse(null);
      Optional<Boolean> exported = booleanAttribute(resource, "exported");
      Optional<String> methodPath = literalAttribute(resource, "path");
      if (exported.isEmpty() || methodPath.isEmpty())
        partial(owner, "Dynamic repository method export/path attributes are unresolved.");
      if (classExported.orElse(false)
          && exported.orElse(false)
          && configuredPath.isPresent()
          && methodPath.isPresent()) {
        String queryPath = methodPath.get().isEmpty() ? method.getName() : methodPath.get();
        Endpoint endpoint =
            new Endpoint(method, joinPath(path, "search/" + queryPath), HttpMethod.GET);
        endpoint.setMetadata(
            new JSONObject()
                .put(
                    "repositoryPathResolution",
                    convention ? "convention-unverified" : "literal-source"));
        methods.add(endpoint);
      } else {
        // Retain source methods without claiming that an unexported/unknown route is exposed.
        methods.add(method);
      }
    }
    return methods;
  }

  private static Optional<Boolean> booleanAttribute(AnnotationExpr annotation, String name) {
    Optional<Expression> value = attribute(annotation, name);
    if (value.isEmpty()) return Optional.of(true);
    return value.get().isBooleanLiteralExpr()
        ? Optional.of(value.get().asBooleanLiteralExpr().getValue())
        : Optional.empty();
  }

  /** Empty means unresolved, whereas an absent attribute has its literal empty-string default. */
  public static Optional<String> literalAttribute(AnnotationExpr annotation, String... names) {
    for (String name : names) {
      Optional<Expression> value = attribute(annotation, name);
      if (value.isPresent()) return literal(value.get());
    }
    return Optional.of("");
  }

  private static Optional<Expression> attribute(AnnotationExpr annotation, String name) {
    if (annotation instanceof SingleMemberAnnotationExpr single && name.equals("value"))
      return Optional.of(single.getMemberValue());
    if (annotation instanceof NormalAnnotationExpr normal)
      for (MemberValuePair pair : normal.getPairs())
        if (pair.getNameAsString().equals(name)) return Optional.of(pair.getValue());
    return Optional.empty();
  }

  private static Optional<String> literal(Expression expression) {
    if (expression.isStringLiteralExpr())
      return Optional.of(expression.asStringLiteralExpr().asString());
    if (expression.isArrayInitializerExpr()
        && expression.asArrayInitializerExpr().getValues().size() == 1)
      return literal(expression.asArrayInitializerExpr().getValues().get(0));
    return Optional.empty();
  }

  public static Optional<String> mappingPath(AnnotationExpr mapping) {
    return literalAttribute(mapping, "path", "value");
  }

  public static String joinPath(String prefix, String suffix) {
    String path = (prefix + "/" + suffix).replaceAll("/{2,}", "/");
    if (!path.startsWith("/")) path = "/" + path;
    return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  private static void partial(AbstractClass owner, String reason) {
    owner.getMetadata().put("springCoverage", "partial");
    owner.getMetadata().put("springLimitation", reason);
  }
}
