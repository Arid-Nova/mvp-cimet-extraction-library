package edu.university.ecs.lab.common.parsers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.nio.file.Path;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.parsers.treesitter.PolyglotExtractor;

public class ParserRegistry {
    private final List<LanguageParser> parsers = new ArrayList<>();

    public void register(LanguageParser parser) {
        parsers.add(Objects.requireNonNull(parser));
    }

    /** One registry belongs to one extraction run; no global mutable parser or checkout cache. */
    public static ParserRegistry createDefault() {
        String configured = System.getProperty("cimet.javascript.adapter");
        if (configured == null || configured.isBlank()) configured = System.getenv("CIMET_JAVASCRIPT_ADAPTER");
        Path adapter = Path.of(configured == null || configured.isBlank() ? "adapter/javascript" : configured);
        return createDefault(adapter, System.getProperty("cimet.node.executable", "node"));
    }

    public static ParserRegistry createDefault(Path adapterDirectory, String nodeExecutable) {
        ParserRegistry registry = new ParserRegistry();
        registry.register(new JavaLanguageParser());
        registry.register(new JavaScriptLanguageParser(adapterDirectory, nodeExecutable));
        for (PolyglotExtractor.Language language : List.of(PolyglotExtractor.Language.PYTHON,
                PolyglotExtractor.Language.GO, PolyglotExtractor.Language.CSHARP)) {
            registry.register(new PolyglotLanguageParser(language));
        }
        return registry;
    }

    public void prepareRepository(Path root, RepositoryConfig config) {
        Objects.requireNonNull(root);
        parsers.forEach(parser -> parser.prepareRepository(root.toAbsolutePath().normalize(), config));
    }

    public List<ParserCapability> getCapabilities() {
        return parsers.stream().map(LanguageParser::getCapability).toList();
    }

    public Optional<LanguageParser> findParserFor(File sourceFile) {
        return parsers.stream()
                .filter(p -> p.canParse(sourceFile))
                .findFirst();
    }
}
