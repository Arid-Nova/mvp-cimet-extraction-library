package edu.university.ecs.lab.experiments;

import edu.university.ecs.lab.common.models.enums.EndpointTemplate;
import edu.university.ecs.lab.common.models.enums.RestCallTemplate;
import org.treesitter.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static edu.university.ecs.lab.experiments.FileFacts.sortedAttributes;
import edu.university.ecs.lab.experiments.TsUtil.SNode;

/**
 * Tree-sitter re-implementation of CIMET's Java extraction ({@code SourceToObjectUtils}).
 *
 * The goal is to measure parser parity, so every CIMET rule (class-role detection, endpoint URL
 * building, REST call URL parsing, Feign / RepositoryRestResource handling) is ported as literally
 * as possible, including its quirks. The one thing tree-sitter cannot do is symbol resolution, so
 * the type of the object a method is called on is approximated from declarations in the same file
 * (see {@link #objectType}). That approximation is what the {@code methodCallObjectTypes} and
 * {@code restCalls} dimensions of the parity report measure.
 */
public class TreeSitterJavaExtractor {

    private static final Set<String> CLASS_OR_INTERFACE = Set.of("class_declaration", "interface_declaration");
    private static final Set<String> COMMENTS = Set.of("line_comment", "block_comment");
    private static final Set<String> NON_REFERENCE_TYPES = Set.of(
            "integral_type", "floating_point_type", "boolean_type", "array_type", "void_type");
    private static final String UNKNOWN_VALUE = "{?}";

    private final TSParser parser = new TSParser();

    public TreeSitterJavaExtractor() {
        parser.setLanguage(new TreeSitterJava());
    }

    /** One annotation, classified the same way JavaParser classifies AnnotationExpr subtypes. */
    private record Ann(SNode node, String name, Kind kind, Map<String, SNode> pairs, SNode single) {
        enum Kind { MARKER, SINGLE, NORMAL }
    }

    /** Per-file state (the equivalent of CIMET's static cu / className fields). */
    private final class Ctx {
        final byte[] bytes;
        final SNode root;
        final String className;
        final List<SNode> fieldDecls;

        Ctx(byte[] bytes, SNode root, String className) {
            this.bytes = bytes;
            this.root = root;
            this.className = className;
            this.fieldDecls = root.findAll("field_declaration", "constant_declaration");
        }

        String text(SNode n) { return TsUtil.text(n, bytes); }
    }

    public FileFacts extract(File file) throws Exception {
        byte[] bytes = TsUtil.stripUtf8Bom(Files.readAllBytes(file.toPath()));
        TSTree tree = parser.parseString(null, new String(bytes, StandardCharsets.UTF_8));
        Ctx ctx = new Ctx(bytes, TsUtil.materialize(tree), file.getName().replace(".java", ""));

        FileFacts facts = new FileFacts(file.getPath());
        // ERROR nodes; MISSING nodes only show up through hasError(), so count a file with only those as 1
        int errors = ctx.root.findAll("ERROR").size();
        facts.put("_syntaxErrors", String.valueOf(errors == 0 && tree.getRootNode().hasError() ? 1 : errors));

        List<SNode> classOrInterfaces = ctx.root.findAll("class_declaration", "interface_declaration");
        List<SNode> enums = ctx.root.findAll("enum_declaration");
        List<SNode> records = ctx.root.findAll("record_declaration");

        // Class-level annotations: annotations directly on a class/interface declaration (any depth)
        List<Ann> classAnnotations = new ArrayList<>();
        for (SNode n : ctx.root.findAll("annotation", "marker_annotation")) {
            SNode parent = n.parent;
            if (parent.type.equals("modifiers") && CLASS_OR_INTERFACE.contains(parent.parent.type)) {
                classAnnotations.add(toAnn(n, ctx));
            }
        }
        Ann requestMapping = classAnnotations.stream().filter(a -> a.name.equals("RequestMapping")).findFirst().orElse(null);

        Set<String> extendedForRole = new LinkedHashSet<>();
        if (!classOrInterfaces.isEmpty()) extendedForRole.addAll(extendedTypes(classOrInterfaces.get(0), ctx));
        String classRole = classRole(classAnnotations, extendedForRole);

        // prepareClass(): first class/interface wins, then enum, then record
        SNode decl;
        String classType;
        if (!classOrInterfaces.isEmpty()) {
            decl = classOrInterfaces.get(0);
            classType = decl.type.equals("class_declaration") ? "CLASS" : "INTERFACE";
        } else if (!enums.isEmpty()) {
            decl = enums.get(0);
            classType = "ENUM";
        } else if (!records.isEmpty()) {
            decl = records.get(0);
            classType = "RECORD";
        } else {
            return facts.put("classType", "NONE");
        }

        facts.put("classType", classType);
        facts.put("packageName", packageName(ctx));
        facts.put("classRole", classRole);
        facts.put("classModifiers", classModifiers(decl, classType, ctx));
        facts.put("supertypes", supertypes(decl, classType, ctx));
        facts.put("enumConstants", classType.equals("ENUM")
                ? decl.field("body").findAll("enum_constant").stream()
                    .filter(c -> c.ancestorOfType("enum_declaration") == decl)
                    .map(c -> ctx.text(c.field("name"))).toList()
                : List.of());

        List<String> annotations = ctx.root.findAll("annotation", "marker_annotation").stream()
                .map(n -> toAnn(n, ctx)).map(a -> a.name + sortedAttributes(attributes(a, ctx))).toList();
        facts.put("annotations", annotations);
        facts.put("fields", fields(ctx));

        MethodsOut out = methods(ctx, requestMapping);
        switch (classRole) {
            case "FEIGN_CLIENT" -> {
                facts.put("imports", List.of());
                facts.put("methods", out.signatures);
                facts.put("endpoints", List.of());
                facts.put("methodCalls", out.calls);
                facts.put("methodCallObjectTypes", out.callTypes);
                List<String> restCalls = new ArrayList<>(out.restCalls);
                restCalls.addAll(out.feignRestCalls);
                facts.put("restCalls", restCalls);
            }
            case "REP_REST_RSC" -> {
                String preUrl = "/" + ctx.className.toLowerCase().replace("repository", "") + "s";
                facts.put("imports", List.of());
                facts.put("methods", out.signatures);
                facts.put("endpoints", out.names.stream().map(n -> "GET " + preUrl + "/search/" + n + " -> " + n).toList());
                facts.put("methodCalls", out.calls);
                facts.put("methodCallObjectTypes", out.callTypes);
                facts.put("restCalls", out.restCalls);
            }
            default -> {
                facts.put("imports", imports(ctx));
                facts.put("methods", out.signatures);
                facts.put("endpoints", out.endpoints);
                facts.put("methodCalls", out.calls);
                facts.put("methodCallObjectTypes", out.callTypes);
                facts.put("restCalls", out.restCalls);
            }
        }
        return facts;
    }

    // ---------------------------------------------------------------- class level

    private String packageName(Ctx ctx) {
        SNode pkg = ctx.root.firstChildOfType("package_declaration");
        if (pkg == null) return null;
        return ctx.text(pkg.firstChildOfType("scoped_identifier", "identifier"));
    }

    private static String classRole(List<Ann> classAnnotations, Set<String> extendedTypes) {
        for (Ann a : classAnnotations) {
            switch (a.name) {
                case "RestController", "Controller": return "CONTROLLER";
                case "Service": return "SERVICE";
                case "Repository": return "REPOSITORY";
                case "RepositoryRestResource": return "REP_REST_RSC";
                case "Entity", "Embeddable": return "ENTITY";
                case "FeignClient": return "FEIGN_CLIENT";
                default: break;
            }
        }
        for (String t : extendedTypes) {
            if (t.equals("MongoRepository") || t.equals("CrudRepository")) return "REPOSITORY";
        }
        return "UNKNOWN";
    }

    /** JavaParser getExtendedTypes(): superclass for classes, extends-list for interfaces. */
    private List<String> extendedTypes(SNode decl, Ctx ctx) {
        List<String> out = new ArrayList<>();
        if (decl.type.equals("class_declaration")) {
            SNode sc = decl.field("superclass");
            if (sc != null && sc.namedChildCount() > 0) out.add(simpleTypeName(sc.namedChild(0), ctx));
        } else {
            SNode ext = decl.firstChildOfType("extends_interfaces");
            if (ext != null) out.addAll(typeList(ext, ctx));
        }
        return out;
    }

    private List<String> typeList(SNode holder, Ctx ctx) {
        SNode list = holder.firstChildOfType("type_list");
        if (list == null) return List.of();
        return list.namedChildren().stream().filter(n -> !COMMENTS.contains(n.type))
                .map(n -> simpleTypeName(n, ctx)).toList();
    }

    private List<String> supertypes(SNode decl, String classType, Ctx ctx) {
        List<String> out = new ArrayList<>();
        switch (classType) {
            case "CLASS" -> {
                extendedTypes(decl, ctx).forEach(t -> out.add("extends " + t));
                SNode ifaces = decl.field("interfaces");
                if (ifaces != null) typeList(ifaces, ctx).forEach(t -> out.add("implements " + t));
            }
            case "INTERFACE" -> extendedTypes(decl, ctx).forEach(t -> out.add("extends " + t));
            default -> {
                SNode ifaces = decl.field("interfaces");
                if (ifaces != null) typeList(ifaces, ctx).forEach(t -> out.add("implements " + t));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out)); // CIMET stores these in Sets
    }

    private String classModifiers(SNode decl, String classType, Ctx ctx) {
        Set<String> mods = modifierKeywords(decl);
        String protection = protection(mods);
        return switch (classType) {
            // JEnum / JRecord constructors hard-code these; prepareClass() only overrides what is shown
            case "ENUM" -> protection + " final=true static=true abstract=false";
            case "RECORD" -> protection + " final=true static=" + mods.contains("static") + " abstract=false";
            default -> protection + " final=" + mods.contains("final") + " static=" + mods.contains("static")
                    + " abstract=" + mods.contains("abstract");
        };
    }

    private static Set<String> modifierKeywords(SNode decl) {
        Set<String> mods = new HashSet<>();
        SNode m = decl.firstChildOfType("modifiers");
        if (m != null) {
            for (SNode c : m.children) if (!c.named) mods.add(c.type);
        }
        return mods;
    }

    private static String protection(Set<String> mods) {
        if (mods.contains("public")) return "PUBLIC";
        if (mods.contains("protected")) return "PROTECTED";
        if (mods.contains("private")) return "PRIVATE";
        return "PACKAGE_PRIVATE";
    }

    /** ClassOrInterfaceType.getNameAsString(): simple name, no package, no type arguments. */
    private String simpleTypeName(SNode type, Ctx ctx) {
        return switch (type.type) {
            case "generic_type" -> simpleTypeName(type.namedChild(0), ctx);
            case "scoped_type_identifier" -> simpleTypeName(type.namedChild(type.namedChildCount() - 1), ctx);
            case "annotated_type" -> simpleTypeName(type.namedChild(type.namedChildCount() - 1), ctx);
            default -> ctx.text(type);
        };
    }

    private List<String> imports(Ctx ctx) {
        List<String> out = new ArrayList<>();
        for (SNode imp : ctx.root.findAll("import_declaration")) {
            boolean isStatic = imp.children.stream().anyMatch(c -> c.type.equals("static"));
            boolean asterisk = imp.firstChildOfType("asterisk") != null;
            String name = ctx.text(imp.firstChildOfType("scoped_identifier", "identifier"));
            String prefix = isStatic ? "static " : "";
            if (asterisk) {
                out.add(prefix + name + ".*");
            } else {
                int dot = name.lastIndexOf('.');
                out.add(prefix + name.substring(0, Math.max(dot, 0)) + "." + name.substring(dot + 1));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- annotations

    private Ann toAnn(SNode node, Ctx ctx) {
        String name = ctx.text(node.field("name"));
        if (node.type.equals("marker_annotation")) {
            return new Ann(node, name, Ann.Kind.MARKER, Map.of(), null);
        }
        SNode args = node.field("arguments");
        List<SNode> items = args == null ? List.of() : args.namedChildren().stream()
                .filter(n -> !COMMENTS.contains(n.type)).toList();
        if (items.size() == 1 && !items.get(0).type.equals("element_value_pair")) {
            return new Ann(node, name, Ann.Kind.SINGLE, Map.of(), items.get(0));
        }
        Map<String, SNode> pairs = new LinkedHashMap<>();
        for (SNode item : items) {
            if (item.type.equals("element_value_pair")) {
                pairs.put(ctx.text(item.field("key")), item.field("value"));
            }
        }
        return new Ann(node, name, Ann.Kind.NORMAL, pairs, null);
    }

    /** Port of Annotation.parseAttributes(). */
    private Map<String, String> attributes(Ann a, Ctx ctx) {
        Map<String, String> attrs = new HashMap<>();
        switch (a.kind) {
            case SINGLE -> {
                if (a.single.type.equals("string_literal")) attrs.put("default", stringValue(a.single, ctx));
            }
            case NORMAL -> a.pairs.forEach((k, v) ->
                    attrs.put(k, v.type.equals("string_literal") ? stringValue(v, ctx) : ctx.text(v)));
            default -> { }
        }
        return attrs;
    }

    /** StringLiteralExpr.asString(): the literal's value with Java escapes resolved. */
    private String stringValue(SNode literal, Ctx ctx) {
        String raw = ctx.text(literal);
        if (raw.startsWith("\"\"\"")) {
            raw = raw.substring(3, raw.length() - 3);
        } else if (raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return unescapeJava(raw);
    }

    private static String unescapeJava(String s) {
        if (!s.contains("\\")) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) { sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                }
                default -> sb.append(n);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- fields

    private List<String> fields(Ctx ctx) {
        List<String> out = new ArrayList<>();
        for (SNode fd : ctx.fieldDecls) {
            Set<String> mods = modifierKeywords(fd);
            String type = ctx.text(fd.field("type"));
            for (SNode vd : fd.children) {
                if (!vd.type.equals("variable_declarator")) continue;
                SNode dims = vd.field("dimensions");
                SNode value = vd.field("value");
                out.add(ctx.text(vd.field("name")) + ":" + type + (dims == null ? "" : ctx.text(dims))
                        + " " + protection(mods) + " static=" + mods.contains("static") + " final=" + mods.contains("final")
                        + " init=" + (value == null ? "" : ctx.text(value)));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- methods

    private static final class MethodsOut {
        final List<String> names = new ArrayList<>();
        final List<String> signatures = new ArrayList<>();
        final List<String> endpoints = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<String> callTypes = new ArrayList<>();
        final List<String> restCalls = new ArrayList<>();
        final List<String> feignRestCalls = new ArrayList<>();
    }

    private record Param(String name, String type, boolean varArgs, List<Ann> annotations) {}

    private MethodsOut methods(Ctx ctx, Ann requestMapping) {
        MethodsOut out = new MethodsOut();
        for (SNode md : ctx.root.findAll("method_declaration")) {
            String name = ctx.text(md.field("name"));
            Set<String> mods = modifierKeywords(md);
            List<Param> params = parameters(md, ctx);

            List<String> paramStrings = new ArrayList<>();
            for (Param p : params) {
                List<String> anns = p.annotations.stream()
                        .map(a -> "@" + a.name + sortedAttributes(attributes(a, ctx))).sorted().toList();
                paramStrings.add(String.join("", anns) + p.type + (p.varArgs ? "..." : "") + " " + p.name);
            }
            Collections.sort(paramStrings);
            List<String> thrown = new ArrayList<>();
            SNode throwsNode = md.firstChildOfType("throws");
            if (throwsNode != null) {
                throwsNode.namedChildren().stream().filter(n -> !COMMENTS.contains(n.type))
                        .forEach(n -> thrown.add(ctx.text(n)));
            }
            Collections.sort(thrown);
            // JavaParser isAbstract(): explicit, or an interface member that is not static/default/private
            boolean inInterface = md.parent != null && md.parent.type.equals("interface_body");
            boolean isAbstract = mods.contains("abstract") || (inInterface
                    && !mods.contains("static") && !mods.contains("default") && !mods.contains("private"));
            out.names.add(name);
            out.signatures.add(name + "(" + String.join(", ", paramStrings) + ") " + protection(mods)
                    + " abstract=" + isAbstract + " static=" + mods.contains("static")
                    + " final=" + mods.contains("final") + " throws=" + thrown);

            // Endpoint: the first mapping annotation on the method wins
            SNode modifiers = md.firstChildOfType("modifiers");
            if (modifiers != null) {
                for (SNode an : modifiers.namedChildren()) {
                    if (!an.type.equals("annotation") && !an.type.equals("marker_annotation")) continue;
                    Ann ann = toAnn(an, ctx);
                    if (EndpointTemplate.ENDPOINT_ANNOTATIONS.contains(ann.name)) {
                        String[] ep = endpoint(requestMapping, ann, ctx);
                        out.endpoints.add(ep[0] + " " + ep[1] + " -> " + name);
                        out.feignRestCalls.add(ep[0] + " " + ep[1] + feignQueryParams(params, ctx) + " <- " + name);
                        break;
                    }
                }
            }

            // Method calls (JavaParser: methodDeclaration.findAll(MethodCallExpr.class))
            for (SNode call : md.findAll("method_invocation")) {
                String callName = ctx.text(call.field("name"));
                SNode object = call.field("object");
                String objectName = object != null && object.type.equals("identifier") ? ctx.text(object) : "";
                SNode args = call.field("arguments");
                List<SNode> argNodes = args == null ? List.of() : args.namedChildren().stream()
                        .filter(n -> !COMMENTS.contains(n.type)).toList();
                String paramContents = String.join(",", argNodes.stream().map(ctx::text).toList());
                String objectType = objectType(object, md, ctx);

                out.calls.add(name + ": " + objectName + "." + callName + "(" + paramContents + ")");
                out.callTypes.add(name + ": " + objectName + "." + callName + " : " + objectType);

                if (RestCallTemplate.REST_OBJECTS.contains(objectType) && RestCallTemplate.REST_METHODS.contains(callName)) {
                    String url = restCallUrl(call, argNodes, objectType, ctx);
                    if (!url.isEmpty()) {
                        out.restCalls.add(httpFromCallName(callName, argNodes, ctx) + " " + url + " <- " + name);
                    }
                }
            }
        }
        return out;
    }

    private List<Param> parameters(SNode md, Ctx ctx) {
        List<Param> params = new ArrayList<>();
        SNode formal = md.field("parameters");
        if (formal == null) return params;
        for (SNode p : formal.namedChildren()) {
            List<Ann> anns = new ArrayList<>();
            SNode mods = p.firstChildOfType("modifiers");
            if (mods != null) {
                for (SNode a : mods.namedChildren()) {
                    if (a.type.equals("annotation") || a.type.equals("marker_annotation")) anns.add(toAnn(a, ctx));
                }
            }
            if (p.type.equals("formal_parameter")) {
                SNode dims = p.field("dimensions");
                params.add(new Param(ctx.text(p.field("name")),
                        ctx.text(p.field("type")) + (dims == null ? "" : ctx.text(dims)), false, anns));
            } else if (p.type.equals("spread_parameter")) {
                SNode type = p.namedChildren().stream()
                        .filter(n -> !n.type.equals("modifiers") && !n.type.equals("variable_declarator"))
                        .findFirst().orElse(null);
                SNode vd = p.firstChildOfType("variable_declarator");
                params.add(new Param(ctx.text(vd.field("name")), ctx.text(type), true, anns));
            }
        }
        return params;
    }

    /** Port of EndpointTemplate: returns {httpMethod, url}. */
    private String[] endpoint(Ann requestMapping, Ann mapping, Ctx ctx) {
        String preUrl = "";
        if (requestMapping != null) {
            if (requestMapping.kind == Ann.Kind.NORMAL) {
                SNode v = requestMapping.pairs.get("value");
                if (v != null) preUrl = ctx.text(v).replace("\"", "");
            } else if (requestMapping.kind == Ann.Kind.SINGLE) {
                preUrl = ctx.text(requestMapping.single).replace("\"", "");
            }
        }

        String http = "ALL";
        String url = "";
        if (mapping.kind == Ann.Kind.NORMAL) {
            for (Map.Entry<String, SNode> pair : mapping.pairs.entrySet()) {
                if (pair.getKey().equals("method")) {
                    http = httpFromMapping(ctx.text(pair.getValue()));
                } else if (pair.getKey().equals("path") || pair.getKey().equals("value")) {
                    url = ctx.text(pair.getValue()).replace("\"", "");
                }
            }
        } else if (mapping.kind == Ann.Kind.SINGLE) {
            url = ctx.text(mapping.single).replace("\"", "");
        } else if (preUrl.isEmpty()) {
            url = "/";
        }
        if (http.equals("ALL")) http = httpFromMapping(mapping.name);

        if (!preUrl.isEmpty() && !preUrl.startsWith("/")) {
            preUrl = "/" + preUrl;
        } else if (!url.isEmpty() && !url.startsWith("/")) {
            url = "/" + url;
        }
        String finalUrl = preUrl.isEmpty() && url.isEmpty() ? "/" : preUrl + url;
        finalUrl = finalUrl.replaceAll("//", "/");
        if (finalUrl.endsWith("/") && !finalUrl.equals("/")) finalUrl = finalUrl.substring(0, finalUrl.length() - 1);
        return new String[]{http, EndpointTemplate.simplifyEndpointURL(finalUrl)};
    }

    private static String httpFromMapping(String mapping) {
        return switch (mapping) {
            case "GetMapping", "RequestMethod.GET" -> "GET";
            case "PostMapping", "RequestMethod.POST" -> "POST";
            case "DeleteMapping", "RequestMethod.DELETE" -> "DELETE";
            case "PutMapping", "RequestMethod.PUT" -> "PUT";
            case "PatchMapping", "RequestMethod.PATCH" -> "PATCH";
            default -> "ALL";
        };
    }

    /** Query string CIMET appends to Feign client rest calls from @RequestParam parameters. */
    private String feignQueryParams(List<Param> params, Ctx ctx) {
        StringBuilder q = new StringBuilder();
        for (Param p : params) {
            for (Ann a : p.annotations) {
                if (!a.name.equals("RequestParam")) continue;
                Map<String, String> attrs = attributes(a, ctx);
                q.append("&").append(attrs.getOrDefault("default", attrs.getOrDefault("name", p.name))).append("={?}");
            }
        }
        if (!q.isEmpty()) q.replace(0, 1, "?");
        return q.toString();
    }

    // ---------------------------------------------------------------- type approximation

    /**
     * CIMET resolves the type of the call's scope with the JavaParser symbol solver. Tree-sitter has
     * no type information, so look the scope up in declarations of the same file instead.
     */
    private String objectType(SNode object, SNode method, Ctx ctx) {
        if (object == null) return "";
        switch (object.type) {
            case "identifier" -> {
                String name = ctx.text(object);
                SNode type = declaredType(name, method, ctx);
                if (type != null) return typeToSimpleName(type, ctx);
                // Unknown identifier: most likely a class name used for a static call (Math.max(...))
                return Character.isUpperCase(name.charAt(0)) ? name : "";
            }
            case "field_access" -> {
                SNode obj = object.field("object");
                if (obj != null && obj.type.equals("this")) {
                    SNode type = fieldType(ctx.text(object.field("field")), ctx);
                    return type == null ? "" : typeToSimpleName(type, ctx);
                }
                return "";
            }
            case "this" -> {
                SNode cls = object.ancestorOfType("class_declaration", "enum_declaration", "record_declaration");
                return cls == null ? "" : ctx.text(cls.field("name"));
            }
            case "string_literal" -> { return "String"; }
            case "object_creation_expression" -> { return typeToSimpleName(object.field("type"), ctx); }
            case "parenthesized_expression" -> {
                SNode inner = object.namedChild(0);
                return inner.type.equals("cast_expression") ? typeToSimpleName(inner.field("type"), ctx) : "";
            }
            default -> { return ""; }
        }
    }

    private String typeToSimpleName(SNode type, Ctx ctx) {
        if (type == null || NON_REFERENCE_TYPES.contains(type.type)) return "";
        return simpleTypeName(type, ctx);
    }

    /** Local variable, parameter, for-each / catch variable, then field. Ignores block scoping. */
    private SNode declaredType(String name, SNode method, Ctx ctx) {
        for (SNode n : method.findAll("local_variable_declaration", "formal_parameter", "spread_parameter",
                "enhanced_for_statement", "catch_formal_parameter", "resource")) {
            switch (n.type) {
                case "local_variable_declaration" -> {
                    for (SNode vd : n.children) {
                        if (vd.type.equals("variable_declarator") && ctx.text(vd.field("name")).equals(name)) {
                            SNode type = n.field("type");
                            SNode value = vd.field("value");
                            if (ctx.text(type).equals("var") && value != null && value.type.equals("object_creation_expression")) {
                                return value.field("type");
                            }
                            return type;
                        }
                    }
                }
                case "formal_parameter", "enhanced_for_statement", "resource" -> {
                    if (name.equals(ctx.text(n.field("name")))) return n.field("type");
                }
                case "spread_parameter" -> {
                    SNode vd = n.firstChildOfType("variable_declarator");
                    if (vd != null && name.equals(ctx.text(vd.field("name")))) return n.firstChildOfType("array_type");
                }
                case "catch_formal_parameter" -> {
                    if (name.equals(ctx.text(n.field("name")))) {
                        SNode ct = n.firstChildOfType("catch_type");
                        return ct == null ? null : ct.namedChild(0);
                    }
                }
                default -> { }
            }
        }
        return fieldType(name, ctx);
    }

    private SNode fieldType(String name, Ctx ctx) {
        for (SNode fd : ctx.fieldDecls) {
            for (SNode vd : fd.children) {
                if (vd.type.equals("variable_declarator") && ctx.text(vd.field("name")).equals(name)) {
                    return fd.field("type");
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- rest calls (port of RestCallTemplate)

    private String restCallUrl(SNode call, List<SNode> args, String objectType, Ctx ctx) {
        SNode urlArg;
        if (objectType.equals("WebClient")) {
            SNode parent = call.parent;
            if (!parent.type.equals("method_invocation")) return "";
            SNode pargs = parent.field("arguments");
            List<SNode> pArgNodes = pargs == null ? List.of() : pargs.namedChildren().stream()
                    .filter(n -> !COMMENTS.contains(n.type)).toList();
            if (pArgNodes.isEmpty()) return ""; // CIMET throws IndexOutOfBoundsException here
            urlArg = pArgNodes.get(0);
        } else {
            if (args.isEmpty()) return "";
            urlArg = args.get(0);
        }
        return RestCallTemplate.simplifyEndpointURL(cleanURL(parseURL(urlArg, ctx)));
    }

    private String parseURL(SNode exp, Ctx ctx) {
        switch (exp.type) {
            case "string_literal" -> { return stringValue(exp, ctx); }
            case "field_access" -> { return parseFieldValue(ctx.text(exp.field("field")), ctx); }
            case "binary_expression" -> {
                return parseURL(exp.field("left"), ctx) + parseURL(exp.field("right"), ctx);
            }
            // CIMET recurses on the same EnclosedExpr here and overflows the stack; we unwrap instead
            case "parenthesized_expression" -> { return parseURL(exp.namedChild(0), ctx); }
            case "method_invocation" -> {
                String backup = backupParseURL(exp, ctx);
                return backup.isEmpty() ? UNKNOWN_VALUE : backup;
            }
            case "identifier" -> {
                String name = ctx.text(exp);
                return name.contains("uri") || name.contains("url") ? "" : UNKNOWN_VALUE;
            }
            default -> { return backupParseURL(exp, ctx); }
        }
    }

    private static final Pattern BACKUP_URL = Pattern.compile("\".*(/.+?)\"");

    private String backupParseURL(SNode exp, Ctx ctx) {
        Matcher matcher = BACKUP_URL.matcher(ctx.text(exp));
        if (matcher.find()) {
            String extracted = matcher.group(0).replace("\"", "").replaceAll("%[sdif]", UNKNOWN_VALUE);
            return cleanURL(extracted);
        }
        return "";
    }

    private static String cleanURL(String str) {
        str = str.replace("http://", "").replace("https://", "");
        int slash = str.indexOf("/");
        if (slash > 0) str = str.substring(slash);
        if (str.endsWith("\"")) str = str.substring(0, str.length() - 1);
        if (str.endsWith("/")) str = str.substring(0, str.length() - 1);
        return str;
    }

    private String parseFieldValue(String fieldName, Ctx ctx) {
        for (SNode fd : ctx.fieldDecls) {
            List<SNode> declarators = fd.children.stream().filter(c -> c.type.equals("variable_declarator")).toList();
            String variables = String.join(", ", declarators.stream().map(ctx::text).toList());
            if (variables.contains(fieldName)) {
                SNode init = declarators.get(0).field("value");
                if (init != null) {
                    String s = ctx.text(init);
                    return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
                }
            }
        }
        return "";
    }

    private String httpFromCallName(String name, List<SNode> args, Ctx ctx) {
        return switch (name) {
            case "getForObject", "get" -> "GET";
            case "postForObject", "post" -> "POST";
            case "patchForObject", "patch" -> "PATCH";
            case "put" -> "PUT";
            case "delete" -> "DELETE";
            case "exchange" -> {
                String joined = String.join("", args.stream().map(ctx::text).toList());
                if (joined.contains("HttpMethod.POST")) yield "POST";
                if (joined.contains("HttpMethod.PUT")) yield "PUT";
                if (joined.contains("HttpMethod.DELETE")) yield "DELETE";
                if (joined.contains("HttpMethod.PATCH")) yield "PATCH";
                yield "GET";
            }
            default -> "NONE";
        };
    }
}
