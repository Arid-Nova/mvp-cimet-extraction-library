package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.parsers.treesitter.PolyglotExtractor;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parser ownership for one sequential extraction job.
 *
 * <p>This mutable registry is not thread-safe: registration, repository preparation and file
 * parsing must not overlap on the same instance. Repository-aware parsers retain one prepared
 * checkout's evidence. Independent jobs may run concurrently using independent registries;
 * repository-level parallelism must also bound worker count and aggregate memory rather than
 * sharing this registry. Checkouts must be immutable or isolated; this contract does not make a
 * shared Git cache safe.
 */
public class ParserRegistry {
  private final List<LanguageParser> parsers = new ArrayList<>();

  public void register(LanguageParser parser) {
    parsers.add(Objects.requireNonNull(parser));
  }

  /** One registry belongs to one extraction run; no global mutable parser or checkout cache. */
  public static ParserRegistry createDefault() {
    String configured = System.getProperty("cimet.javascript.adapter");
    if (configured == null || configured.isBlank())
      configured = System.getenv("CIMET_JAVASCRIPT_ADAPTER");
    Path adapter =
        Path.of(configured == null || configured.isBlank() ? "adapter/javascript" : configured);
    return createDefault(adapter, System.getProperty("cimet.node.executable", "node"));
  }

  public static ParserRegistry createDefault(Path adapterDirectory, String nodeExecutable) {
    return createDefault(adapterDirectory, nodeExecutable, null);
  }

  /**
   * Explicit trusted JavaScript profile for an extraction job; no process-global configuration
   * changes.
   */
  public static ParserRegistry createDefault(
      Path adapterDirectory, String nodeExecutable, Path analyzerConfig) {
    ParserRegistry registry = new ParserRegistry();
    registry.register(new JavaLanguageParser());
    registry.register(
        new JavaScriptLanguageParser(adapterDirectory, nodeExecutable, analyzerConfig));
    for (PolyglotExtractor.Language language :
        List.of(
            PolyglotExtractor.Language.PYTHON,
            PolyglotExtractor.Language.GO,
            PolyglotExtractor.Language.CSHARP)) {
      registry.register(new PolyglotLanguageParser(language));
    }
    return registry;
  }

  /**
   * Replaces the active repository context and invalidates all parser caches. Finish parsing the
   * previous repository before calling this; callers must not interleave prepared repositories.
   */
  public void prepareRepository(Path root, RepositoryConfig config) {
    Objects.requireNonNull(root);
    parsers.forEach(parser -> parser.prepareRepository(root.toAbsolutePath().normalize(), config));
  }

  public List<ParserCapability> getCapabilities() {
    return parsers.stream().map(LanguageParser::getCapability).toList();
  }

  public Optional<LanguageParser> findParserFor(File sourceFile) {
    return parsers.stream().filter(p -> p.canParse(sourceFile)).findFirst();
  }
}
