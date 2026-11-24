package edu.university.ecs.lab.common.parsers.treesitter;

import edu.university.ecs.lab.common.parsers.treesitter.TsUtil.SNode;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTsx;

/**
 * Tree-sitter extraction of microservice-relevant facts for JavaScript, Python, Go and C#.
 *
 * <p>There is no CIMET baseline for these languages, so the rules aim at the same information CIMET
 * extracts for Java: types, functions, imports, annotations, exposed endpoints and outgoing calls.
 * Framework coverage is heuristic (see {@link Language} for what each language recognises); gRPC
 * registrations and clients are extracted as well because many polyglot systems talk over gRPC.
 */
public class PolyglotExtractor {

  // Parser instances are confined to this facade's extraction job. Language visitors are
  // stateless and source/fact state is supplied explicitly for each parse.

  public static final List<String> DIMENSIONS =
      List.of(
          "types",
          "classRole",
          "fields",
          "functions",
          "parameters",
          "imports",
          "annotations",
          "endpoints",
          "httpCalls",
          "rpcServers",
          "rpcClients");

  public enum Language {
    // TSX grammar, not the plain JS one: it's a superset that also understands JSX and most
    // TypeScript/Flow type syntax, and still parses plain untyped JS the same way (see
    // TsGrammarCompare experiment: fixed 42/46 files that erroed on Flow-typed JSX, on a real
    // repo).
    JAVASCRIPT(
        TreeSitterTsx::new,
        ".js",
        ".mjs",
        ".cjs",
        ".jsx"), // Express-style routers, fetch/axios/http
    PYTHON(TreeSitterPython::new, ".py"), // Flask/FastAPI decorators, Django path(), requests/httpx
    GO(TreeSitterGo::new, ".go"), // net/http, gorilla/mux, gin/echo/chi
    CSHARP(TreeSitterCSharp::new, ".cs"); // ASP.NET attributes + minimal APIs, HttpClient

    final Supplier<TSLanguage> grammar;
    final List<String> extensions;

    Language(Supplier<TSLanguage> grammar, String... extensions) {
      this.grammar = grammar;
      this.extensions = List.of(extensions);
    }

    public List<String> getExtensions() {
      return extensions;
    }

    public static Optional<Language> forFile(String fileName) {
      for (Language l : values()) {
        for (String ext : l.extensions) if (fileName.endsWith(ext)) return Optional.of(l);
      }
      return Optional.empty();
    }
  }

  private static final Map<Language, LanguageFactExtractor> FACT_EXTRACTORS =
      Map.of(
          Language.JAVASCRIPT, new JavaScriptFactExtractor(),
          Language.PYTHON, new PythonFactExtractor(),
          Language.GO, new GoFactExtractor(),
          Language.CSHARP, new CSharpFactExtractor());

  private final Map<Language, TSParser> parsers = new EnumMap<>(Language.class);

  /** Parse result plus the extracted facts. */
  public record Result(FileFacts facts, int syntaxErrors, long parseNanos) {}

  public Result extract(Language language, String path, byte[] bytes) {
    bytes = TsUtil.stripUtf8Bom(bytes);
    TSParser parser =
        parsers.computeIfAbsent(
            language,
            l -> {
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

    FactOutput out = new FactOutput(bytes);
    FACT_EXTRACTORS.get(language).extract(root, out);
    FileFacts facts = new FileFacts(path);
    out.values.forEach(facts::put);
    return new Result(facts, errors, parseNanos);
  }
}
