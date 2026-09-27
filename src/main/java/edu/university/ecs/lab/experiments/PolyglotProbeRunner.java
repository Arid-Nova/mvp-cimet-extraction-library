package edu.university.ecs.lab.experiments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import edu.university.ecs.lab.experiments.PolyglotExtractor.Language;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Checks whether tree-sitter can parse a polyglot repository and what microservice context it can
 * extract per language (JavaScript, Python, Go, C#).
 *
 * Usage: PolyglotProbeRunner [repoName] — the repo must be cloned under ./clone/&lt;repoName&gt;
 * (defaults to microservices-demo, https://github.com/GoogleCloudPlatform/microservices-demo).
 * Writes output/experiments/polyglot-&lt;repo&gt;.json with the per-file facts.
 */
public class PolyglotProbeRunner {

    private static final Set<String> SKIPPED_DIRS = Set.of(
            "node_modules", "vendor", ".git", "bin", "obj", "dist", "build", "__pycache__", ".venv", "venv");

    static final class LangStats {
        int files, generatedFiles, filesWithErrors, errorNodes;
        long bytes, parseNanos, totalNanos;
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<String> errorFiles = new ArrayList<>();

        LangStats() { PolyglotExtractor.DIMENSIONS.forEach(d -> counts.put(d, 0)); }
    }

    public static void main(String[] args) throws Exception {
        String repoName = args.length > 0 ? args[0] : "microservices-demo";
        Path repo = Path.of(".", "clone", repoName);
        if (!Files.isDirectory(repo)) {
            throw new IllegalArgumentException("Clone the repository into " + repo + " first");
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(repo)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> repo.relativize(p).toString().split("[/\\\\]").length > 0
                            && Arrays.stream(repo.relativize(p).toString().split("[/\\\\]")).noneMatch(SKIPPED_DIRS::contains))
                    .filter(p -> Language.forFile(p.getFileName().toString()).isPresent())
                    .sorted().toList();
        }

        PolyglotExtractor extractor = new PolyglotExtractor();
        Map<Language, LangStats> stats = new EnumMap<>(Language.class);
        // service -> dimension -> values, for the per-service context view (hand-written code only)
        Map<String, Map<String, Set<String>>> services = new TreeMap<>();
        List<Map<String, Object>> fileReports = new ArrayList<>();

        for (Path path : files) {
            Language lang = Language.forFile(path.getFileName().toString()).orElseThrow();
            String rel = repo.relativize(path).toString();
            byte[] bytes = Files.readAllBytes(path);
            boolean generated = isGenerated(rel, bytes);

            long t0 = System.nanoTime();
            PolyglotExtractor.Result result = extractor.extract(lang, rel, bytes);
            long elapsed = System.nanoTime() - t0;

            LangStats s = stats.computeIfAbsent(lang, l -> new LangStats());
            s.files++;
            s.bytes += bytes.length;
            s.parseNanos += result.parseNanos();
            s.totalNanos += elapsed;
            if (generated) s.generatedFiles++;
            if (result.syntaxErrors() > 0) {
                s.filesWithErrors++;
                s.errorNodes += result.syntaxErrors();
                s.errorFiles.add(rel + " (" + result.syntaxErrors() + ")");
            }

            Map<String, Object> fr = new LinkedHashMap<>();
            fr.put("file", rel);
            fr.put("language", lang);
            fr.put("generated", generated);
            fr.put("syntaxErrors", result.syntaxErrors());
            fr.put("facts", result.facts().getDimensions());
            fileReports.add(fr);

            if (generated) continue; // generated stubs would drown out the real service context
            String service = serviceOf(rel);
            for (String dim : PolyglotExtractor.DIMENSIONS) {
                List<String> values = result.facts().get(dim);
                s.counts.merge(dim, values.size(), Integer::sum);
                if (!values.isEmpty() && !dim.equals("functions") && !dim.equals("types") && !dim.equals("imports")
                        && !dim.equals("annotations")) {
                    services.computeIfAbsent(service + " [" + lang.name().toLowerCase() + "]", k -> new TreeMap<>())
                            .computeIfAbsent(dim, k -> new TreeSet<>()).addAll(values);
                }
            }
        }

        print(repoName, stats, services);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("repository", repoName);
        Map<String, Object> langs = new LinkedHashMap<>();
        stats.forEach((l, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("files", s.files);
            m.put("generatedFiles", s.generatedFiles);
            m.put("bytes", s.bytes);
            m.put("filesWithSyntaxErrors", s.filesWithErrors);
            m.put("errorNodes", s.errorNodes);
            m.put("errorFiles", s.errorFiles);
            m.put("parseMillis", s.parseNanos / 1_000_000.0);
            m.put("totalMillis", s.totalNanos / 1_000_000.0);
            m.put("factCountsExcludingGenerated", s.counts);
            langs.put(l.name(), m);
        });
        report.put("languages", langs);
        report.put("services", services);
        report.put("files", fileReports);
        Path out = Path.of("output", "experiments", "polyglot-" + repoName + ".json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.toFile(), report);
        System.out.println("\nFull report: " + out);
    }

    private static boolean isGenerated(String rel, byte[] bytes) {
        String name = Path.of(rel).getFileName().toString();
        if (name.endsWith(".pb.go") || name.endsWith("_pb2.py") || name.endsWith("_pb2_grpc.py")
                || name.endsWith(".g.cs") || name.endsWith(".Designer.cs") || name.endsWith(".min.js")) {
            return true;
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 600));
        return head.contains("Code generated") || head.contains("<auto-generated") || head.contains("DO NOT EDIT");
    }

    /** First directory under src/ (or the top-level directory) is taken as the service name. */
    private static String serviceOf(String rel) {
        String[] parts = rel.split("[/\\\\]");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("src") && i + 1 < parts.length - 1) return parts[i + 1];
        }
        return parts.length > 1 ? parts[0] : "(root)";
    }

    private static void print(String repo, Map<Language, LangStats> stats, Map<String, Map<String, Set<String>>> services) {
        System.out.println("=== Tree-sitter polyglot probe — " + repo + " ===");
        System.out.printf("%-11s %6s %5s %8s %10s %9s %9s", "language", "files", "gen", "KB", "syntaxErr", "parse ms", "total ms");
        PolyglotExtractor.DIMENSIONS.forEach(d -> System.out.printf(" %10s", d));
        System.out.println();
        stats.forEach((l, s) -> {
            System.out.printf("%-11s %6d %5d %8d %10s %9.1f %9.1f", l.name().toLowerCase(), s.files, s.generatedFiles,
                    s.bytes / 1024, s.filesWithErrors + "/" + s.files, s.parseNanos / 1e6, s.totalNanos / 1e6);
            PolyglotExtractor.DIMENSIONS.forEach(d -> System.out.printf(" %10d", s.counts.get(d)));
            System.out.println();
        });
        System.out.println("(fact counts exclude generated files)");
        stats.forEach((l, s) -> {
            if (!s.errorFiles.isEmpty()) System.out.println("Syntax errors in " + l + ": " + s.errorFiles);
        });

        System.out.println("\n--- Service context (endpoints, outgoing HTTP, gRPC servers/clients)");
        services.forEach((svc, dims) -> {
            System.out.println(svc);
            dims.forEach((d, v) -> System.out.println("   " + String.format("%-11s", d) + " " + v));
        });
    }
}
