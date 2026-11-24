package edu.university.ecs.lab.common.parsers.treesitter;

import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.CONFIG;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.STRING_TYPES;
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
 * C# declarations, ASP.NET route attributes/minimal APIs and supported HTTP/gRPC source patterns.
 * Rules are stateless; all per-file output and source bytes live in {@link FactOutput}.
 */
final class CSharpFactExtractor implements LanguageFactExtractor {
  private static final Pattern CS_HTTP_ATTR =
      Pattern.compile("Http(Get|Post|Put|Delete|Patch|Head|Options)");
  private static final Pattern CS_MINIMAL_API =
      Pattern.compile("Map(Get|Post|Put|Delete|Patch|Methods)?");
  private static final Pattern CS_HTTP_CLIENT =
      Pattern.compile(
          "(Get|Post|Put|Delete|Patch|Send)(String|Stream|ByteArray|AsJson|FromJson)?Async");

  @Override
  public void extract(SNode root, FactOutput out) {
    root.walk(
        n -> {
          switch (n.type) {
            case "class_declaration",
                "interface_declaration",
                "record_declaration",
                "struct_declaration",
                "enum_declaration" -> {
              String typeName = out.text(n.field("name"));
              out.add("types", n.type.replace("_declaration", "") + " " + typeName);
              List<String> supertypes = csharpSupertypes(n, out);
              if (n.type.equals("class_declaration")) {
                csharpController(n, out);
                String role =
                    CONFIG.classifyRole(
                        "csharp", typeName, csharpAttributeNames(n, out), supertypes);
                out.add("classRole", typeName + ":" + role);
              }
              if (n.type.equals("class_declaration") || n.type.equals("struct_declaration")) {
                csharpFields(n, out).forEach(f -> out.add("fields", typeName + "." + f));
              }
              csharpGrpcImpl(supertypes, out);
            }
            case "method_declaration" -> {
              String name = csharpQualifiedMethodName(n, out);
              out.add("functions", name);
              out.add("parameters", name + "(" + csharpParamList(n.field("parameters"), out) + ")");
            }
            case "using_directive" -> {
              SNode name = n.namedChildCount() > 0 ? n.namedChild(n.namedChildCount() - 1) : null;
              if (name != null) out.add("imports", out.text(name));
            }
            case "attribute" -> out.add("annotations", out.text(n));
            case "invocation_expression" -> csharpCall(n, out);
            case "object_creation_expression" -> {
              // gRPC client: new Pkg.CartService.CartServiceClient(channel)
              String type = out.text(n.field("type"));
              String simple = lastSegment(type);
              if (simple.endsWith("Client") && type.contains(".") && !simple.equals("HttpClient")) {
                out.add("rpcClients", simple.substring(0, simple.length() - "Client".length()));
              }
            }
            default -> {}
          }
        });
  }

  /** ASP.NET MVC controllers: [Route] on the class + [HttpGet("...")] on actions. */
  private void csharpController(SNode cls, FactOutput out) {
    String className = out.text(cls.field("name"));
    String prefix = attributeArg(cls, "Route", out);
    if (prefix != null)
      prefix = prefix.replace("[controller]", className.replaceAll("Controller$", ""));
    SNode body = cls.field("body");

    if (body == null) return;

    for (SNode m : body.namedChildren()) {
      if (!m.type.equals("method_declaration")) continue;
      for (SNode list : m.namedChildren()) {
        if (!list.type.equals("attribute_list")) continue;
        for (SNode attr : list.namedChildren()) {
          Matcher verb = CS_HTTP_ATTR.matcher(out.text(attr.field("name")));
          if (!verb.matches()) continue;
          String template = firstAttributeArg(attr, out);
          String url;
          if (template.startsWith("/") || template.startsWith("~/")) {
            // ASP.NET action templates beginning with / or ~/ override the controller prefix.
            url = template.startsWith("~/") ? template.substring(1) : template;
          } else {
            url =
                "/"
                    + String.join(
                        "/",
                        java.util.stream.Stream.of(prefix, template)
                            .filter(s -> s != null && !s.isEmpty())
                            .map(s -> s.replaceAll("^/|/$", ""))
                            .toList());
          }
          out.add(
              "endpoints",
              verb.group(1).toUpperCase()
                  + " "
                  + url
                  + " -> "
                  + className
                  + "."
                  + out.text(m.field("name")));
        }
      }
    }
  }

  /** gRPC service implementations derive from the generated Foo.FooBase class. */
  private void csharpGrpcImpl(List<String> supertypes, FactOutput out) {
    for (String simple : supertypes) {
      if (simple.endsWith("Base") && simple.length() > 4 && !simple.equals("ControllerBase")) {
        out.add("rpcServers", simple.substring(0, simple.length() - 4));
      }
    }
  }

  private static List<String> csharpSupertypes(SNode decl, FactOutput out) {
    SNode bases = decl.firstChildOfType("base_list");
    if (bases == null) return List.of();
    List<String> names = new ArrayList<>();
    for (SNode b : bases.namedChildren()) names.add(lastSegment(out.text(b)));

    return names;
  }

  private static List<String> csharpAttributeNames(SNode decl, FactOutput out) {
    List<String> names = new ArrayList<>();
    for (SNode list : decl.namedChildren()) {
      if (!list.type.equals("attribute_list")) continue;
      for (SNode attr : list.namedChildren()) names.add(out.text(attr.field("name")));
    }

    return names;
  }

  private static String attributeArg(SNode decl, String attributeName, FactOutput out) {
    for (SNode list : decl.namedChildren()) {
      if (!list.type.equals("attribute_list")) continue;
      for (SNode attr : list.namedChildren()) {
        if (out.text(attr.field("name")).equals(attributeName)) return firstAttributeArg(attr, out);
      }
    }

    return null;
  }

  private static String firstAttributeArg(SNode attr, FactOutput out) {
    SNode args = attr.firstChildOfType("attribute_argument_list");
    if (args == null || args.namedChildCount() == 0) return "";
    return urlOf(args.namedChild(0), out);
  }

  private void csharpCall(SNode call, FactOutput out) {
    SNode fn = call.field("function");
    if (fn == null || !fn.type.equals("member_access_expression")) return;
    SNode nameNode = fn.field("name");
    String name =
        nameNode == null
            ? ""
            : out.text(nameNode.type.equals("generic_name") ? nameNode.namedChild(0) : nameNode);
    List<SNode> args = args(call, out);
    String caller = csharpEnclosingName(call, out);

    if (name.equals("MapGrpcService") && nameNode.type.equals("generic_name")) {
      out.add(
          "rpcServers",
          caller + ": " + lastSegment(out.text(nameNode.namedChild(1)).replaceAll("[<>]", "")));
      return;
    }

    Matcher minimal = CS_MINIMAL_API.matcher(name);
    if (minimal.matches() && !args.isEmpty()) {
      String url = urlOf(args.get(0), out);
      if (looksLikePath(url)) {
        out.add(
            "endpoints",
            (minimal.group(1) == null ? "ANY" : minimal.group(1).toUpperCase()) + " " + url);
        return;
      }
    }

    if (CS_HTTP_CLIENT.matcher(name).matches() && !args.isEmpty()) {
      SNode first =
          args.get(0).namedChildCount() > 0
              ? args.get(0).namedChild(args.get(0).namedChildCount() - 1)
              : null;
      // Only string-ish first arguments: filters out cache.GetAsync(key) and similar
      if (first != null
          && (STRING_TYPES.contains(first.type) || first.type.equals("binary_expression"))) {
        out.add(
            "httpCalls",
            caller
                + ": "
                + name.replaceAll("(String|Stream|ByteArray|AsJson|FromJson)?Async$", "")
                    .toUpperCase()
                + " "
                + urlOf(first, out));
      }
    }
  }

  private static String csharpQualifiedMethodName(SNode methodDecl, FactOutput out) {
    String name = out.text(methodDecl.field("name"));
    SNode owner =
        methodDecl.ancestorOfType(
            "class_declaration", "struct_declaration", "interface_declaration");
    return owner == null ? name : out.text(owner.field("name")) + "." + name;
  }

  private static String csharpEnclosingName(SNode node, FactOutput out) {
    SNode fn =
        node.ancestorOfType(
            "method_declaration", "constructor_declaration", "local_function_statement");
    if (fn == null) return "<module>";
    if (fn.type.equals("method_declaration")) return csharpQualifiedMethodName(fn, out);
    SNode name = fn.field("name");
    return name != null ? out.text(name) : "<anonymous>";
  }

  /**
   * Plain fields and auto-properties directly on a class/struct body (not inherited, not local
   * variables).
   */
  private static List<String> csharpFields(SNode decl, FactOutput out) {
    SNode body = decl.field("body");
    if (body == null) return List.of();
    List<String> fields = new ArrayList<>();
    for (SNode m : body.namedChildren()) {
      if (m.type.equals("field_declaration")) {
        SNode varDecl = m.firstChildOfType("variable_declaration");
        if (varDecl == null) continue;
        SNode type = varDecl.field("type");
        for (SNode declarator : varDecl.namedChildren()) {
          if (!declarator.type.equals("variable_declarator")) continue;
          SNode name = declarator.field("name");
          if (name != null) fields.add(out.text(name) + ":" + (type == null ? "" : out.text(type)));
        }
      } else if (m.type.equals("property_declaration")) {
        SNode name = m.field("name");
        SNode type = m.field("type");
        if (name != null) fields.add(out.text(name) + ":" + (type == null ? "" : out.text(type)));
      }
    }

    return fields;
  }

  private static String csharpParamList(SNode params, FactOutput out) {
    if (params == null) return "";
    List<String> parts = new ArrayList<>();
    for (SNode p : params.namedChildren()) {
      if (!p.type.equals("parameter")) continue;
      SNode type = p.field("type");
      SNode name = p.field("name");
      parts.add((type == null ? "" : out.text(type)) + (name != null ? " " + out.text(name) : ""));
    }

    return String.join(", ", parts);
  }
}
