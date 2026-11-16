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

/**
 * Legacy tree-sitter JavaScript facts; the default JS/TS registry uses the TypeScript adapter.
 * Rules are stateless; all per-file output and source bytes live in {@link FactOutput}.
 */
final class JavaScriptFactExtractor implements LanguageFactExtractor {
  @Override
  public void extract(SNode root, FactOutput out) {
    root.walk(
        n -> {
          switch (n.type) {
            case "class_declaration", "class" -> {
              SNode name = n.field("name");
              if (name != null) {
                String className = out.text(name);
                out.add("types", "class " + className);
                String role =
                    CONFIG.classifyRole("javascript", className, List.of(), jsSupertypes(n, out));
                out.add("classRole", className + ":" + role);
                jsClassFields(n, out).forEach(f -> out.add("fields", className + "." + f));
              }
            }
            case "function_declaration", "generator_function_declaration", "method_definition" -> {
              // method_definition is only ever valid directly inside a class body, so it always
              // gets qualified; function_declaration is a top-level statement so it never is.
              String name =
                  n.type.equals("method_definition")
                      ? jsQualifiedMethodName(n, out)
                      : out.text(n.field("name"));
              out.add("functions", name);
              out.add("parameters", name + "(" + jsParamList(n.field("parameters"), out) + ")");
            }
            case "variable_declarator" -> {
              SNode v = n.field("value");
              if (v != null
                  && (v.type.equals("arrow_function")
                      || v.type.equals("function_expression")
                      || v.type.equals("function"))) {
                String name = out.text(n.field("name"));
                out.add("functions", name);
                out.add("parameters", name + "(" + jsParamList(v.field("parameters"), out) + ")");
              }
            }
            case "import_statement" -> out.add("imports", urlOf(n.field("source"), out));
            case "decorator" -> out.add("annotations", out.text(n).substring(1));
            case "call_expression" -> jsCall(n, out);
            case "new_expression" -> {
              // gRPC client stubs: new proto.pkg.SomeService(address, credentials)
              SNode ctor = n.field("constructor");
              if (ctor != null && ctor.type.equals("member_expression")) {
                String name = out.text(ctor.field("property"));
                if (name.endsWith("Service") || name.endsWith("Client"))
                  out.add("rpcClients", name);
              }
            }
            default -> {}
          }
        });
  }

  private void jsCall(SNode call, FactOutput out) {
    SNode fn = call.field("function");
    List<SNode> args = args(call, out);
    if (fn == null) return;
    String caller = jsEnclosingName(call, out);
    if (fn.type.equals("identifier")) {
      String name = out.text(fn);
      if (name.equals("require") && !args.isEmpty()) out.add("imports", urlOf(args.get(0), out));
      String httpMethod = CONFIG.httpMethodFor("javascript", name, name); // e.g. bare fetch(url)
      if (httpMethod != null && !args.isEmpty())
        out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
      return;
    }
    if (!fn.type.equals("member_expression")) return;
    String method = out.text(fn.field("property"));
    SNode object = fn.field("object");
    String objectText = out.text(object);

    if (method.equals("addService") && !args.isEmpty()) {
      // server.addService(proto.pkg.CartService.service, impl)
      String svc = out.text(args.get(0)).replaceAll("\\.service$", "");
      out.add("rpcServers", caller + ": " + lastSegment(svc));
      return;
    }
    String httpMethod = CONFIG.httpMethodFor("javascript", objectText, method);
    if (httpMethod != null && !args.isEmpty()) {
      out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
      return;
    }
    if (HTTP_VERBS.contains(method.toLowerCase()) && !args.isEmpty()) {
      String url = urlOf(args.get(0), out);
      if (looksLikePath(url)) out.add("endpoints", method.toUpperCase() + " " + url);
    }
  }

  /**
   * Simple name of the class's extended type, if any (tree-sitter-javascript has no "super" field).
   */
  private static List<String> jsSupertypes(SNode classNode, FactOutput out) {
    SNode heritage = classNode.firstChildOfType("class_heritage");
    if (heritage == null || heritage.namedChildCount() == 0) return List.of();
    return List.of(lastSegment(out.text(heritage.namedChild(0))));
  }

  private static List<String> jsClassFields(SNode classNode, FactOutput out) {
    SNode body = classNode.field("body");
    if (body == null) return List.of();
    List<String> fields = new ArrayList<>();
    for (SNode m : body.namedChildren()) {
      // The TSX grammar (used here) calls this "public_field_definition" with a "name" field;
      // kept the plain-JS grammar's "field_definition"/"property" as a defensive fallback.
      if (!m.type.equals("public_field_definition") && !m.type.equals("field_definition")) continue;
      SNode name = m.field("name") != null ? m.field("name") : m.field("property");
      SNode type = m.field("type"); // only present for TS-annotated fields; empty for plain/Flow JS
      if (name != null) fields.add(out.text(name) + ":" + (type == null ? "" : out.text(type)));
    }
    return fields;
  }

  private static String jsParamList(SNode params, FactOutput out) {
    if (params == null) return "";
    List<String> parts = new ArrayList<>();
    for (SNode p : params.namedChildren()) {
      switch (p.type) {
        // The TSX grammar wraps every parameter in required_parameter/optional_parameter, with
        // the actual name/destructuring under "pattern" and an optional type annotation.
        case "required_parameter", "optional_parameter" -> {
          SNode pattern = p.field("pattern");
          SNode type = p.field("type");
          String name =
              pattern == null
                  ? out.text(p)
                  : pattern.type.equals("rest_pattern")
                      ? "..."
                          + (pattern.namedChildCount() > 0 ? out.text(pattern.namedChild(0)) : "")
                      : out.text(pattern);
          parts.add(name + (type != null ? ":" + out.text(type) : ""));
        }
        // Plain-JS grammar shapes, kept as a defensive fallback.
        case "identifier" -> parts.add(out.text(p));
        case "assignment_pattern" -> {
          SNode left = p.field("left");
          if (left != null) parts.add(out.text(left));
        }
        case "rest_pattern" ->
            parts.add("..." + (p.namedChildCount() > 0 ? out.text(p.namedChild(0)) : ""));
        default ->
            parts.add(out.text(p)); // object/array destructuring etc.: keep the raw pattern text
      }
    }
    return String.join(", ", parts);
  }

  /**
   * "ClassName.methodName" for a class method, else just its own name (method_definition is only
   * ever valid directly inside a class body, so a name field is always present when it applies
   * here).
   */
  private static String jsQualifiedMethodName(SNode methodDef, FactOutput out) {
    String name = out.text(methodDef.field("name"));
    SNode cls = methodDef.ancestorOfType("class_declaration", "class");
    if (cls == null) return name;
    SNode clsName = cls.field("name");
    return clsName == null ? name : out.text(clsName) + "." + name;
  }

  private static String jsEnclosingName(SNode node, FactOutput out) {
    SNode fn =
        node.ancestorOfType(
            "function_declaration",
            "generator_function_declaration",
            "method_definition",
            "arrow_function",
            "function_expression",
            "function");
    if (fn == null) return "<module>";
    if (fn.type.equals("method_definition")) return jsQualifiedMethodName(fn, out);
    SNode name = fn.field("name");
    if (name != null) return out.text(name);
    if (fn.parent != null && fn.parent.type.equals("variable_declarator")) {
      SNode vname = fn.parent.field("name");
      if (vname != null) return out.text(vname);
    }
    return "<anonymous>";
  }
}
