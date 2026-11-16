package edu.university.ecs.lab.common.parsers.treesitter;

import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.CONFIG;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.args;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.looksLikePath;
import static edu.university.ecs.lab.common.parsers.treesitter.FactSupport.urlOf;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go declarations, net/http/router registrations and supported HTTP/gRPC source patterns. Rules are
 * stateless; all per-file output and source bytes live in {@link FactOutput}.
 */
final class GoFactExtractor implements LanguageFactExtractor {
  private static final Pattern GO_HTTP_METHOD =
      Pattern.compile(
          "Method(Get|Post|Put|Delete|Patch|Head|Options)|\"(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\"");
  private static final Set<String> GO_ROUTE_FUNCS =
      Set.of(
          "HandleFunc",
          "Handle",
          "Get",
          "Post",
          "Put",
          "Delete",
          "Patch",
          "Head",
          "Options",
          "GET",
          "POST",
          "PUT",
          "DELETE",
          "PATCH",
          "HEAD",
          "OPTIONS",
          "Any",
          "Method");
  private static final Pattern GO_GRPC_SERVER = Pattern.compile("Register(\\w+)Server");
  private static final Pattern GO_GRPC_CLIENT = Pattern.compile("New(\\w+)Client");

  @Override
  public void extract(SNode root, FactOutput out) {
    root.walk(
        n -> {
          switch (n.type) {
            case "type_spec" -> {
              SNode t = n.field("type");
              String kind =
                  t == null
                      ? "type"
                      : switch (t.type) {
                        case "struct_type" -> "struct";
                        case "interface_type" -> "interface";
                        default -> "type";
                      };
              String typeName = out.text(n.field("name"));
              out.add("types", kind + " " + typeName);
              // Go has no annotations/inheritance to key off, so this is name_contains only
              if (!kind.equals("type")) {
                out.add(
                    "classRole",
                    typeName + ":" + CONFIG.classifyRole("go", typeName, List.of(), List.of()));
              }
              if (kind.equals("struct"))
                goStructFields(t, out).forEach(f -> out.add("fields", typeName + "." + f));
            }
            case "function_declaration" -> {
              String name = out.text(n.field("name"));
              out.add("functions", name);
              out.add("parameters", name + "(" + goParamList(n.field("parameters"), out) + ")");
            }
            case "method_declaration" -> {
              String fullName = goReceiverType(n, out) + "." + out.text(n.field("name"));
              out.add("functions", fullName);
              out.add("parameters", fullName + "(" + goParamList(n.field("parameters"), out) + ")");
            }
            case "import_spec" -> out.add("imports", urlOf(n.field("path"), out));
            case "call_expression" -> goCall(n, out);
            default -> {}
          }
        });
  }

  private void goCall(SNode call, FactOutput out) {
    SNode fn = call.field("function");
    if (fn == null || !fn.type.equals("selector_expression")) return;
    String name = out.text(fn.field("field"));
    String operand = out.text(fn.field("operand"));
    List<SNode> args = args(call, out);
    String caller = goEnclosingName(call, out);

    Matcher server = GO_GRPC_SERVER.matcher(name);
    if (server.matches()) {
      out.add("rpcServers", caller + ": " + server.group(1));
      return;
    }
    Matcher client = GO_GRPC_CLIENT.matcher(name);
    if (client.matches()) {
      out.add("rpcClients", caller + ": " + client.group(1));
      return;
    }

    if (operand.equals("http")) {
      // net/http client helpers; http.HandleFunc/Handle register routes on the default mux
      String httpMethod = CONFIG.httpMethodFor("go", "http", name);
      if (httpMethod != null) {
        if (!args.isEmpty())
          out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
        return;
      }
      if (name.equals("NewRequest") || name.equals("NewRequestWithContext")) {
        int i = name.equals("NewRequest") ? 0 : 1;
        if (args.size() > i + 1) {
          out.add(
              "httpCalls",
              caller
                  + ": "
                  + goHttpMethod(out.text(args.get(i)))
                  + " "
                  + urlOf(args.get(i + 1), out));
        }
        return;
      }
    }
    if (!GO_ROUTE_FUNCS.contains(name) || args.isEmpty()) return;
    String url = urlOf(args.get(0), out);
    if (!looksLikePath(url)) return;

    String method = name.equals("HandleFunc") || name.equals("Handle") ? "ANY" : name.toUpperCase();
    // gorilla/mux: r.HandleFunc(path, h).Methods(http.MethodGet, ...)
    SNode parent = call.parent;
    if (parent != null
        && parent.type.equals("selector_expression")
        && out.text(parent.field("field")).equals("Methods")
        && parent.parent != null) {
      List<String> verbs = new ArrayList<>();
      Matcher m = GO_HTTP_METHOD.matcher(out.text(parent.parent.field("arguments")));
      while (m.find()) verbs.add((m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase());
      if (!verbs.isEmpty()) method = String.join(",", verbs);
    }
    out.add("endpoints", method + " " + url);
  }

  private static String goHttpMethod(String arg) {
    Matcher m = GO_HTTP_METHOD.matcher(arg);
    return m.find()
        ? (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase()
        : "{" + arg + "}";
  }

  private static String goReceiverType(SNode methodDecl, FactOutput out) {
    SNode recv = methodDecl.field("receiver");
    if (recv == null) return "";
    SNode param = recv.firstChildOfType("parameter_declaration");
    return param == null ? "" : out.text(param.field("type")).replace("*", "");
  }

  private static String goEnclosingName(SNode node, FactOutput out) {
    SNode fn = node.ancestorOfType("function_declaration", "method_declaration");
    if (fn == null) return "<module>";
    String name = out.text(fn.field("name"));
    return fn.type.equals("method_declaration") ? goReceiverType(fn, out) + "." + name : name;
  }

  private static List<String> goStructFields(SNode structType, FactOutput out) {
    SNode fieldList = structType.firstChildOfType("field_declaration_list");
    if (fieldList == null) return List.of();
    List<String> fields = new ArrayList<>();
    for (SNode fd : fieldList.namedChildren()) {
      if (!fd.type.equals("field_declaration")) continue;
      SNode name = fd.field("name");
      SNode type = fd.field("type");
      if (name != null) fields.add(out.text(name) + ":" + (type == null ? "" : out.text(type)));
    }
    return fields;
  }

  private static String goParamList(SNode params, FactOutput out) {
    if (params == null) return "";
    List<String> parts = new ArrayList<>();
    for (SNode p : params.namedChildren()) {
      if (!p.type.equals("parameter_declaration")
          && !p.type.equals("variadic_parameter_declaration")) continue;
      SNode name = p.field("name");
      SNode type = p.field("type");
      String t = type == null ? "" : out.text(type);
      parts.add(t + (name != null ? " " + out.text(name) : ""));
    }
    return String.join(", ", parts);
  }
}
