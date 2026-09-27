package edu.university.ecs.lab.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Runs the original CIMET JavaParser pipeline and the tree-sitter port over every .java file of a
 * cloned repository and reports, per extracted dimension, how often they agree.
 *
 * Usage: JavaParityRunner [repoName] — the repo must be cloned under ./clone/&lt;repoName&gt;
 * (defaults to train-ticket-microservices-test). Writes output/experiments/java-parity-&lt;repo&gt;.json.
 *
 * Values are compared whitespace-insensitively ({@link FileFacts#norm}), because JavaParser
 * pretty-prints expressions while tree-sitter returns the original source text.
 */
public class JavaParityRunner {

    private static final int MAX_EXAMPLES_PER_DIMENSION = 8;

    /** Aggregated agreement for one dimension. */
    static final class DimStats {
        int filesCompared, filesEqual, cimetItems, tsItems, matchedItems, duplicatesCollapsedByCimet;
        final List<Map<String, Object>> examples = new ArrayList<>();

        double precision() { return tsItems == 0 ? 1.0 : (double) matchedItems / tsItems; }
        double recall() { return cimetItems == 0 ? 1.0 : (double) matchedItems / cimetItems; }
    }

    public static void main(String[] args) throws Exception {
        String repoName = args.length > 0 ? args[0] : "train-ticket-microservices-test";
        Path repo = Path.of(".", "clone", repoName);
        if (!Files.isDirectory(repo)) {
            throw new IllegalArgumentException("Clone the repository into " + repo + " first");
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(repo)) {
            files = walk.filter(p -> p.toString().endsWith(".java") && !p.toString().contains(".github"))
                    .sorted().toList();
        }

        CimetFactsExtractor cimet = new CimetFactsExtractor(repoName);
        TreeSitterJavaExtractor treeSitter = new TreeSitterJavaExtractor();

        Map<String, DimStats> stats = new LinkedHashMap<>();
        FileFacts.JAVA_DIMENSIONS.forEach(d -> stats.put(d, new DimStats()));
        List<Map<String, Object>> fileDiffs = new ArrayList<>();
        List<String> cimetParseFailures = new ArrayList<>();
        List<String> cimetCrashes = new ArrayList<>();
        List<String> treeSitterSyntaxErrors = new ArrayList<>();
        long cimetNanos = 0, treeSitterNanos = 0;
        int filesFullyEqual = 0, filesCompared = 0;

        for (Path path : files) {
            File file = path.toFile();
            String rel = repo.relativize(path).toString();

            boolean parseable = cimet.javaParserCanParse(file);

            FileFacts expected;
            long t0 = System.nanoTime();
            try {
                expected = cimet.extract(file);
            } catch (Throwable t) {
                cimetCrashes.add(rel + " (" + t.getClass().getSimpleName() + ")");
                expected = null;
            }
            long t1 = System.nanoTime();
            FileFacts actual = treeSitter.extract(file);
            long t2 = System.nanoTime();
            cimetNanos += t1 - t0;
            treeSitterNanos += t2 - t1;

            if (!actual.get("_syntaxErrors").equals(List.of("0"))) {
                treeSitterSyntaxErrors.add(rel + " (" + actual.get("_syntaxErrors").get(0) + " error nodes)");
            }
            if (!parseable) {
                // CIMET silently reuses the previous file's AST in this case, so its output is meaningless
                cimetParseFailures.add(rel);
                continue;
            }
            if (expected == null) continue;

            filesCompared++;
            boolean allEqual = true;
            Map<String, Object> diff = new LinkedHashMap<>();
            for (String dim : FileFacts.JAVA_DIMENSIONS) {
                List<String> exp = expected.get(dim).stream().map(FileFacts::norm).toList();
                List<String> act = actual.get(dim).stream().map(FileFacts::norm).toList();
                DimStats s = stats.get(dim);
                if (FileFacts.SET_DIMENSIONS.contains(dim)) {
                    s.duplicatesCollapsedByCimet += act.size() - new HashSet<>(act).size();
                    exp = exp.stream().distinct().toList();
                    act = act.stream().distinct().toList();
                }
                List<String> onlyCimet = multisetMinus(exp, act);
                List<String> onlyTs = multisetMinus(act, exp);

                s.filesCompared++;
                s.cimetItems += exp.size();
                s.tsItems += act.size();
                s.matchedItems += exp.size() - onlyCimet.size();
                if (onlyCimet.isEmpty() && onlyTs.isEmpty()) {
                    s.filesEqual++;
                    continue;
                }
                allEqual = false;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("onlyCimet", onlyCimet);
                d.put("onlyTreeSitter", onlyTs);
                diff.put(dim, d);
                if (s.examples.size() < MAX_EXAMPLES_PER_DIMENSION) {
                    Map<String, Object> ex = new LinkedHashMap<>();
                    ex.put("file", rel);
                    ex.putAll(d);
                    s.examples.add(ex);
                }
            }
            if (allEqual) {
                filesFullyEqual++;
            } else {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", rel);
                entry.put("differences", diff);
                fileDiffs.add(entry);
            }
        }

        printSummary(repoName, files.size(), filesCompared, filesFullyEqual, cimetNanos, treeSitterNanos,
                stats, cimetParseFailures, cimetCrashes, treeSitterSyntaxErrors);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("repository", repoName);
        report.put("javaFiles", files.size());
        report.put("filesCompared", filesCompared);
        report.put("filesFullyEqual", filesFullyEqual);
        report.put("cimetMillis", cimetNanos / 1_000_000);
        report.put("treeSitterMillis", treeSitterNanos / 1_000_000);
        report.put("cimetParseFailures", cimetParseFailures);
        report.put("cimetCrashes", cimetCrashes);
        report.put("treeSitterSyntaxErrors", treeSitterSyntaxErrors);
        Map<String, Object> dims = new LinkedHashMap<>();
        stats.forEach((dim, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filesEqual", s.filesEqual);
            m.put("filesCompared", s.filesCompared);
            m.put("cimetItems", s.cimetItems);
            m.put("treeSitterItems", s.tsItems);
            m.put("matchedItems", s.matchedItems);
            m.put("precision", s.precision());
            m.put("recall", s.recall());
            m.put("duplicatesCollapsedByCimet", s.duplicatesCollapsedByCimet);
            m.put("examples", s.examples);
            dims.put(dim, m);
        });
        report.put("dimensions", dims);
        report.put("fileDiffs", fileDiffs);

        Path out = Path.of("output", "experiments", "java-parity-" + repoName + ".json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        System.out.println("\nFull report: " + out);
    }

    private static List<String> multisetMinus(List<String> a, List<String> b) {
        Map<String, Integer> counts = new HashMap<>();
        b.forEach(x -> counts.merge(x, 1, Integer::sum));
        List<String> out = new ArrayList<>();
        for (String x : a) {
            Integer c = counts.get(x);
            if (c == null || c == 0) out.add(x);
            else counts.put(x, c - 1);
        }
        return out;
    }

    private static void printSummary(String repo, int total, int compared, int fullyEqual, long cimetNanos,
                                     long tsNanos, Map<String, DimStats> stats, List<String> parseFailures,
                                     List<String> crashes, List<String> tsErrors) {
        System.out.println("=== Java parity: CIMET (JavaParser + symbol solver) vs tree-sitter — " + repo + " ===");
        System.out.printf("Java files: %d, compared: %d, identical on every dimension: %d (%.1f%%)%n",
                total, compared, fullyEqual, compared == 0 ? 0.0 : 100.0 * fullyEqual / compared);
        System.out.printf("Time  CIMET: %,d ms   tree-sitter: %,d ms%n", cimetNanos / 1_000_000, tsNanos / 1_000_000);
        System.out.printf("CIMET parse failures: %d, CIMET crashes: %d, tree-sitter files with syntax errors: %d%n%n",
                parseFailures.size(), crashes.size(), tsErrors.size());

        System.out.printf("%-22s %14s %10s %10s %10s %9s %9s %8s%n",
                "dimension", "files equal", "CIMET", "t-sitter", "matched", "precision", "recall", "dedup*");
        stats.forEach((dim, s) -> System.out.printf("%-22s %7d/%-6d %10d %10d %10d %8.1f%% %8.1f%% %8s%n",
                dim, s.filesEqual, s.filesCompared, s.cimetItems, s.tsItems, s.matchedItems,
                100 * s.precision(), 100 * s.recall(),
                FileFacts.SET_DIMENSIONS.contains(dim) ? String.valueOf(s.duplicatesCollapsedByCimet) : "-"));
        System.out.println("* dedup: duplicate items tree-sitter saw that CIMET's Set-based IR collapses (compared as sets)");

        stats.forEach((dim, s) -> {
            if (s.examples.isEmpty()) return;
            System.out.println("\n--- " + dim + " (first differences)");
            for (Map<String, Object> ex : s.examples.subList(0, Math.min(3, s.examples.size()))) {
                System.out.println("  " + ex.get("file"));
                System.out.println("    only CIMET:       " + truncate(ex.get("onlyCimet")));
                System.out.println("    only tree-sitter: " + truncate(ex.get("onlyTreeSitter")));
            }
        });
    }

    private static String truncate(Object o) {
        String s = String.valueOf(o);
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }
}
