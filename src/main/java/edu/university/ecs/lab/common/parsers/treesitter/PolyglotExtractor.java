package edu.university.ecs.lab.common.parsers.treesitter;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;
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
            "types", "classRole", "fields", "functions", "parameters", "imports", "annotations",
            "endpoints", "httpCalls", "rpcServers", "rpcClients", "queueProducers", "queueConsumers",
            "routeMounts");

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

        public List<String> getExtensions() { return extensions; }

        public static Optional<Language> forFile(String fileName) {
            for (Language l : values()) {
                for (String ext : l.extensions) if (fileName.endsWith(ext)) return Optional.of(l);
            }
            return Optional.empty();
        }
    }

    private static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "delete", "patch", "head", "options", "all");
    private static final Pattern GO_HTTP_METHOD = Pattern.compile("Method(Get|Post|Put|Delete|Patch|Head|Options)|\"(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\"");

    // Message-queue (AMQP/Kafka) produce/consume detection, shared across all four languages - name
    // matching only, the same spirit as the HTTP-verb and gRPC rules above (#16 follow-up: the pipeline
    // had no async/queue dimension at all). Grounded in real library usage found across our own
    // ground-truth repos: pika (basic_publish/basic_consume), amqplib (publish/sendToQueue/consume),
    // RabbitMQ.Client (BasicPublish[Async]/BasicConsume[Async]), streadway/amqp (Publish/Consume),
    // segmentio/kafka-go (WriteMessages/ReadMessage/FetchMessage), Sarama (SendMessage/ConsumePartition),
    // confluent-kafka/kafka-python (produce/consume/poll/subscribe), kafkajs (send/subscribe).
    // "ALWAYS" method names are distinctive enough to match regardless of the receiver's name; "HINTED"
    // ones (publish/send/consume/subscribe/poll - all common words with unrelated meanings elsewhere)
    // only count when the receiver's own name also suggests messaging, the same tradeoff the HTTP-verb
    // rules already make for generic names like "get"/"post".
    private static final Set<String> MQ_PRODUCE_ALWAYS = Set.of(
            "basic_publish", "BasicPublish", "BasicPublishAsync", "sendToQueue", "WriteMessages",
            "SendMessage", "sendBatch",
            // aiokafka's producer method (confirmed by reproduction: Python-Microservices' order-service
            // calls _producer.send_and_wait(topic, ...) - "send" alone didn't match it, #16 follow-up)
            "send_and_wait");
    private static final Set<String> MQ_CONSUME_ALWAYS = Set.of(
            "basic_consume", "BasicConsume", "BasicConsumeAsync", "ConsumePartition", "FetchMessage",
            "ReadMessage");
    // aiokafka/kafka-python's consumer takes its topic as the constructor's own first argument and is
    // then driven by `async for msg in consumer:` - a language construct, not a method call, so there's
    // no verb to match the way basic_consume()/Subscribe() give every other library. The constructor
    // call itself is the only call-shaped signal available, and it's reliable on its own (#16 follow-up).
    private static final Set<String> PY_KAFKA_CONSUMER_CTORS = Set.of("AIOKafkaConsumer", "KafkaConsumer");
    private static final Set<String> MQ_PRODUCE_HINTED = Set.of("publish", "Publish", "produce", "Produce", "send", "Send");
    private static final Set<String> MQ_CONSUME_HINTED = Set.of("consume", "Consume", "subscribe", "Subscribe", "poll", "Poll");
    private static final Set<String> MQ_OBJECT_HINTS = Set.of(
            "channel", "ch", "queue", "mq", "amqp", "kafka", "broker", "publisher", "producer",
            "consumer", "exchange", "rabbit", "rabbitmq");
    // Checked in this order, not just set membership: pika's basic_publish(exchange=..., routing_key=...)
    // passes both, and routing_key/topic/queue identify the actual destination - "exchange" is just the
    // broker-side router, the least useful of the four when only one can be reported.
    private static final List<String> MQ_TOPIC_KEYWORDS = List.of("routing_key", "topic", "queue", "exchange");

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
        // JS only: local variable name -> what it refers to, either "THIS_FILE" (a router created
        // in-file, e.g. express.Router()) or a require()'d relative module path (e.g.
        // "./routers/events-router") - built in a pass over the whole file before call expressions are
        // processed, so an app.use("/prefix", thatVariable) mount can be resolved regardless of
        // declaration order (see resolveRouteMounts in IRExtractionService for the other half of this).
        final Map<String, String> jsRouterVars = new HashMap<>();
        // Go only: local variable name -> prefix, for gorilla/mux's r.PathPrefix("/x").Subrouter() and
        // gin's router.Group("/x") - both always same-file (the sub-router/group is used right where
        // it's created), so unlike JS this is resolved immediately in goCall, no cross-file pass needed.
        final Map<String, String> goRouterPrefixes = new HashMap<>();
        // Python only, same-file case: local variable -> prefix, for Blueprint(..., url_prefix="/x") /
        // APIRouter(prefix="/x") - the prefix is known at construction, so (like Go) this is resolved
        // immediately at each @var.route(...) decorator, not via the cross-file resolver.
        final Map<String, String> pyRouterPrefixes = new HashMap<>();
        // Python only, cross-file case: local variable -> what it refers to, either "THIS_FILE" (a
        // Blueprint/APIRouter created in-file) or a relative module path from `from .x.y import name`
        // (e.g. "./x/y") - for app.include_router(name, prefix=...) / app.register_blueprint(name,
        // url_prefix=...), which (like Express's app.use) is routinely in a different file than the one
        // declaring the routes.
        final Map<String, String> pyRouterVars = new HashMap<>();
        // Go only: package-level var/const name -> its string-literal initializer (e.g. microservices-demo's
        // `var baseUrl = ""`, later overridable from an env var). A route built as baseUrl+"/cart" then
        // resolves to its declared default instead of a placeholder segment.
        final Map<String, String> stringConstants = new HashMap<>();

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
            case "identifier", "field_identifier" -> {
                String known = out.stringConstants.get(out.text(n));
                return known != null ? known : "{" + out.text(n) + "}";
            }
            case "parenthesized_expression" -> { return n.namedChildCount() > 0 ? urlOf(n.namedChild(0), out) : "{?}"; }
            case "argument" -> { return n.namedChildCount() > 0 ? urlOf(n.namedChild(n.namedChildCount() - 1), out) : "{?}"; }
            // C# attribute arguments ([Route("api/v1/items")], [HttpGet("{id}")]) are wrapped one level
            // deeper than a plain call argument - tree-sitter-c-sharp always nests the value in an
            // "attribute_argument" node, even for a single unnamed string literal. Without this case every
            // C# attribute-based route's literal path was silently replaced with "{?}" (#16 follow-up -
            // found by comparing our output against an external ground truth's exact path strings; the
            // endpoint COUNTS were always right, only the path text was wrong, for every C# repo tested).
            case "attribute_argument" -> { return n.namedChildCount() > 0 ? urlOf(n.namedChild(n.namedChildCount() - 1), out) : "{?}"; }
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

    /** Prepends a mount/group/router prefix to a path, normalizing the single slash between them. */
    private static String joinPaths(String prefix, String path) {
        if (prefix == null || prefix.isEmpty()) {
            return path;
        }
        String normalizedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String normalizedPath = (path == null || path.isEmpty()) ? "" : (path.startsWith("/") ? path : "/" + path);
        return normalizedPrefix + normalizedPath;
    }

    private static List<SNode> args(SNode call, Out out) {
        SNode a = call.field("arguments");
        if (a == null) return List.of();
        return a.namedChildren().stream().filter(c -> !c.type.contains("comment")).toList();
    }

    /** "produce", "consume", or null - see the MQ_* constants above for the reasoning. */
    private static String messageDirection(String methodName, String objectName) {
        if (MQ_PRODUCE_ALWAYS.contains(methodName)) return "produce";
        if (MQ_CONSUME_ALWAYS.contains(methodName)) return "consume";
        String objLower = objectName == null ? "" : lastSegment(objectName).toLowerCase();
        boolean hinted = MQ_OBJECT_HINTS.stream().anyMatch(objLower::contains);
        if (!hinted) return null;
        if (MQ_PRODUCE_HINTED.contains(methodName)) return "produce";
        if (MQ_CONSUME_HINTED.contains(methodName)) return "consume";
        return null;
    }

    /**
     * Best-effort queue/topic name for a message-queue call: a keyword argument named queue/
     * routing_key/topic/exchange if one is present (pika's basic_publish takes these as keyword
     * args, not positionally), else the first positional argument (true for amqplib's
     * consume("queue", cb), Go's amqp Consume("queue", ...), RabbitMQ.Client's BasicPublish(exchange,
     * routingKey, ...)). A struct-literal argument (e.g. kafka-go's kafka.Message{Topic: ...}) isn't
     * unpacked - same "best-effort, not resolved" tradeoff the rest of this extractor already makes.
     */
    private static String messageTopicOf(List<SNode> args, Out out) {
        Map<String, SNode> keywordValues = new LinkedHashMap<>();
        for (SNode a : args) {
            if (a.type.equals("keyword_argument") && a.field("name") != null) {
                keywordValues.put(out.text(a.field("name")), a.field("value"));
            }
        }
        for (String keyword : MQ_TOPIC_KEYWORDS) {
            SNode value = keywordValues.get(keyword);
            if (value != null) return urlOf(value, out);
        }
        return args.isEmpty() ? "" : urlOf(args.get(0), out);
    }

    /** Emits a queueProducers/queueConsumers fact if this call matches a message-queue produce/consume
     *  pattern; returns whether it did, so callers can {@code return} early like the gRPC checks do. */
    private static boolean tryMessageCall(Out out, String caller, String methodName, String objectName, List<SNode> args) {
        String direction = messageDirection(methodName, objectName);
        if (direction == null) return false;
        out.add(direction.equals("produce") ? "queueProducers" : "queueConsumers", caller + ": " + messageTopicOf(args, out));
        return true;
    }

    private static String lastSegment(String s) {
        int i = Math.max(s.lastIndexOf('.'), s.lastIndexOf(':'));
        return i < 0 ? s : s.substring(i + 1);
    }

    // "<module>" marks a call made at a file's top level (e.g. Program.cs's top-level statements, a
    // module-level script), outside any function; "<anonymous>" one inside a function with no
    // discoverable name. Each language's own enclosing-name helper also qualifies with the owning
    // class ("ClassName.methodName") when the enclosing function is a class method, so a call made
    // from one of several classes in the same file is attributed to the right one.

    // ------------------------------------------------------------------ JavaScript

    private void javascript(SNode root, Out out) {
        // First pass: find every local variable that refers to an Express router, cross-file
        // (var x = require('./some-router')) or same-file (var x = express.Router() / new Router()) -
        // done up front so a later app.use("/prefix", x) resolves regardless of where in the file x was
        // declared (#16 follow-up: sub-router mount prefixes were never tracked at all before this).
        root.walk(n -> {
            if (!n.type.equals("variable_declarator")) return;
            SNode nameNode = n.field("name");
            SNode value = n.field("value");
            if (nameNode == null || value == null || !nameNode.type.equals("identifier")) return;
            String varName = out.text(nameNode);

            if (value.type.equals("call_expression")) {
                SNode fn = value.field("function");
                List<SNode> callArgs = args(value, out);
                if (fn != null && fn.type.equals("identifier") && out.text(fn).equals("require")
                        && callArgs.size() == 1) {
                    String modulePath = urlOf(callArgs.get(0), out);
                    if (modulePath.startsWith(".")) out.jsRouterVars.put(varName, modulePath);
                    return;
                }
                if (fn != null && fn.type.equals("member_expression")
                        && out.text(fn.field("property")).equals("Router")) {
                    out.jsRouterVars.put(varName, "THIS_FILE");
                }
            } else if (value.type.equals("new_expression")) {
                SNode ctor = value.field("constructor");
                if (ctor != null && lastSegment(out.text(ctor)).equals("Router")) {
                    out.jsRouterVars.put(varName, "THIS_FILE");
                }
            }
        });

        root.walk(n -> {
            switch (n.type) {
                case "class_declaration", "class" -> {
                    SNode name = n.field("name");
                    if (name != null) {
                        String className = out.text(name);
                        out.add("types", "class " + className);
                        String role = CONFIG.classifyRole("javascript", className, List.of(), jsSupertypes(n, out));
                        out.add("classRole", className + ":" + role);
                        jsClassFields(n, out).forEach(f -> out.add("fields", className + "." + f));
                    }
                }
                case "function_declaration", "generator_function_declaration", "method_definition" -> {
                    // method_definition is only ever valid directly inside a class body, so it always
                    // gets qualified; function_declaration is a top-level statement so it never is.
                    String name = n.type.equals("method_definition") ? jsQualifiedMethodName(n, out) : out.text(n.field("name"));
                    out.add("functions", name);
                    out.add("parameters", name + "(" + jsParamList(n.field("parameters"), out) + ")");
                }
                case "variable_declarator" -> {
                    SNode v = n.field("value");
                    if (v != null && (v.type.equals("arrow_function") || v.type.equals("function_expression")
                            || v.type.equals("function"))) {
                        String name = out.text(n.field("name"));
                        out.add("functions", name);
                        out.add("parameters", name + "(" + jsParamList(v.field("parameters"), out) + ")");
                    }
                }
                case "import_statement" -> out.add("imports", urlOf(n.field("source"), out));
                case "decorator" -> out.add("annotations", out.text(n).substring(1));
                case "call_expression" -> jsCall(n, out);
                case "pair" -> jsMoleculerAlias(n, out);
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

    private static final Pattern MOLECULER_ALIAS_KEY =
            Pattern.compile("(?:(GET|POST|PUT|DELETE|PATCH|OPTIONS)\\s+)?(/\\S*)");

    /**
     * moleculer-web (StretchShop and others) declares its REST routes as plain object-literal entries -
     * {@code aliases: {"POST /webhook/:supplier": "orders.paymentWebhookRaw", ...}} - not a single
     * registration call anywhere, so jsCall's whole approach (matching a .get()/.post() call) finds
     * nothing here at all (#16 follow-up: 0 endpoints extracted from a real repo with ~70). Requiring
     * BOTH the "METHOD /path" key shape AND the enclosing object being itself the value of a sibling
     * "aliases" key keeps this from matching an unrelated object that happens to have a string key
     * starting with "/".
     */
    private void jsMoleculerAlias(SNode pair, Out out) {
        SNode keyNode = pair.field("key");
        if (keyNode == null || !keyNode.type.equals("string")) return;
        Matcher m = MOLECULER_ALIAS_KEY.matcher(urlOf(keyNode, out));
        if (!m.matches()) return;

        SNode objectNode = pair.parent;
        if (objectNode == null || !objectNode.type.equals("object")) return;
        SNode aliasesPair = objectNode.parent;
        if (aliasesPair == null || !aliasesPair.type.equals("pair")) return;
        SNode aliasesKey = aliasesPair.field("key");
        if (aliasesKey == null || !out.text(aliasesKey).equals("aliases")) return;

        out.add("endpoints", (m.group(1) == null ? "GET" : m.group(1).toUpperCase()) + " " + m.group(2));
    }

    private void jsCall(SNode call, Out out) {
        SNode fn = call.field("function");
        List<SNode> args = args(call, out);
        if (fn == null) return;
        String caller = jsEnclosingName(call, out);
        if (fn.type.equals("identifier")) {
            String name = out.text(fn);
            if (name.equals("require") && !args.isEmpty()) out.add("imports", urlOf(args.get(0), out));
            String httpMethod = CONFIG.httpMethodFor("javascript", name, name); // e.g. bare fetch(url)
            if (httpMethod != null && !args.isEmpty()) out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
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
        if (tryMessageCall(out, caller, method, objectText, args)) return;
        // app.use("/prefix", routerVar) / router.use("/prefix", routerVar) - a sub-router mount. Requires
        // a path-shaped first argument (excludes plain middleware installs like app.use(express.json())
        // or app.use(cors()), which pass no path) and a last argument that's a plain identifier matching
        // a tracked router variable (excludes those same middleware calls, whose single argument is
        // itself a call/arrow-function, never a bare identifier naming one of our tracked routers).
        if (method.equals("use") && args.size() >= 2) {
            String prefix = urlOf(args.get(0), out);
            SNode lastArg = args.get(args.size() - 1);
            if (looksLikePath(prefix) && lastArg.type.equals("identifier")) {
                String target = out.jsRouterVars.get(out.text(lastArg));
                if (target != null) {
                    out.add("routeMounts", prefix + "|||" + target);
                    return;
                }
            }
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

    /** Simple name of the class's extended type, if any (tree-sitter-javascript has no "super" field). */
    private static List<String> jsSupertypes(SNode classNode, Out out) {
        SNode heritage = classNode.firstChildOfType("class_heritage");
        if (heritage == null || heritage.namedChildCount() == 0) return List.of();
        return List.of(lastSegment(out.text(heritage.namedChild(0))));
    }

    private static List<String> jsClassFields(SNode classNode, Out out) {
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

    private static String jsParamList(SNode params, Out out) {
        if (params == null) return "";
        List<String> parts = new ArrayList<>();
        for (SNode p : params.namedChildren()) {
            switch (p.type) {
                // The TSX grammar wraps every parameter in required_parameter/optional_parameter, with
                // the actual name/destructuring under "pattern" and an optional type annotation.
                case "required_parameter", "optional_parameter" -> {
                    SNode pattern = p.field("pattern");
                    SNode type = p.field("type");
                    String name = pattern == null ? out.text(p)
                            : pattern.type.equals("rest_pattern")
                                    ? "..." + (pattern.namedChildCount() > 0 ? out.text(pattern.namedChild(0)) : "")
                                    : out.text(pattern);
                    parts.add(name + (type != null ? ":" + out.text(type) : ""));
                }
                // Plain-JS grammar shapes, kept as a defensive fallback.
                case "identifier" -> parts.add(out.text(p));
                case "assignment_pattern" -> {
                    SNode left = p.field("left");
                    if (left != null) parts.add(out.text(left));
                }
                case "rest_pattern" -> parts.add("..." + (p.namedChildCount() > 0 ? out.text(p.namedChild(0)) : ""));
                default -> parts.add(out.text(p)); // object/array destructuring etc.: keep the raw pattern text
            }
        }
        return String.join(", ", parts);
    }

    /** "ClassName.methodName" for a class method, else just its own name (method_definition is only ever
     * valid directly inside a class body, so a name field is always present when it applies here). */
    private static String jsQualifiedMethodName(SNode methodDef, Out out) {
        String name = out.text(methodDef.field("name"));
        SNode cls = methodDef.ancestorOfType("class_declaration", "class");
        if (cls == null) return name;
        SNode clsName = cls.field("name");
        return clsName == null ? name : out.text(clsName) + "." + name;
    }

    private static String jsEnclosingName(SNode node, Out out) {
        SNode fn = node.ancestorOfType("function_declaration", "generator_function_declaration", "method_definition",
                "arrow_function", "function_expression", "function");
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

    // ------------------------------------------------------------------ Python

    private static final Pattern PY_GRPC_SERVER = Pattern.compile("add_(\\w+?)Servicer_to_server");
    private static final Pattern PY_GRPC_STUB = Pattern.compile("(\\w+)Stub");

    private static final Set<String> PY_ROUTER_CTORS = Set.of("Blueprint", "APIRouter");

    private void python(SNode root, Out out) {
        // First pass: track every local variable that's a Flask Blueprint / FastAPI APIRouter, same-file
        // (var = Blueprint(..., url_prefix="/x") / APIRouter(prefix="/x") - prefix known immediately) or
        // cross-file (from .routes.users import router [as alias] - prefix given later, wherever it's
        // include_router()'d/register_blueprint()'d - #16 follow-up, same bug class as Express's mount
        // prefix, just Python's two common frameworks' equivalent of it).
        root.walk(n -> {
            if (n.type.equals("assignment")) {
                SNode left = n.field("left");
                SNode right = n.field("right");
                if (left == null || right == null || !left.type.equals("identifier") || !right.type.equals("call")) return;
                SNode fn = right.field("function");
                if (fn == null) return;
                String ctorName = fn.type.equals("attribute") ? out.text(fn.field("attribute")) : out.text(fn);
                if (!PY_ROUTER_CTORS.contains(ctorName)) return;
                String varName = out.text(left);
                out.pyRouterVars.put(varName, "THIS_FILE");
                for (SNode a : args(right, out)) {
                    if (a.type.equals("keyword_argument")) {
                        String kwName = out.text(a.field("name"));
                        if (kwName.equals("prefix") || kwName.equals("url_prefix")) {
                            out.pyRouterPrefixes.put(varName, urlOf(a.field("value"), out));
                        }
                    }
                }
            } else if (n.type.equals("import_from_statement")) {
                String module = out.text(n.field("module_name"));
                if (!module.startsWith(".")) return; // only relative (same-repo) imports can be resolved
                String modulePath = "./" + module.replaceFirst("^\\.+", "").replace('.', '/');
                for (SNode nameNode : n.fields("name")) {
                    boolean aliased = nameNode.type.equals("aliased_import");
                    String localName = out.text(aliased ? nameNode.field("alias") : nameNode);
                    out.pyRouterVars.putIfAbsent(localName, modulePath);
                }
            }
        });

        root.walk(n -> {
            switch (n.type) {
                case "class_definition" -> {
                    String className = out.text(n.field("name"));
                    out.add("types", "class " + className);
                    String role = CONFIG.classifyRole("python", className, pyDecoratorNames(n, out), pySupertypes(n, out));
                    out.add("classRole", className + ":" + role);
                    pyClassFields(n, out).forEach(f -> out.add("fields", className + "." + f));
                }
                case "function_definition" -> {
                    // Prefixed "ClassName.methodName" when it's a direct class method (any nesting depth
                    // in between still resolves to the right owner, since ancestorOfType finds the nearest
                    // enclosing class regardless), bare name otherwise - lets the converter attribute
                    // methods/fields/calls to the class that actually owns them when a file has several.
                    String name = pyQualifiedFunctionName(n, out);
                    out.add("functions", name);
                    out.add("parameters", name + "(" + pyParamList(n.field("parameters"), out) + ")");
                }
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
        String object = out.text(fn.field("object"));
        String finalUrl = joinPaths(out.pyRouterPrefixes.get(object), url);
        SNode def = decorator.parent == null ? null : decorator.parent.field("definition");
        out.add("endpoints", method + " " + finalUrl + (def == null ? "" : " -> " + pyQualifiedFunctionName(def, out)));
    }

    private void pyCall(SNode call, Out out) {
        SNode fn = call.field("function");
        if (fn == null) return;
        List<SNode> args = args(call, out);
        String callee = fn.type.equals("attribute") ? out.text(fn.field("attribute")) : out.text(fn);
        String caller = pyEnclosingQualifiedName(call, out);

        Matcher server = PY_GRPC_SERVER.matcher(callee);
        if (server.matches()) { out.add("rpcServers", caller + ": " + server.group(1)); return; }
        Matcher stub = PY_GRPC_STUB.matcher(callee);
        if (stub.matches()) { out.add("rpcClients", caller + ": " + stub.group(1)); return; }

        if (fn.type.equals("identifier") && (callee.equals("path") || callee.equals("re_path")) && !args.isEmpty()) {
            out.add("endpoints", "ANY /" + urlOf(args.get(0), out)); // Django urls.py
            return;
        }
        if (fn.type.equals("identifier") && PY_KAFKA_CONSUMER_CTORS.contains(callee) && !args.isEmpty()
                && !args.get(0).type.equals("keyword_argument")) {
            out.add("queueConsumers", caller + ": " + urlOf(args.get(0), out));
            return;
        }
        if (fn.type.equals("attribute")) {
            String object = out.text(fn.field("object"));
            if (tryMessageCall(out, caller, callee, object, args)) return;
            // app.include_router(router, prefix="/v1") / app.register_blueprint(bp, url_prefix="/api") -
            // a cross-file mount, resolved later by RouteMountResolver once every file in the microservice
            // has been parsed (same mechanism as Express's app.use mount, #16 follow-up).
            if ((callee.equals("include_router") || callee.equals("register_blueprint")) && !args.isEmpty()
                    && args.get(0).type.equals("identifier")) {
                String target = out.pyRouterVars.get(out.text(args.get(0)));
                if (target != null) {
                    for (SNode a : args) {
                        if (a.type.equals("keyword_argument")) {
                            String kwName = out.text(a.field("name"));
                            if (kwName.equals("prefix") || kwName.equals("url_prefix")) {
                                out.add("routeMounts", urlOf(a.field("value"), out) + "|||" + target);
                            }
                        }
                    }
                    return;
                }
            }
            // requests.get(...), self.client.get(...) (Locust / test clients), session.post(...)
            String httpMethod = CONFIG.httpMethodFor("python", lastSegment(object), callee);
            if (httpMethod != null && !args.isEmpty()) {
                out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
            } else if (object.endsWith("request") && callee.equals("urlopen") && !args.isEmpty()) {
                out.add("httpCalls", caller + ": ANY " + urlOf(args.get(0), out));
            }
        }
    }

    /** "ClassName.functionName" if def is a direct method of some class (at any nesting depth), else just its own name. */
    private static String pyQualifiedFunctionName(SNode functionDef, Out out) {
        String name = out.text(functionDef.field("name"));
        SNode cls = functionDef.ancestorOfType("class_definition");
        return cls == null ? name : out.text(cls.field("name")) + "." + name;
    }

    private static String pyEnclosingQualifiedName(SNode node, Out out) {
        SNode fn = node.ancestorOfType("function_definition");
        return fn == null ? "<module>" : pyQualifiedFunctionName(fn, out);
    }

    /** Direct class-level attributes, e.g. "name: str" or "age: int = 0" - not instance fields set via self.x. */
    private static List<String> pyClassFields(SNode classNode, Out out) {
        SNode body = classNode.field("body");
        if (body == null) return List.of();
        List<String> fields = new ArrayList<>();
        for (SNode stmt : body.namedChildren()) {
            if (!stmt.type.equals("expression_statement") || stmt.namedChildCount() == 0) continue;
            SNode assign = stmt.namedChild(0);
            if (assign == null || !assign.type.equals("assignment")) continue;
            SNode left = assign.field("left");
            if (left == null || !left.type.equals("identifier")) continue; // skips self.x = ... (an "attribute", not identifier)
            SNode type = assign.field("type");
            fields.add(out.text(left) + ":" + (type == null ? "" : out.text(type)));
        }
        return fields;
    }

    private static String pyParamList(SNode params, Out out) {
        if (params == null) return "";
        List<String> parts = new ArrayList<>();
        for (SNode p : params.namedChildren()) {
            switch (p.type) {
                case "identifier" -> parts.add(out.text(p)); // untyped, e.g. self
                case "typed_parameter" -> {
                    SNode inner = p.namedChildCount() > 0 ? p.namedChild(0) : null;
                    String type = p.field("type") != null ? out.text(p.field("type")) : "";
                    if (inner == null) break;
                    String prefix = inner.type.equals("list_splat_pattern") ? "*"
                            : inner.type.equals("dictionary_splat_pattern") ? "**" : "";
                    SNode name = prefix.isEmpty() ? inner : (inner.namedChildCount() > 0 ? inner.namedChild(0) : inner);
                    parts.add((type.isEmpty() ? "" : type + " ") + prefix + out.text(name));
                }
                case "default_parameter" -> parts.add(out.text(p.field("name")));
                case "typed_default_parameter" -> {
                    String type = p.field("type") != null ? out.text(p.field("type")) : "";
                    parts.add((type.isEmpty() ? "" : type + " ") + out.text(p.field("name")));
                }
                case "list_splat_pattern" -> parts.add("*" + (p.namedChildCount() > 0 ? out.text(p.namedChild(0)) : ""));
                case "dictionary_splat_pattern" -> parts.add("**" + (p.namedChildCount() > 0 ? out.text(p.namedChild(0)) : ""));
                default -> { }
            }
        }
        return String.join(", ", parts);
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
            if (!n.type.equals("var_spec") && !n.type.equals("const_spec")) return;
            if (n.ancestorOfType("function_declaration", "method_declaration", "func_literal") != null) return;
            SNode name = n.field("name");
            SNode value = n.field("value");
            if (name == null || value == null || value.namedChildCount() != 1) return;
            SNode literal = value.namedChild(0);
            if (literal.type.equals("interpreted_string_literal") || literal.type.equals("raw_string_literal")) {
                out.stringConstants.put(out.text(name), urlOf(literal, out));
            }
        });
        // First pass: find every local variable that's a prefixed sub-router/group - gorilla/mux's
        // r.PathPrefix("/x").Subrouter() or gin's router.Group("/x") - so a later call on that variable
        // (goCall) can prepend its prefix. Always same-file in practice (#16 follow-up: same class of bug
        // as JS's sub-router mount prefix, but Go's idiom doesn't span files the way Express's does, so
        // this resolves immediately rather than needing the cross-file RouteMountResolver pass).
        root.walk(n -> {
            if (!n.type.equals("short_var_declaration")) return;
            SNode left = n.field("left");
            SNode right = n.field("right");
            if (left == null || right == null || left.namedChildCount() != 1 || right.namedChildCount() != 1) return;
            SNode nameNode = left.namedChild(0);
            SNode valueNode = right.namedChild(0);
            if (!nameNode.type.equals("identifier") || !valueNode.type.equals("call_expression")) return;
            SNode fn = valueNode.field("function");
            if (fn == null || !fn.type.equals("selector_expression")) return;
            String methodName = out.text(fn.field("field"));
            List<SNode> callArgs = args(valueNode, out);

            if (methodName.equals("Subrouter")) {
                SNode operand = fn.field("operand");
                if (operand != null && operand.type.equals("call_expression")) {
                    SNode operandFn = operand.field("function");
                    if (operandFn != null && operandFn.type.equals("selector_expression")
                            && out.text(operandFn.field("field")).equals("PathPrefix")) {
                        List<SNode> prefixArgs = args(operand, out);
                        if (!prefixArgs.isEmpty()) {
                            out.goRouterPrefixes.put(out.text(nameNode), urlOf(prefixArgs.get(0), out));
                        }
                    }
                }
            } else if (methodName.equals("Group") && !callArgs.isEmpty()) {
                out.goRouterPrefixes.put(out.text(nameNode), urlOf(callArgs.get(0), out));
            }
        });

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
                    if (kind.equals("struct")) goStructFields(t, out).forEach(f -> out.add("fields", typeName + "." + f));
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
        String caller = goEnclosingName(call, out);

        Matcher server = GO_GRPC_SERVER.matcher(name);
        if (server.matches()) { out.add("rpcServers", caller + ": " + server.group(1)); return; }
        Matcher client = GO_GRPC_CLIENT.matcher(name);
        if (client.matches()) { out.add("rpcClients", caller + ": " + client.group(1)); return; }
        if (tryMessageCall(out, caller, name, operand, args)) return;

        if (operand.equals("http")) {
            // net/http client helpers; http.HandleFunc/Handle register routes on the default mux
            String httpMethod = CONFIG.httpMethodFor("go", "http", name);
            if (httpMethod != null) {
                if (!args.isEmpty()) out.add("httpCalls", caller + ": " + httpMethod + " " + urlOf(args.get(0), out));
                return;
            }
            if (name.equals("NewRequest") || name.equals("NewRequestWithContext")) {
                int i = name.equals("NewRequest") ? 0 : 1;
                if (args.size() > i + 1) {
                    out.add("httpCalls", caller + ": " + goHttpMethod(out.text(args.get(i))) + " " + urlOf(args.get(i + 1), out));
                }
                return;
            }
        }
        // gorilla/mux builder-chain idiom with the verb FIRST: r.Methods("GET").Path("/login").Handler(...)
        // or r.Methods("GET").PathPrefix("/customers").Handler(...) - common in older (pre-go-modules)
        // services (e.g. sock-shop's user/payment/catalogue, #16 follow-up); distinct from the
        // HandleFunc(path, h).Methods(...) chain already handled below, where the verb comes last.
        if ((name.equals("Path") || name.equals("PathPrefix")) && !args.isEmpty()) {
            SNode operandNode = fn.field("operand");
            if (operandNode != null && operandNode.type.equals("call_expression")) {
                SNode operandFn = operandNode.field("function");
                if (operandFn != null && operandFn.type.equals("selector_expression")
                        && out.text(operandFn.field("field")).equals("Methods")) {
                    List<String> verbs = new ArrayList<>();
                    for (SNode a : args(operandNode, out)) {
                        String v = goHttpMethod(out.text(a));
                        if (!v.startsWith("{")) verbs.add(v);
                    }
                    String chainUrl = urlOf(args.get(0), out);
                    if (looksLikePath(chainUrl)) {
                        out.add("endpoints", (verbs.isEmpty() ? "ANY" : String.join(",", verbs)) + " "
                                + withGoPrefix(operand, chainUrl, out));
                        return;
                    }
                }
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
        out.add("endpoints", method + " " + withGoPrefix(operand, url, out));
    }

    /** Prepends a tracked sub-router/group's own prefix (see the short_var_declaration pass in go()
     *  above) - a no-op if operand isn't one. */
    private static String withGoPrefix(String operand, String url, Out out) {
        return joinPaths(out.goRouterPrefixes.get(operand), url);
    }

    private static String goHttpMethod(String arg) {
        Matcher m = GO_HTTP_METHOD.matcher(arg);
        return m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)).toUpperCase() : "{" + arg + "}";
    }

    private static String goReceiverType(SNode methodDecl, Out out) {
        SNode recv = methodDecl.field("receiver");
        if (recv == null) return "";
        SNode param = recv.firstChildOfType("parameter_declaration");
        return param == null ? "" : out.text(param.field("type")).replace("*", "");
    }

    private static String goEnclosingName(SNode node, Out out) {
        SNode fn = node.ancestorOfType("function_declaration", "method_declaration");
        if (fn == null) return "<module>";
        String name = out.text(fn.field("name"));
        return fn.type.equals("method_declaration") ? goReceiverType(fn, out) + "." + name : name;
    }

    private static List<String> goStructFields(SNode structType, Out out) {
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

    private static String goParamList(SNode params, Out out) {
        if (params == null) return "";
        List<String> parts = new ArrayList<>();
        for (SNode p : params.namedChildren()) {
            if (!p.type.equals("parameter_declaration") && !p.type.equals("variadic_parameter_declaration")) continue;
            SNode name = p.field("name");
            SNode type = p.field("type");
            String t = type == null ? "" : out.text(type);
            parts.add(t + (name != null ? " " + out.text(name) : ""));
        }
        return String.join(", ", parts);
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
                default -> { }
            }
        });
    }

    /**
     * ASP.NET controllers. Attribute routing: [Route] on the class + [HttpGet("...")] on actions.
     * Conventional routing (an MVC controller with no route template anywhere on the class or action -
     * routed instead by the app's default "{controller=Home}/{action=Index}/{id?}" map): the path is
     * /{Controller}/{Action}, plus /{id} when the action takes a parameter named id, and a public action
     * with no verb attribute at all accepts any verb.
     */
    private void csharpController(SNode cls, Out out) {
        String className = out.text(cls.field("name"));
        String controllerName = className.replaceAll("Controller$", "");
        String prefix = attributeArg(cls, "Route", out);
        if (prefix != null) prefix = prefix.replace("[controller]", controllerName);
        boolean conventional = prefix == null && className.endsWith("Controller")
                && csharpSupertypes(cls, out).contains("Controller");
        SNode body = cls.field("body");
        if (body == null) return;
        for (SNode m : body.namedChildren()) {
            if (!m.type.equals("method_declaration")) continue;
            String action = out.text(m.field("name"));
            String conventionalUrl = "/" + controllerName + "/" + action
                    + (csharpHasParameterNamed(m, "id", out) ? "/{id}" : "");
            if (conventional && csharpIsPublicAction(m, out) && !csharpHasVerbAttribute(m, out)) {
                out.add("endpoints", "ANY " + conventionalUrl + " -> " + className + "." + action);
                continue;
            }
            // A verb attribute's own path argument is often left blank, with the actual route template
            // given instead by a SEPARATE sibling [Route("...")] attribute on the same method (e.g.
            // pitstop's WorkshopPlanningController: "[HttpGet]\n[Route(\"{planningDate}\")]") - scan all
            // of this method's attribute_lists up front so either shape resolves correctly (#16 follow-up).
            String methodRoute = null;
            for (SNode list : m.namedChildren()) {
                if (!list.type.equals("attribute_list")) continue;
                for (SNode attr : list.namedChildren()) {
                    if (out.text(attr.field("name")).equals("Route")) methodRoute = firstAttributeArg(attr, out);
                }
            }
            for (SNode list : m.namedChildren()) {
                if (!list.type.equals("attribute_list")) continue;
                for (SNode attr : list.namedChildren()) {
                    Matcher verb = CS_HTTP_ATTR.matcher(out.text(attr.field("name")));
                    if (!verb.matches()) continue;
                    String ownArg = firstAttributeArg(attr, out);
                    String template = (ownArg == null || ownArg.isEmpty()) ? methodRoute : ownArg;
                    String url = (prefix == null && (template == null || template.isEmpty()) && className.endsWith("Controller"))
                            ? conventionalUrl
                            : "/" + String.join("/", java.util.stream.Stream.of(prefix, template)
                                    .filter(s -> s != null && !s.isEmpty()).map(s -> s.replaceAll("^/|/$", "")).toList());
                    out.add("endpoints", verb.group(1).toUpperCase() + " " + url + " -> " + className + "." + action);
                }
            }
        }
    }

    private static boolean csharpHasVerbAttribute(SNode method, Out out) {
        for (SNode list : method.namedChildren()) {
            if (!list.type.equals("attribute_list")) continue;
            for (SNode attr : list.namedChildren()) {
                String name = out.text(attr.field("name"));
                if (CS_HTTP_ATTR.matcher(name).matches() || name.equals("Route")) return true;
            }
        }
        return false;
    }

    /** Public, non-static, not marked [NonAction] - what ASP.NET's conventional router treats as an action. */
    private static boolean csharpIsPublicAction(SNode method, Out out) {
        boolean isPublic = false;
        for (SNode c : method.namedChildren()) {
            if (c.type.equals("modifier")) {
                String mod = out.text(c);
                if (mod.equals("public")) isPublic = true;
                if (mod.equals("static")) return false;
            }
            if (c.type.equals("attribute_list")) {
                for (SNode attr : c.namedChildren()) {
                    if (out.text(attr.field("name")).equals("NonAction")) return false;
                }
            }
        }
        return isPublic;
    }

    private static boolean csharpHasParameterNamed(SNode method, String paramName, Out out) {
        SNode params = method.field("parameters");
        if (params == null) return false;
        for (SNode p : params.namedChildren()) {
            if (p.type.equals("parameter") && paramName.equals(out.text(p.field("name")))) return true;
        }
        return false;
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
        String caller = csharpEnclosingName(call, out);

        if (name.equals("MapGrpcService") && nameNode.type.equals("generic_name")) {
            out.add("rpcServers", caller + ": " + lastSegment(out.text(nameNode.namedChild(1)).replaceAll("[<>]", "")));
            return;
        }
        SNode receiver = fn.field("expression");
        if (tryMessageCall(out, caller, name, receiver == null ? "" : out.text(receiver), args)) return;
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
                out.add("httpCalls", caller + ": " + name.replaceAll("(String|Stream|ByteArray|AsJson|FromJson)?Async$", "").toUpperCase()
                        + " " + urlOf(first, out));
            }
        }
    }

    private static String csharpQualifiedMethodName(SNode methodDecl, Out out) {
        String name = out.text(methodDecl.field("name"));
        SNode owner = methodDecl.ancestorOfType("class_declaration", "struct_declaration", "interface_declaration");
        return owner == null ? name : out.text(owner.field("name")) + "." + name;
    }

    private static String csharpEnclosingName(SNode node, Out out) {
        SNode fn = node.ancestorOfType("method_declaration", "constructor_declaration", "local_function_statement");
        if (fn == null) return "<module>";
        if (fn.type.equals("method_declaration")) return csharpQualifiedMethodName(fn, out);
        SNode name = fn.field("name");
        return name != null ? out.text(name) : "<anonymous>";
    }

    /** Plain fields and auto-properties directly on a class/struct body (not inherited, not local variables). */
    private static List<String> csharpFields(SNode decl, Out out) {
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

    private static String csharpParamList(SNode params, Out out) {
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
