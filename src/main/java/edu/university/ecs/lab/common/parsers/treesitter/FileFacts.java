package edu.university.ecs.lab.experiments;

import java.util.*;

/**
 * Parser-neutral snapshot of what was extracted from one source file.
 *
 * Every dimension is a sorted multiset of strings, so two extractors can be diffed dimension by
 * dimension regardless of how each one models the data internally.
 */
public class FileFacts {

    /** Dimensions compared for Java, in report order. */
    public static final List<String> JAVA_DIMENSIONS = List.of(
            "classType", "packageName", "classRole", "classModifiers", "supertypes", "enumConstants",
            "imports", "annotations", "fields", "methods", "endpoints",
            "methodCalls", "methodCallObjectTypes", "restCalls");

    /**
     * Dimensions CIMET stores in a {@code Set}. Component equality ignores source location, so
     * CIMET collapses duplicates (e.g. seven {@code @Override}s become one); these are compared as sets.
     */
    public static final Set<String> SET_DIMENSIONS = Set.of(
            "supertypes", "enumConstants", "imports", "annotations", "fields", "methods", "endpoints");

    private final String path;
    private final Map<String, List<String>> dimensions = new LinkedHashMap<>();

    public FileFacts(String path) {
        this.path = path;
    }

    public String getPath() { return path; }

    public Map<String, List<String>> getDimensions() { return dimensions; }

    public List<String> get(String dimension) {
        return dimensions.getOrDefault(dimension, List.of());
    }

    public FileFacts put(String dimension, Collection<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        dimensions.put(dimension, sorted);
        return this;
    }

    public FileFacts put(String dimension, String value) {
        return put(dimension, List.of(String.valueOf(value)));
    }

    /** Whitespace-insensitive form used for comparison (JavaParser pretty-prints, tree-sitter keeps source text). */
    public static String norm(String s) {
        return s == null ? "null" : s.replaceAll("\\s+", "");
    }

    public static String sortedAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) return "";
        return new TreeMap<>(attributes).toString();
    }
}
