package edu.university.ecs.lab.experiments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Declarative role-classification and REST-client rules for the non-Java languages, loaded from
 * {@code experiments/language-config.json}. Adapted from LaCIMET
 * (github.com/ShakthiYasas/LaCIMET, language_config.json + ir_generator.py) — the same style of
 * annotation/name-substring rule CIMET already uses for Java (see the original TreeSitterTest's
 * classifyRole, which is a hand-written Java copy of LaCIMET's Java role_patterns).
 *
 * <p>LaCIMET's own generator ({@code ir_generator.py::_categorize_artifacts}) only ever reads the
 * {@code annotations} and {@code name_contains} keys, silently ignoring {@code decorators} and
 * {@code supertypes}/{@code inheritance} even though its config declares them — for Python/JS/C#
 * that leaves role classification working off name substrings alone. This loader reads all four
 * kinds of rule, which is what the config was clearly meant to do.
 */
public final class LanguageConfig {

    private static final LanguageConfig INSTANCE = load();

    private final JsonNode root;

    private LanguageConfig(JsonNode root) { this.root = root; }

    public static LanguageConfig get() { return INSTANCE; }

    private static LanguageConfig load() {
        try (InputStream in = LanguageConfig.class.getResourceAsStream("/experiments/language-config.json")) {
            if (in == null) throw new IllegalStateException("experiments/language-config.json not found on classpath");
            return new LanguageConfig(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load language-config.json", e);
        }
    }

    /**
     * Classifies a type against {@code role_patterns}, role by role in the order declared in the
     * config (first match wins) — the same "first matching annotation wins" rule CIMET's
     * SourceToObjectUtils.parseClassRole uses for Java.
     *
     * @param annotationsOrDecorators annotation/decorator text found directly on the type (e.g. "RestController", "app.route")
     * @param supertypes              simple names of extended/implemented types
     * @return the role name (e.g. "CONTROLLER"), or "UNKNOWN" if nothing matched
     */
    public String classifyRole(String language, String typeName, Collection<String> annotationsOrDecorators,
                                Collection<String> supertypes) {
        JsonNode patterns = root.path(language).path("role_patterns");
        Iterator<Map.Entry<String, JsonNode>> roles = patterns.fields();
        while (roles.hasNext()) {
            Map.Entry<String, JsonNode> role = roles.next();
            JsonNode rule = role.getValue();
            boolean matches =
                    // annotation/decorator name contains one of the listed patterns (e.g. "RestController" contains "Controller")
                    containsPattern(rule.path("annotations"), annotationsOrDecorators, false)
                    || containsPattern(rule.path("decorators"), annotationsOrDecorators, false)
                    // type name contains one of the listed patterns, case-insensitive
                    || containsPattern(rule.path("name_contains"), java.util.List.of(typeName), true)
                    // a supertype exactly matches one of the listed patterns
                    || exactMatch(rule.path("supertypes"), supertypes);
            if (matches) return role.getKey();
        }
        return "UNKNOWN";
    }

    /** Looks up the HTTP method for a REST-client call, e.g. ("javascript", "axios", "get") -> "GET". */
    public String httpMethodFor(String language, String objectName, String methodName) {
        if (objectName == null || methodName == null) return null;
        JsonNode methods = root.path(language).path("rest_clients").path(objectName);
        JsonNode value = methods.path(methodName.toLowerCase());
        return value.isTextual() ? value.asText() : null;
    }

    private static boolean containsPattern(JsonNode patterns, Collection<String> values, boolean caseInsensitive) {
        if (!patterns.isArray() || values == null) return false;
        for (JsonNode p : patterns) {
            String pattern = caseInsensitive ? p.asText().toLowerCase() : p.asText();
            for (String v : values) {
                if (v == null) continue;
                if ((caseInsensitive ? v.toLowerCase() : v).contains(pattern)) return true;
            }
        }
        return false;
    }

    private static boolean exactMatch(JsonNode patterns, Collection<String> values) {
        if (!patterns.isArray() || values == null) return false;
        for (JsonNode p : patterns) {
            for (String v : values) if (p.asText().equals(v)) return true;
        }
        return false;
    }
}
