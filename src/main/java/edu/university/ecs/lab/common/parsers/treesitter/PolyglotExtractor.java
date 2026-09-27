package edu.university.ecs.lab.experiments;

import edu.university.ecs.lab.experiments.TsUtil.SNode;
import org.treesitter.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tree-sitter extraction of microservice-relevant facts for JavaScript, Python, Go and C#.
 *
 * There is no CIMET baseline for these languages, so the rules aim at the same information CIMET
 * extracts for Java: types, functions, imports, annotations, exposed endpoints and outgoing calls.
 * Framework coverage is heuristic (see {@link Language} for what each language recognises); gRPC
 * registrations and clients are extracted as well because many polyglot systems talk over gRPC.
 */
public class PolyglotExtractor {

    public static final List<String> DIMENSIONS = List.of(
            "types", "classRole", "functions", "imports", "annotations", "endpoints", "httpCalls", "rpcServers", "rpcClients");

    private static final LanguageConfig CONFIG = LanguageConfig.get();

    public enum Language {
        // TSX grammar, not the plain JS one: it's a superset that also understands JSX and most
        // TypeScript/Flow type syntax, and still parses plain untyped JS the same way (see
        // TsGrammarCompare experiment: fixed 42/46 files that erroed on Flow-typed JSX, on a real repo).
        JAVASCRIPT(TreeSitterTsx::new, ".js", ".mjs", ".cjs", ".jsx"),          // Express-style routers, fetch/axios/http
        PYTHON(TreeSitterPython::new, ".py"),                                    // Flask/FastAPI decorators, Django path(), requests/httpx
        GO(TreeSitterGo::new, ".go"),                                            // net/http, gorilla/mux, gin/echo/chi
        CSHARP(TreeSitterCSharp::new, ".cs");                                    // ASP.NET attributes + minimal APIs, HttpClient

        final Supplier<TSLanguage> grammar;
        final List<String> extensions;

        Language(Supplier<TSLanguage> grammar, String... extensions) {
            this.grammar = grammar;
            this.extensions = List.of(extensions);
        }

        public static Optional<Language> forFile(String fileName) {
            for (Language l : values()) {
                for (String ext : l.extensions) if (fileName.endsWith(ext)) return Optional.of(l);
            }
            return Optional.empty();
        }
    }

    private static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options", "all");
    private static final Pattern GO_HTTP_METHOD = Pattern.compile("Method(Get|Post|Put|Delete|Patch|Head|Options)|\"(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\"");

    private final Map<Language, TSParser> parsers = new EnumMap<>(Language.class);

    /** Parse result plus the extracted facts. */
    public record Result(FileFacts facts, int syntaxErrors, long parseNanos) {}

    public Result extract(Language language, String path, byte[] bytes) {
        bytes = TsUtil.stripUtf8Bom(bytes);
        TSParser parser = parsers.computeIfAbsent(language, l -> {
            TSParser p = new TSParser();
            p.setLanguage(l.grammar.get());
            return p;
        });
        long t0 = System.nanoTime();
        TSTree tree = parser.parseString(null, new String(bytes, StandardCharsets.UTF_8));
        long parseNanos = System.nanoTime() - t0;

        SNode root = TsUtil.materialize(tree);
        int errors = root.findAll("ERROR").size();
        if (errors == 0 && tree.getRootNode().hasError()) errors = 1; // only MISSING nodes

        Out out = new Out(bytes);
        switch (language) {
            case JAVASCRIPT -> javascript(root, out);
            case PYTHON -> python(root, out);
            case GO -> go(root, out);
            case CSHARP -> csharp(root, out);
        }
        FileFacts facts = new FileFacts(path);
        out.values.forEach(facts::put);
        return new Result(facts, errors, parseNanos);
    }

    /** Collects values per dimension and gives rules access to node text. */
    private static final class Out {
        final byte[] bytes;
        final Map<String, List<String>> values = new LinkedHashMap<>();

        Out(byte[] bytes) {
            this.bytes = bytes;
            DIMENSIONS.forEach(d -> values.put(d, new ArrayList<>()));
        }

        String text(SNode n) { return TsUtil.text(n, bytes); }

        void add(String dimension, String value) { values.get(dimension).add(value); }
    }

    // ------------------------------------------------------------------ shared helpers

    private static final Set<String> STRING_TYPES = Set.of(
            "string", "template_string", "interpreted_string_literal", "raw_string_literal",
            "string_literal", "verbatim_string_literal", "interpolated_string_expression",
            "concatenated_string");

    /**
     * Best-effort value of a URL-like expression: literal parts are kept, interpolations and
     * identifiers become {name}, anything else becomes {?}.
     */
    private static String urlOf(SNode n, Out out) {
        if (n == null) return "";
        switch (n.type) {
            case "binary_expression" -> {
                SNode left = n.field("left"), right = n.field("right");
                if (left != null && right != null) return urlOf(left, out) + urlOf(right, out);
                return "{?}";
            }
            case "identifier", "field_identifier" -> { return "{" + out.text(n) + "}"; }
            case "parenthesized_expression" -> { return n.namedChildCount() > 0 ? urlOf(n.namedChild(0), out) : "{?}"; }
            case "argument" -> { return n.namedChildCount() > 0 ? urlOf(n.namedChild(n.namedChildCount() - 1), out) : "{?}"; }
            default -> { }
        }
        if (!STRING_TYPES.contains(n.type)) return "{?}";
        StringBuilder sb = new StringBuilder();
        boolean sawPart = false;
        for (SNode c : n.namedChildren()) {
            switch (c.type) {
                case "string_fragment", "string_content", "interpreted_string_literal_content",
                     "raw_string_literal_content", "string_literal_content", "interpolated_verbatim_string_text",
                     "interpolated_string_text", "escape_sequence" -> { sb.append(out.text(c)); sawPart = true; }
                case "template_substitution", "interpolation" -> {
                    SNode e = c.namedChildCount() > 0 ? c.namedChild(0) : null;
                    sb.append("{").append(e == null ? "?" : out.text(e)).append("}");
                    sawPart = true;
                }
                case "string" -> { sb.append(urlOf(c, out)); sawPart = true; } // Python implicit concatenation
                default -> { }
            }
        }
        if (!sawPart) {
            // Leaf literal (e.g. Go raw strings, C# verbatim strings): strip quotes/prefixes
            String t = out.text(n);
            sb.append(t.replaceAll("^[@$a-zA-Z]*[\"'`]+|[\"'`]+$", ""));
        }
        return sb.toString();
    }

    private static boolean looksLikePath(String url) {
        return url.startsWith("/") || url.startsWith("{") && url.contains("/");
    }

    private static List<SNode> args(SNode call, Out out) {
        SNode a = call.field("arguments");
        if (a == null) return List.of();
        return a.namedChildren().stream().filter(c -> !c.type.contains("comment")).toList();
    }

    private static String lastSegment(String s) {
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf(':'));
        return i < 0 ? s : s.substring(i + 1);
    }

    // ------------------------------------------------------------------ JavaScript

    private void javascript(SNode root, Out out) {
        root.walk(n -> {
            switch (n.type) {
                case "class_declaration", "class" -> {
                    SNode name = n.field("name");
                    if (name != null) {
                        String className = out.text(name);
                        out.add("types", "class " + className);
                        String role = CONFIG.classifyRole("javascript", className, List.of(), jsSupertypes(n, out));
                        out.add("classRole", className + ":" + role);
                    }
                }
                case "function_declaration", "generator_function_declaration", "method_definition" ->
                        out.add("functions", out.text(n.field("name")));
                case "variable_declarator" -> {
                    SNode v = n.field("value");
                    if (v != null && (v.type.equals("arrow_function") || v.type.equals("function_expression")
                            || v.type.equals("function"))) {
                        out.add("functions", out.text(n.field("name")));
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
                        if (name.endsWith("Service") || name.endsWith("Client")) out.add("rpcClients", name);
                    }
                }
                default -> { }
            }
        });
    }

    private void jsCall(SNode call, Out out) {
        SNode fn = call.field("function");
        List<SNode> args = args(call, out);
        if (fn == null) return;
        if (fn.type.equals("identifier")) {
            String name = out.text(fn);
            if (name.equals("require") && !args.isEmpty()) out.add("imports", urlOf(args.get(0), out));
            String httpMethod = CONFIG.httpMethodFor("javascript", name, name); // e.g. bare fetch(url)
            if (httpMethod != null && !args.isEmpty()) out.add("httpCalls", httpMethod + " " + urlOf(args.get(0), out));
            return;
        }
        if (!fn.type.equals("member_expression")) return;
        String method = out.text(fn.field("property"));
        SNode object = fn.field("object");
        String objectText = out.text(object);

        if (method.equals("addService") && !args.isEmpty()) {
            // server.addService(proto.pkg.CartService.service, impl)
            String svc = out.text(args.get(0)).replaceAll("\\.service$", "");
            out.add("rpcServers", lastSegment(svc));
            return;
        }
        String httpMethod = CONFIG.httpMethodFor("javascript", objectText, method);
        if (httpMethod != null && !args.isEmpty()) {
            out.add("httpCalls", httpMethod + " " + urlOf(args.get(0), out));
            return;
        }
        if (HTTP_VERBS.contains(method.toLowerCase()) && !args.isEmpty()) {
            String url = urlOf(args.get(0), out);
            if (looksLikePath(url)) out.add("endpoints", method.toUpperCase() + " " + url);
        }
    }

    /** Simple name of the class's extended type, if any (tree-sitter-javascript has no "super" field). */
    private static List<String> jsSupertypes(SNode classNode, Out out) {
        SNode heritage = classNode.firstChildOfType("class_heritage");
        if (heritage == null || heritage.namedChildCount() == 0) return List.of();
        return List.of(lastSegment(out.text(heritage.namedChild(0))));
    }

    // ------------------------------------------------------------------ Python

    private static final Pattern PY_GRPC_SERVER = Pattern.compile("add_(\\w+?)Servicer_to_server");
    private static final Pattern PY_GRPC_STUB = Pattern.compile("(\\w+)Stub");

    private void python(SNode root, Out out) {
        root.walk(n -> {
            switch (n.type) {
                case "class_definition" -> {
                    String className = out.text(n.field("name"));
                    out.add("types", "class " + className);
                    String role = CONFIG.classifyRole("python", className, pyDecoratorNames(n, out), pySupertypes(n, out));
                    out.add("classRole", className + ":" + role);
                }
                case "function_definition" -> out.add("functions", out.text(n.field("name")));
                case "import_statement" -> n.fields("name").forEach(x -> out.add("imports", out.text(
                        x.type.equals("aliased_import") ? x.field("name") : x)));
                case "import_from_statement" -> {
                    String module = out.text(n.field("module_name"));
                    List<SNode> names = n.fields("name");
                    if (names.isEmpty()) out.add("imports", module + ".*");
                    names.forEach(x -> out.add("imports", module + "." + out.text(
                            x.type.equals("aliased_import") ? x.field("name") : x)));
                }
                case "decorator" -> {
                    out.add("annotations", out.text(n).substring(1).trim());
                    pyRouteDecorator(n, out);
                }
                case "call" -> pyCall(n, out);
                default -> { }
            }
        });
    }

    private void pyRouteDecorator(SNode decorator, Out out) {
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
        out.add("endpoints", method + " " + url + (def == null ? "" : " -> " + out.text(def.field("name"))));
    }

    private void pyCall(SNode call, Out out) {
        SNode fn = call.field("function");
        if (fn == null) return;
        List<SNode> args = args(call, out);
        String callee = fn.type.equals("attribute") ? out.text(fn.field("attribute")) : out.text(fn);

        Matcher server = PY_GRPC_SERVER.matcher(callee);
        if (server.matches()) { out.add("rpcServers", server.group(1)); return; }
        Matcher stub = PY_GRPC_STUB.matcher(callee);
        if (stub.matches()) { out.add("rpcClients", stub.group(1)); return; }

        if (fn.type.equals("identifier") && (callee.equals("path") || callee.equals("re_path")) && !args.isEmpty()) {
            out.add("endpoints", "ANY /" + urlOf(args.get(0), out)); // Django urls.py
            return;
        }
        if (fn.type.equals("attribute")) {
            String object = out.text(fn.field("object"));
            // requests.get(...), self.client.get(...) (Locust / test clients), session.post(...)
            String httpMethod = CONFIG.httpMethodFor("python", lastSegment(object), callee);
            if (httpMethod != null && !args.isEmpty()) {
                out.add("httpCalls", httpMethod + " " + urlOf(args.get(0), out));
            } else if (object.endsWith("request") && callee.equals("urlopen") && !args.isEmpty()) {
                out.add("httpCalls", "ANY " + urlOf(args.get(0), out));
            }
        }
    }

    /** Decorator names on a class, e.g. ["app.route"] for @app.route("/x") — empty if the class isn't decorated. */
    private static List<String> pyDecoratorNames(SNode classNode, Out out) {
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
    private static List<String> pySupertypes(SNode classNode, Out out) {
        SNode bases = classNode.field("superclasses");
        if (bases == null) return List.of();
        List<String> names = new ArrayList<>();
        for (SNode c : bases.namedChildren()) {
            if (c.type.equals("keyword_argument")) continue; // e.g. class Meta(metaclass=...)
            names.add(lastSegment(out.text(c)));
        }
        return names;
    }

    // ------------------------------------------------------------------ Go

    private static final Set<String> GO_ROUTE_FUNCS = Set.of(
            "HandleFunc", "Handle", "Get", "Post", "Put", "Delete", "Patch", "Head", "Options",
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Any", "Method");
    private static final Pattern GO_GRPC_SERVER = Pattern.compile("Register(\\w+)Server");
    private static final Pattern GO_GRPC_CLIENT = Pattern.compile("New(\\w+)Client");

    private void go(SNode root, Out out) {
        root.walk(n -> {
            switch (n.type) {
                case "type_spec" -> {
                    SNode t = n.field("type");
                    String kind = t == null ? "type" : switch (t.type) {
                        case "struct_type" -> "struct";
                        case "interface_type" -> "interface";
                        default -> "type";
                    };
                    String typeName = out.text(n.field("name"));
                    out.add("types", kind + " " + typeName);
                    // Go has no annotations/inheritance to key off, so this is name_contains only
                    if (!kind.equals("type")) {
                        out.add("classRole", typeName + ":" + CONFIG.classifyRole("go", typeName, List.of(), List.of()));
                    }
                }
                case "function_declaration" -> out.add("functions", out.text(n.field("name")));
                case "method_declaration" -> {
                    SNode recv = n.field("receiver");
                    String recvType = "";
                    if (recv != null) {
                        SNode param = recv.firstChildOfType("parameter_declaration");
                        if (param != null) recvType = out.text(param.field("type")).replace("*", "");
                    }
                    out.add("functions", recvType + "." + out.text(n.field("name")));
                }
                case "import_spec" -> out.add("imports", urlOf(n.field("path"), out));
                case "call_expression" -> goCall(n, out);
                default -> { }
            }
        });
    }

    private void goCall(SNode call, Out out) {
        SNode fn = call.field("function");
        if (fn == null || !fn.type.equals("selector_expression")) return;
        String name = out.text(fn.field("field"));
        String operand = out.text(fn.field("operand"));
        List<SNode> args = args(call, out);

        Matcher server = GO_GRPC_SERVER.matcher(name);
        if (server.matches()) { out.add("rpcServers", server.group(1)); return; }
        Matcher client = GO_GRPC_CLIENT.matcher(name);
        if (client.matches()) { out.add("rpcClients", client.group(1)); return; }

        if (operand.equals("http")) {
            // net/http client helpers; http.HandleFunc/Handle register routes on the default mux
            String httpMethod = CONFIG.httpMethodFor("go", "http", name);
            if (httpMethod != null) {
                if (!args.isEmpty()) out.add("httpCalls", httpMethod + " " + urlOf(args.get(0), out));
                return;
            }
            if (name.equals("NewRequest") || name.equals("NewRequestWithContext")) {
                int i = name.equals("NewRequest") ? 0 : 1;
                if (args.size() > i + 1) {
                    out.add("httpCalls", goHttpMethod(out.text(args.get(i))) + " " + urlOf(args.get(i + 1), out));
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
        if (parent != null && parent.type.equals("selector_expression")
                && out.text(parent.field("field")).equals("Methods") && parent.parent != null) {
            List<String> verbs = new ArrayList<>();
            Matcher m = GO_HTTP_METHOD.matcher(out.text(parent.parent.field("arguments")));
            while (m.find()) verbs.add((m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase());
            if (!verbs.isEmpty()) method = String.join(",", verbs);
        }
        out.add("endpoints", method + " " + url);
    }

    private static String goHttpMethod(String arg) {
        Matcher m = GO_HTTP_METHOD.matcher(arg);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase() : "{" + arg + "}";
    }

    // ------------------------------------------------------------------ C#

    private static final Pattern CS_HTTP_ATTR = Pattern.compile("Http(Get|Post|Put|Delete|Patch|Head|Options)");
    private static final Pattern CS_MINIMAL_API = Pattern.compile("Map(Get|Post|Put|Delete|Patch|Methods)?");
    private static final Pattern CS_HTTP_CLIENT = Pattern.compile(
            "(Get|Post|Put|Delete|Patch|Send)(String|Stream|ByteArray|AsJson|FromJson)?Async");

    private void csharp(SNode root, Out out) {
        root.walk(n -> {
            switch (n.type) {
                case "class_declaration", "interface_declaration", "record_declaration", "struct_declaration",
                     "enum_declaration" -> {
                    String typeName = out.text(n.field("name"));
                    out.add("types", n.type.replace("_declaration", "") + " " + typeName);
                    List<String> supertypes = csharpSupertypes(n, out);
                    if (n.type.equals("class_declaration")) {
                        csharpController(n, out);
                        String role = CONFIG.classifyRole("csharp", typeName, csharpAttributeNames(n, out), supertypes);
                        out.add("classRole", typeName + ":" + role);
                    }
                    csharpGrpcImpl(supertypes, out);
                }
                case "method_declaration" -> out.add("functions", out.text(n.field("name")));
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
                default -> { }
            }
        });
    }

    /** ASP.NET MVC controllers: [Route] on the class + [HttpGet("...")] on actions. */
    private void csharpController(SNode cls, Out out) {
        String className = out.text(cls.field("name"));
        String prefix = attributeArg(cls, "Route", out);
        if (prefix != null) prefix = prefix.replace("[controller]", className.replaceAll("Controller$", ""));
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
                    String url = "/" + String.join("/", java.util.stream.Stream.of(prefix, template)
                            .filter(s -> s != null && !s.isEmpty()).map(s -> s.replaceAll("^/|/$", "")).toList());
                    out.add("endpoints", verb.group(1).toUpperCase() + " " + url + " -> " + out.text(m.field("name")));
                }
            }
        }
    }

    /** gRPC service implementations derive from the generated Foo.FooBase class. */
    private void csharpGrpcImpl(List<String> supertypes, Out out) {
        for (String simple : supertypes) {
            if (simple.endsWith("Base") && simple.length() > 4 && !simple.equals("ControllerBase")) {
                out.add("rpcServers", simple.substring(0, simple.length() - 4));
            }
        }
    }

    private static List<String> csharpSupertypes(SNode decl, Out out) {
        SNode bases = decl.firstChildOfType("base_list");
        if (bases == null) return List.of();
        List<String> names = new ArrayList<>();
        for (SNode b : bases.namedChildren()) names.add(lastSegment(out.text(b)));
        return names;
    }

    private static List<String> csharpAttributeNames(SNode decl, Out out) {
        List<String> names = new ArrayList<>();
        for (SNode list : decl.namedChildren()) {
            if (!list.type.equals("attribute_list")) continue;
            for (SNode attr : list.namedChildren()) names.add(out.text(attr.field("name")));
        }
        return names;
    }

    private static String attributeArg(SNode decl, String attributeName, Out out) {
        for (SNode list : decl.namedChildren()) {
            if (!list.type.equals("attribute_list")) continue;
            for (SNode attr : list.namedChildren()) {
                if (out.text(attr.field("name")).equals(attributeName)) return firstAttributeArg(attr, out);
            }
        }
        return null;
    }

    private static String firstAttributeArg(SNode attr, Out out) {
        SNode args = attr.firstChildOfType("attribute_argument_list");
        if (args == null || args.namedChildCount() == 0) return "";
        return urlOf(args.namedChild(0), out);
    }

    private void csharpCall(SNode call, Out out) {
        SNode fn = call.field("function");
        if (fn == null || !fn.type.equals("member_access_expression")) return;
        SNode nameNode = fn.field("name");
        String name = nameNode == null ? "" : out.text(nameNode.type.equals("generic_name") ? nameNode.namedChild(0) : nameNode);
        List<SNode> args = args(call, out);

        if (name.equals("MapGrpcService") && nameNode.type.equals("generic_name")) {
            out.add("rpcServers", lastSegment(out.text(nameNode.namedChild(1)).replaceAll("[<>]", "")));
            return;
        }
        Matcher minimal = CS_MINIMAL_API.matcher(name);
        if (minimal.matches() && !args.isEmpty()) {
            String url = urlOf(args.get(0), out);
            if (looksLikePath(url)) {
                out.add("endpoints", (minimal.group(1) == null ? "ANY" : minimal.group(1).toUpperCase()) + " " + url);
                return;
            }
        }
        if (CS_HTTP_CLIENT.matcher(name).matches() && !args.isEmpty()) {
            SNode first = args.get(0).namedChildCount() > 0 ? args.get(0).namedChild(args.get(0).namedChildCount() - 1) : null;
            // Only string-ish first arguments: filters out cache.GetAsync(key) and similar
            if (first != null && (STRING_TYPES.contains(first.type) || first.type.equals("binary_expression"))) {
                out.add("httpCalls", name.replaceAll("(String|Stream|ByteArray|AsJson|FromJson)?Async$", "").toUpperCase()
                        + " " + urlOf(first, out));
            }
        }
    }
}
