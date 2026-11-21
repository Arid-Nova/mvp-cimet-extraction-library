package edu.university.ecs.lab.common.parsers.treesitter;

import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.CONFIG;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.HTTP_VERBS;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.args;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.lastSegment;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.looksLikePath;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.urlOf;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python declaration, Flask/FastAPI/Django route and supported HTTP/gRPC source patterns. Rules are
 * stateless; all per-file output and source bytes live in {@link FactOutput}.
 */
final class PythonFactExtractor implements LanguageFactExtractor {
  private static final Pattern PY_GRPC_SERVER = Pattern.compile("add_(\\w+?)Servicer_to_server");
  private static final Pattern PY_GRPC_STUB = Pattern.compile("(\\w+)Stub");

  @Override
  public void extract(SNode root, FactOutput out) {
    root.walk(
        n -> {
          switch (n.type) {
            case "class_definition" -> {
              String className = out.text(n.field("name"));
              out.add("types", "class " + className);
              String role =
                  CONFIG.classifyRole(
                      "python", className, pyDecoratorNames(n, out), pySupertypes(n, out));
              out.add("classRole", className + ":" + role);
              pyClassFields(n, out).forEach(f -> out.add("fields", className + "." + f));
            }
            case "function_definition" -> {
              // Prefixed "ClassName.methodName" when it's a direct class method (any nesting depth
              // in between still resolves to the right owner, since ancestorOfType finds the
              // nearest
              // enclosing class regardless), bare name otherwise - lets the converter attribute
              // methods/fields/calls to the class that actually owns them when a file has several.
              String name = pyQualifiedFunctionName(n, out);
              out.add("functions", name);
              out.add("parameters", name + "(" + pyParamList(n.field("parameters"), out) + ")");
            }
            case "import_statement" ->
                n.fields("name")
                    .forEach(
                        x ->
                            out.add(
                                "imports",
                                out.text(x.type.equals("aliased_import") ? x.field("name") : x)));
            case "import_from_statement" -> {
              String module = out.text(n.field("module_name"));
              List<SNode> names = n.fields("name");
              if (names.isEmpty()) out.add("imports", module + ".*");
              names.forEach(
                  x ->
                      out.add(
                          "imports",
                          module
                              + "."
                              + out.text(x.type.equals("aliased_import") ? x.field("name") : x)));
            }
            case "decorator" -> {
              out.add("annotations", out.text(n).substring(1).trim());
              pyRouteDecorator(n, out);
            }
            case "call" -> pyCall(n, out);
            default -> {}
          }
        });
  }

  private void pyRouteDecorator(SNode decorator, FactOutput out) {
    SNode call = decorator.namedChildCount() > 0 ? decorator.namedChild(0) : null;
    if (call == null || !call.type.equals("call")) return;
    SNode fn = call.field("function");
    if (fn == null || !fn.type.equals("attribute")) return;
    String attr = out.text(fn.field("attribute"));
    List<SNode> args = args(call, out);
    if (args.isEmpty()) return;
    String url = urlOf(args.get(0), out);
    if (!looksLikePath(url)) return;

    String method;
    if (attr.equals("route") || attr.equals("api_route")) {
      method = "GET"; // Flask default
      for (SNode a : args) {
        if (a.type.equals("keyword_argument") && out.text(a.field("name")).equals("methods")) {
          method = out.text(a.field("value")).replaceAll("[\\[\\]\"' ]", "").toUpperCase();
        }
      }
    } else if (HTTP_VERBS.contains(attr)) {
      method = attr.toUpperCase();
    } else {
      return;
    }
    SNode def = decorator.parent == null ? null : decorator.parent.field("definition");
    out.add(
        "endpoints",
        method + " " + url + (def == null ? "" : " -> " + pyQualifiedFunctionName(def, out)));
  }

  private void pyCall(SNode call, FactOutput out) {
    SNode fn = call.field("function");
    if (fn == null) return;
    List<SNode> args = args(call, out);
    String callee = fn.type.equals("attribute") ? out.text(fn.field("attribute")) : out.text(fn);
    String caller = pyEnclosingQualifiedName(call, out);

    Matcher server = PY_GRPC_SERVER.matcher(callee);
    if (server.matches()) {
      out.add("rpcServers", caller + ": " + server.group(1));
      return;
    }

    Matcher stub = PY_GRPC_STUB.matcher(callee);
    if (stub.matches()) {
      out.add("rpcClients", caller + ": " + stub.group(1));
      return;
    }

    if (fn.type.equals("identifier")
        && (callee.equals("path") || callee.equals("re_path"))
        && !args.isEmpty()) {
      out.add("endpoints", "ANY /" + urlOf(args.get(0), out)); // Django urls.py
      return;
    }

    if (fn.type.equals("attribute")) {
      String object = out.text(fn.field("object"));
      // requests.get(...), self.client.get(...) (Locust / test clients), session.post(...)
      String httpMethod = CONFIG.httpMethodFor("python", lastSegment(object), callee);
      if (httpMethod != null && !args.isEmpty()) {
        out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
      } else if (object.endsWith("request") && callee.equals("urlopen") && !args.isEmpty()) {
        out.add("httpCalls", caller + ": ANY " + urlOf(args.get(0), out));
      }
    }
  }

  /**
   * "ClassName.functionName" if def is a direct method of some class (at any nesting depth), else
   * just its own name.
   */
  private static String pyQualifiedFunctionName(SNode functionDef, FactOutput out) {
    String name = out.text(functionDef.field("name"));
    SNode cls = functionDef.ancestorOfType("class_definition");
    return cls == null ? name : out.text(cls.field("name")) + "." + name;
  }

  private static String pyEnclosingQualifiedName(SNode node, FactOutput out) {
    SNode fn = node.ancestorOfType("function_definition");
    return fn == null ? "<module>" : pyQualifiedFunctionName(fn, out);
  }

  /**
   * Direct class-level attributes, e.g. "name: str" or "age: int = 0" - not instance fields set via
   * self.x.
   */
  private static List<String> pyClassFields(SNode classNode, FactOutput out) {
    SNode body = classNode.field("body");
    if (body == null) return List.of();
    List<String> fields = new ArrayList<>();
    for (SNode stmt : body.namedChildren()) {
      if (!stmt.type.equals("expression_statement") || stmt.namedChildCount() == 0) continue;
      SNode assign = stmt.namedChild(0);
      if (assign == null || !assign.type.equals("assignment")) continue;
      SNode left = assign.field("left");
      if (left == null || !left.type.equals("identifier"))
        continue; // skips self.x = ... (an "attribute", not identifier)
      SNode type = assign.field("type");
      fields.add(out.text(left) + ":" + (type == null ? "" : out.text(type)));
    }

    return fields;
  }

  private static String pyParamList(SNode params, FactOutput out) {
    if (params == null) return "";
    List<String> parts = new ArrayList<>();
    for (SNode p : params.namedChildren()) {
      switch (p.type) {
        case "identifier" -> parts.add(out.text(p)); // untyped, e.g. self
        case "typed_parameter" -> {
          SNode inner = p.namedChildCount() > 0 ? p.namedChild(0) : null;
          String type = p.field("type") != null ? out.text(p.field("type")) : "";
          if (inner == null) break;
          String prefix =
              inner.type.equals("list_splat_pattern")
                  ? "*"
                  : inner.type.equals("dictionary_splat_pattern") ? "**" : "";
          SNode name =
              prefix.isEmpty()
                  ? inner
                  : (inner.namedChildCount() > 0 ? inner.namedChild(0) : inner);
          parts.add((type.isEmpty() ? "" : type + " ") + prefix + out.text(name));
        }
        case "default_parameter" -> parts.add(out.text(p.field("name")));
        case "typed_default_parameter" -> {
          String type = p.field("type") != null ? out.text(p.field("type")) : "";
          parts.add((type.isEmpty() ? "" : type + " ") + out.text(p.field("name")));
        }
        case "list_splat_pattern" ->
            parts.add("*" + (p.namedChildCount() > 0 ? out.text(p.namedChild(0)) : ""));
        case "dictionary_splat_pattern" ->
            parts.add("**" + (p.namedChildCount() > 0 ? out.text(p.namedChild(0)) : ""));
        default -> {}
      }
    }

    return String.join(", ", parts);
  }

  /**
   * Decorator names on a class, e.g. ["app.route"] for @app.route("/x") — empty if the class isn't
   * decorated.
   */
  private static List<String> pyDecoratorNames(SNode classNode, FactOutput out) {
    SNode parent = classNode.parent;
    if (parent == null || !parent.type.equals("decorated_definition")) return List.of();
    
    List<String> names = new ArrayList<>();
    for (SNode d : parent.namedChildren()) {
      if (!d.type.equals("decorator") || d.namedChildCount() == 0) continue;
      SNode expr = d.namedChild(0);
      SNode fn = expr.type.equals("call") ? expr.field("function") : expr;
      if (fn != null) names.add(out.text(fn));
    }

    return names;
  }

  /** Simple names of a class's base classes, e.g. ["Model"] for class User(db.Model). */
  private static List<String> pySupertypes(SNode classNode, FactOutput out) {
    SNode bases = classNode.field("superclasses");
    if (bases == null) return List.of();
    
    List<String> names = new ArrayList<>();
    for (SNode c : bases.namedChildren()) {
      if (c.type.equals("keyword_argument")) continue; // e.g. class Meta(metaclass=...)
      names.add(lastSegment(out.text(c)));
    }

    return names;
  }
}
