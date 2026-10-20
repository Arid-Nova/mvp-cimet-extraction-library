package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.parsers.treesitter.FileFacts;

import java.nio.file.Path;
import java.util.*;
import org.json.JSONObject;

/**
 * Converts the flat, string-based facts produced by {@code PolyglotExtractor} (the tree-sitter
 * extractor for Python, Go, C# and JavaScript) into the real IR object graph
 * ({@link AbstractClass}/{@link Method}/{@link Endpoint}/{@link RestCall}/etc.) that the rest of the
 * pipeline expects.
 *
 * <p><b>One {@link JClass} per real type declared in the file</b> (not one per file): a file with
 * several classes/structs (measured as common — 20/57 files in a real repo, 13/68 in another) gets one
 * container per class, each with its own correct role, not just the first one with everything else's
 * fields and methods silently folded in. Fields, methods, and outgoing calls are attributed to the
 * class that actually owns them via a "ClassName.memberName" prefix {@code PolyglotExtractor} now
 * emits wherever the source structurally makes that ownership unambiguous.
 *
 * <p><b>The "loose function" fallback:</b> Python/JS/Go frequently have functions that don't belong to
 * any class at all (e.g. a bare Flask route), which the IR model has no place for — every
 * {@link Method} must belong to an {@link AbstractClass}. A synthetic {@link JClass} named after the
 * file (role UNKNOWN) is created on demand to hold these, and anything else that can't be matched to a
 * real class by name (there should normally be nothing).
 *
 * <p><b>Path identity:</b> the first (or only) type in the file keeps the real file path, so the
 * common single-class-per-file case is unaffected; additional types in the same file get a
 * disambiguated portable path ({@code <path>.__types__/<TypeName>-<hash>}), since two different {@link AbstractClass}es sharing
 * one path would otherwise collide with path-keyed matching elsewhere in the pipeline (e.g. delta
 * comparison, path-based test lookups like {@code TestUtilities.deepCompareSystems}).
 *
 * <p><b>Known gaps, still deliberately left out rather than modeled incorrectly:</b> parameter/field
 * types are the literal source text (e.g. "List&lt;string&gt;"), not resolved/simplified type names;
 * and the type an outgoing call's target object resolves to isn't tracked.
 */
public final class PolyglotFactsToIrConverter {

    private static final String MODULE_CONTAINER = "<module>";

    private PolyglotFactsToIrConverter() {}

    /** One real class/struct/interface found in the file, or the synthetic "loose function" container. */
    private record ClassSlot(AbstractClass abstractClass, String name) {}

    public static AbstractClass convert(Microservice microservice, Path gitPath, String fileBaseName, FileFacts facts,
                                         boolean filterOutUnknownClassRoles) {
        List<AbstractClass> classes = convertAll(microservice, gitPath, fileBaseName, facts, filterOutUnknownClassRoles);
        // Compatibility with the original one-result contract. New dispatch consumes convertAll.
        classes.stream().skip(1).forEach(microservice::addAbstractClass);
        return classes.isEmpty() ? null : classes.getFirst();
    }

    /** Converts every declared type without adding nodes to the supplied microservice. */
    public static List<AbstractClass> convertAll(Microservice microservice, Path gitPath, String fileBaseName, FileFacts facts,
                                                 boolean filterOutUnknownClassRoles) {
        List<String[]> types = parseTypes(facts); // {name, role}, in declaration order
        Map<String, ClassSlot> byName = new LinkedHashMap<>();

        if (types.isEmpty()) {
            // No real class/struct/interface in this file at all (a common case - a bare Flask/Express
            // module, a Go file of package-level functions): this container IS the module-level
            // fallback, stored under that key so resolve() reuses it instead of creating a second one.
            byName.put(MODULE_CONTAINER, newSlot(microservice, gitPath, fileBaseName, ClassRole.UNKNOWN));
        } else {
            boolean first = true;
            for (String[] type : types) {
                Path path = first ? gitPath : typeIdentity(gitPath, type[0]);
                byName.put(type[0], newSlot(microservice, path, type[0], roleOf(type[1])));
                first = false;
            }
        }
        // Imports/annotations aren't attributed to a specific class in the extracted facts, so they all
        // go on the file's first (or only) class - a known first-pass imprecision when a file has more
        // than one real class, no worse than the "one JClass per file" shortcut this replaces.
        AbstractClass primary = byName.values().iterator().next().abstractClass();
        for (String imp : facts.get("imports")) addImport(primary, imp);
        for (String ann : facts.get("annotations")) addAnnotation(primary, ann);

        for (String f : facts.get("fields")) {
            int dot = f.indexOf('.');
            String owner = dot < 0 ? null : f.substring(0, dot);
            String rest = dot < 0 ? f : f.substring(dot + 1);
            addField(resolve(byName, owner, microservice, gitPath, fileBaseName), rest);
        }

        Map<String, String> paramSignatures = parseSignatures(facts);
        Map<String, Method> methodsByQualifiedName = new LinkedHashMap<>();

        Set<String> endpointNames = new HashSet<>();
        int i = 0;
        for (String endpointFact : facts.get("endpoints")) {
            i++;
            EndpointFact ef = parseEndpointFact(endpointFact, i);
            AbstractClass target = resolve(byName, ownerOf(ef.name()), microservice, gitPath, fileBaseName);
            if (target.getClassRole() == ClassRole.UNKNOWN) target.setClassRole(ClassRole.CONTROLLER);
            Method base = new Method(target, ef.name(), new Location());
            Endpoint endpoint = new Endpoint(base, ef.url(), ef.httpMethod());
            attachParameters(endpoint, ef.name(), paramSignatures);
            target.getMethods().add(endpoint);
            methodsByQualifiedName.put(ef.name(), endpoint);
            endpointNames.add(ef.name());
        }

        // Every detected function becomes a plain Method, unless it was already added as an Endpoint
        // above (endpoint facts carry the same qualified name for the languages that report one; a
        // same-named function may legitimately show up as both — harmless duplication, not double
        // counted incorrectly, just a known first-pass imprecision).
        for (String fn : facts.get("functions")) {
            if (endpointNames.contains(fn)) continue;
            AbstractClass target = resolve(byName, ownerOf(fn), microservice, gitPath, fileBaseName);
            Method method = new Method(target, fn, new Location());
            attachParameters(method, fn, paramSignatures);
            target.getMethods().add(method);
            methodsByQualifiedName.put(fn, method);
        }

        attachHttpCalls(byName, methodsByQualifiedName, facts.get("httpCalls"), microservice, gitPath, fileBaseName);
        attachRpcFacts(byName, methodsByQualifiedName, facts.get("rpcServers"), "grpc-server", microservice, gitPath, fileBaseName);
        attachRpcFacts(byName, methodsByQualifiedName, facts.get("rpcClients"), "grpc-client", microservice, gitPath, fileBaseName);

        // Filtering applies uniformly before the caller attaches the complete physical-file result.
        List<AbstractClass> converted = new ArrayList<>();
        for (ClassSlot slot : byName.values()) {
            AbstractClass ac = slot.abstractClass();
            ac.setMetadata(new JSONObject().put("sourcePath", gitPath.toString().replace('\\', '/'))
                    .put("declaredType", slot.name()).put("extractor", "cimet-tree-sitter")
                    .put("roleResolution", "framework-and-name-heuristics"));
            if (filterOutUnknownClassRoles && ac.getClassRole() == ClassRole.UNKNOWN) continue;
            converted.add(ac);
        }
        return List.copyOf(converted);
    }

    private static Path typeIdentity(Path source, String name) {
        // No colons, angle brackets, or Windows device names. sourcePath metadata retains physical identity.
        String safe = "_" + name.replaceAll("[^a-zA-Z0-9._-]", "_") + "-" + Integer.toUnsignedString(name.hashCode(), 16);
        return Path.of(source + ".__types__").resolve(safe);
    }

    private static ClassSlot newSlot(Microservice microservice, Path path, String name, ClassRole role) {
        JClass abstractClass = new JClass(microservice, path, null, "", Set.of());
        abstractClass.setName(name);
        abstractClass.setClassRole(role);
        return new ClassSlot(abstractClass, name);
    }

    /** name.rest -> owning class name if known; a bare (unqualified) name -> module-level. */
    private static String ownerOf(String qualifiedName) {
        int dot = qualifiedName.indexOf('.');
        return dot < 0 ? null : qualifiedName.substring(0, dot);
    }

    /** Looks up a known class by name, creating the loose-function fallback container on first need. */
    private static AbstractClass resolve(Map<String, ClassSlot> byName, String ownerName, Microservice microservice,
                                          Path gitPath, String fileBaseName) {
        if (ownerName != null) {
            ClassSlot slot = byName.get(ownerName);
            if (slot != null) return slot.abstractClass();
        }
        ClassSlot module = byName.get(MODULE_CONTAINER);
        if (module == null) {
            // byName always already holds at least the file's first real class (or its single fallback
            // container) by the time this runs, so the module container never gets the bare file path.
            module = newSlot(microservice, typeIdentity(gitPath, MODULE_CONTAINER), fileBaseName, ClassRole.UNKNOWN);
            byName.put(MODULE_CONTAINER, module);
        }
        return module.abstractClass();
    }

    private static void addImport(AbstractClass owner, String imp) {
        Import i = new Import();
        i.setParent(Optional.of(owner));
        i.setName(imp);
        i.setImportPackage(imp);
        i.setImportObject("*");
        i.setIsStatic(false);
        owner.getImports().add(i);
    }

    private static void addAnnotation(AbstractClass owner, String ann) {
        Annotation a = new Annotation();
        a.setParent(Optional.of(owner));
        a.setName(ann);
        a.setAttributes(Map.of());
        owner.getAnnotations().add(a);
    }

    private static void addField(AbstractClass owner, String fieldFact) {
        int colon = fieldFact.indexOf(':');
        String name = colon < 0 ? fieldFact : fieldFact.substring(0, colon);
        String type = colon < 0 ? "" : fieldFact.substring(colon + 1);
        Field field = new Field();
        field.setParent(Optional.of(owner));
        field.setName(name);
        field.setFieldType(type);
        field.setProtection(AccessModifier.PACKAGE_PRIVATE);
        field.setIsStatic(false);
        field.setIsFinal(false);
        field.setInitializer("");
        owner.getFields().add(field);
    }

    /** "class X" / "struct X" / "interface X" -> {X, role}; role looked up from the matching classRole fact. */
    private static List<String[]> parseTypes(FileFacts facts) {
        List<String[]> types = new ArrayList<>();
        for (String t : facts.get("types")) {
            int space = t.indexOf(' ');
            String name = space < 0 ? t : t.substring(space + 1);
            types.add(new String[]{name, roleStringFor(name, facts)});
        }
        return types;
    }

    private static String roleStringFor(String className, FileFacts facts) {
        for (String entry : facts.get("classRole")) {
            int colon = entry.lastIndexOf(':');
            if (colon >= 0 && entry.substring(0, colon).equals(className)) return entry.substring(colon + 1);
        }
        return "UNKNOWN";
    }

    private static ClassRole roleOf(String roleString) {
        try {
            return ClassRole.valueOf(roleString);
        } catch (IllegalArgumentException ignored) {
            return ClassRole.UNKNOWN;
        }
    }

    private record EndpointFact(HttpMethod httpMethod, String url, String name) {}

    /** Parses "GET /path -> methodName" or "GET,HEAD /path" (no arrow) - object construction happens
     * in the caller, once the owning class (from a possible "ClassName." prefix on name) is resolved. */
    private static EndpointFact parseEndpointFact(String fact, int index) {
        int firstSpace = fact.indexOf(' ');
        String methodPart = firstSpace < 0 ? fact : fact.substring(0, firstSpace);
        String rest = firstSpace < 0 ? "" : fact.substring(firstSpace + 1);

        String url;
        String name;
        int arrow = rest.indexOf(" -> ");
        if (arrow >= 0) {
            url = rest.substring(0, arrow);
            name = rest.substring(arrow + 4);
        } else {
            url = rest;
            name = "endpoint_" + index;
        }
        return new EndpointFact(httpMethodOf(methodPart.split(",")[0]), url, name);
    }

    private static HttpMethod httpMethodOf(String token) {
        try {
            return HttpMethod.valueOf(token);
        } catch (IllegalArgumentException e) {
            return HttpMethod.ALL; // covers our own "ANY" convention and anything unrecognized
        }
    }

    /** "name(type1 p1, type2 p2)" facts -> {name: "type1 p1, type2 p2"}. */
    private static Map<String, String> parseSignatures(FileFacts facts) {
        Map<String, String> sigs = new HashMap<>();
        for (String p : facts.get("parameters")) {
            int paren = p.indexOf('(');
            if (paren < 0 || !p.endsWith(")")) continue;
            sigs.put(p.substring(0, paren), p.substring(paren + 1, p.length() - 1));
        }
        return sigs;
    }

    private static void attachParameters(Method method, String name, Map<String, String> signatures) {
        String sig = signatures.get(name);
        if (sig == null || sig.isBlank()) return;
        Set<Parameter> params = new LinkedHashSet<>();
        for (String part : sig.split(",\\s*")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int sp = part.lastIndexOf(' ');
            String type = sp < 0 ? "" : part.substring(0, sp).trim();
            String pname = (sp < 0 ? part : part.substring(sp + 1)).trim();
            Parameter param = new Parameter();
            param.setParent(Optional.of(method));
            param.setName(pname.isEmpty() ? "arg" : pname);
            param.setParameterType(type);
            param.setAnnotations(new HashSet<>());
            param.setIsVariableParameter(pname.startsWith("*"));
            params.add(param);
        }
        method.setParameters(params);
    }

    /** "callerName: GET url" facts, or bare "GET url" for a call made outside any function. */
    private static void attachHttpCalls(Map<String, ClassSlot> byName, Map<String, Method> methodsByQualifiedName,
                                         List<String> facts, Microservice microservice, Path gitPath, String fileBaseName) {
        for (String fact : facts) {
            String[] split = splitCaller(fact);
            Method method = getOrCreateMethod(byName, methodsByQualifiedName, split[0], microservice, gitPath, fileBaseName);

            String rest = split[1];
            int sp = rest.indexOf(' ');
            String methodToken = sp < 0 ? rest : rest.substring(0, sp);
            String url = sp < 0 ? "" : rest.substring(sp + 1);

            MethodCall call = new MethodCall(method, methodToken.toLowerCase(), new Location());
            call.setCalledFrom(split[0]);
            RestCall restCall = new RestCall(call, url, httpMethodOf(methodToken));
            method.getMethodCalls().add(restCall);
        }
    }

    /** "callerName: ServiceName" facts (gRPC server registrations / client stubs), same caller format. */
    private static void attachRpcFacts(Map<String, ClassSlot> byName, Map<String, Method> methodsByQualifiedName,
                                        List<String> facts, String kind, Microservice microservice, Path gitPath, String fileBaseName) {
        for (String fact : facts) {
            String[] split = splitCaller(fact);
            Method method = getOrCreateMethod(byName, methodsByQualifiedName, split[0], microservice, gitPath, fileBaseName);

            MethodCall call = new MethodCall(method, split[1], new Location());
            call.setCalledFrom(split[0]);
            call.setObjectType(kind);
            method.getMethodCalls().add(call);
        }
    }

    /** Splits a "callerName: rest" fact; facts without the "<name>: " prefix are treated as module-level. */
    private static String[] splitCaller(String fact) {
        int colon = fact.indexOf(": ");
        return colon < 0 ? new String[]{MODULE_CONTAINER, fact} : new String[]{fact.substring(0, colon), fact.substring(colon + 2)};
    }

    private static Method getOrCreateMethod(Map<String, ClassSlot> byName, Map<String, Method> methodsByQualifiedName,
                                             String qualifiedName, Microservice microservice, Path gitPath, String fileBaseName) {
        return methodsByQualifiedName.computeIfAbsent(qualifiedName, n -> {
            AbstractClass owner = resolve(byName, ownerOf(n), microservice, gitPath, fileBaseName);
            Method placeholder = new Method(owner, n, new Location());
            owner.getMethods().add(placeholder);
            return placeholder;
        });
    }
}
