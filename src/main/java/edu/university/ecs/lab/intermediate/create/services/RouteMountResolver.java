package edu.university.ecs.lab.intermediate.create.services;

import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Endpoint;
import edu.university.ecs.lab.common.models.ir.Method;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.ProjectFile;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves Express.js sub-router mount prefixes across files within one microservice (#16 follow-up).
 *
 * <p>Tree-sitter extracts one file at a time with no cross-file visibility, so when a route is defined
 * on a router created in one file ({@code router.get("/:id", ...)}) and mounted at a prefix in a
 * DIFFERENT file ({@code app.use("/events", require("./routers/events-router"))}), the route's extracted
 * URL is missing that prefix entirely - it was never seen in the same file as the mount call. This was a
 * confirmed, real scoring gap (0% precision/recall against two independent ground-truth systems that use
 * exactly this pattern: node-js-restful-api-example, E-commerce-Microservice).
 *
 * <p>{@link edu.university.ecs.lab.common.parsers.treesitter.PolyglotExtractor} records each mount
 * ({@code app.use(prefix, routerVar)}) as a {@code routeMounts} fact on the mounting file, which
 * {@link edu.university.ecs.lab.common.parsers.PolyglotFactsToIrConverter} stashes into that class's
 * {@code metadata} (excluded from equals/hashCode - see {@code Node.metadata} - so this is purely
 * auxiliary, never part of a node's identity). This resolver runs once per {@link Microservice}, after
 * every one of its files has been parsed, which is the first point anything has visibility across files:
 * <ol>
 *   <li>Builds a mount graph: for every class with a stashed mount, resolve its target (a same-file
 *       router, or a require()'d relative path resolved against the mounting file's own directory) to
 *       the actual target class(es) in this microservice.</li>
 *   <li>Computes each class's cumulative effective prefix by walking that graph - correctly handling
 *       mounts nested more than one level deep (a router mounting a router mounting a router), not just
 *       the single-level case the two known failures happen to be.</li>
 *   <li>Prepends each class's computed prefix to its own endpoints' URLs, exactly once.</li>
 * </ol>
 *
 * <p>Known, accepted limitations (best-effort, consistent with the rest of this extractor's "no type
 * resolution" philosophy): a dynamic (non-literal) mount prefix becomes a named placeholder like
 * {@code {apiPrefix}}, same as elsewhere; a router mounted at more than one prefix only gets the first
 * one found; a require() path resolving to a directory (`./routers` -> `./routers/index.js`) is not
 * special-cased.
 */
public final class RouteMountResolver {

    private RouteMountResolver() {}

    public static void apply(Microservice microservice) {
        List<AbstractClass> classes = new ArrayList<>();
        for (ProjectFile file : microservice.getAllFiles()) {
            if (file instanceof AbstractClass abstractClass) {
                classes.add(abstractClass);
            }
        }
        if (classes.isEmpty()) {
            return;
        }

        Map<String, List<AbstractClass>> byNormalizedPath = new HashMap<>();
        for (AbstractClass abstractClass : classes) {
            byNormalizedPath.computeIfAbsent(normalizedKey(abstractClass.getPath()), key -> new ArrayList<>())
                    .add(abstractClass);
        }

        // incoming.get(target) = every (mountingClass, prefix) that routes into target
        Map<AbstractClass, List<Map.Entry<AbstractClass, String>>> incoming = new IdentityHashMap<>();
        for (AbstractClass mountingClass : classes) {
            JSONObject metadata = mountingClass.getMetadata();
            if (metadata == null || !metadata.has("routeMounts")) {
                continue;
            }
            JSONArray mounts = metadata.getJSONArray("routeMounts");
            for (int i = 0; i < mounts.length(); i++) {
                JSONObject mount = mounts.getJSONObject(i);
                String prefix = mount.optString("prefix", "");
                String target = mount.optString("target", "");
                List<AbstractClass> targetClasses = resolveTargets(mountingClass.getPath(), target, byNormalizedPath);
                for (AbstractClass targetClass : targetClasses) {
                    if (targetClass == mountingClass) {
                        continue; // a file "mounting" its own top-level router - not a real cross-reference
                    }
                    incoming.computeIfAbsent(targetClass, key -> new ArrayList<>())
                            .add(new AbstractMap.SimpleEntry<>(mountingClass, prefix));
                }
            }
        }
        if (incoming.isEmpty()) {
            return;
        }

        Map<AbstractClass, String> effectivePrefix = new IdentityHashMap<>();
        for (AbstractClass abstractClass : classes) {
            computeEffectivePrefix(abstractClass, incoming, effectivePrefix, new HashSet<>());
        }

        for (AbstractClass abstractClass : classes) {
            String prefix = effectivePrefix.get(abstractClass);
            if (prefix != null && !prefix.isEmpty()) {
                for (Method method : abstractClass.getMethods()) {
                    if (method instanceof Endpoint endpoint) {
                        endpoint.setUrl(joinPaths(prefix, endpoint.getUrl()));
                    }
                }
            }
        }

        // Purely a working scratchpad for this pass - strip it so it doesn't leak into the written IR.
        for (AbstractClass abstractClass : classes) {
            JSONObject metadata = abstractClass.getMetadata();
            if (metadata != null) {
                metadata.remove("routeMounts");
            }
        }
    }

    private static String computeEffectivePrefix(AbstractClass current,
                                                   Map<AbstractClass, List<Map.Entry<AbstractClass, String>>> incoming,
                                                   Map<AbstractClass, String> memo,
                                                   Set<AbstractClass> inProgress) {
        if (memo.containsKey(current)) {
            return memo.get(current);
        }
        List<Map.Entry<AbstractClass, String>> edges = incoming.get(current);
        if (edges == null || edges.isEmpty() || inProgress.contains(current)) {
            memo.put(current, "");
            return "";
        }

        inProgress.add(current);
        // A router mounted at more than one prefix is ambiguous for a single-URL-per-endpoint model;
        // take the first mount found, best effort (consistent with other "first match wins" rules
        // elsewhere in this extractor).
        Map.Entry<AbstractClass, String> edge = edges.get(0);
        String parentPrefix = computeEffectivePrefix(edge.getKey(), incoming, memo, inProgress);
        String result = joinPaths(parentPrefix, edge.getValue());
        inProgress.remove(current);
        memo.put(current, result);
        return result;
    }

    private static List<AbstractClass> resolveTargets(Path mountingFilePath, String target,
                                                        Map<String, List<AbstractClass>> byNormalizedPath) {
        Path resolved;
        if ("THIS_FILE".equals(target)) {
            resolved = mountingFilePath;
        } else {
            Path parent = mountingFilePath.getParent();
            if (parent == null) {
                return List.of();
            }
            try {
                resolved = parent.resolve(target).normalize();
            } catch (Exception e) {
                return List.of();
            }
        }

        List<AbstractClass> direct = byNormalizedPath.get(normalizedKey(resolved));
        if (direct != null) {
            return direct;
        }
        // require("./routers") resolving to routers/index.js, or "from .routes import x" resolving to
        // routes/__init__.py - same "a directory import resolves to its package's own index file" idea.
        for (String indexFile : List.of("index", "__init__")) {
            List<AbstractClass> viaIndex = byNormalizedPath.get(normalizedKey(resolved.resolve(indexFile)));
            if (viaIndex != null) {
                return viaIndex;
            }
        }
        return List.of();
    }

    // Both JS's require("./x") and Python's "from .x import y" omit the file extension; stripping it
    // from the real, stored file path too (applied identically on both sides, so it's a no-op wherever
    // they already match) is what makes the two resolve to the same key.
    private static final List<String> STRIPPABLE_EXTENSIONS = List.of(".js", ".py");

    private static String normalizedKey(Path path) {
        String s = path.toString().replace('\\', '/');
        for (String extension : STRIPPABLE_EXTENSIONS) {
            if (s.endsWith(extension)) {
                return s.substring(0, s.length() - extension.length());
            }
        }
        return s;
    }

    private static String joinPaths(String prefix, String path) {
        if (prefix == null || prefix.isEmpty()) {
            return path;
        }
        String normalizedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String normalizedPath = (path == null || path.isEmpty()) ? "" : (path.startsWith("/") ? path : "/" + path);
        String joined = normalizedPrefix + normalizedPath;
        return joined.isEmpty() ? "/" : joined;
    }
}
