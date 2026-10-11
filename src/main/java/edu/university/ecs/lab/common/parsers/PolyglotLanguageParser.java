package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.parsers.treesitter.FileFacts;
import edu.university.ecs.lab.common.parsers.treesitter.PolyglotExtractor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Wraps the tree-sitter {@link PolyglotExtractor} behind the {@link LanguageParser} contract, for one
 * language at a time (see the four instances registered in {@code IRExtractionService}).
 *
 * Maps roles, fields, parameters, imports, annotations, functions, endpoints, and outgoing REST/gRPC
 * calls (attributed to the function that makes each one) into the real IR object graph via
 * {@link PolyglotFactsToIrConverter} - including one real class per class/struct/interface a file
 * declares, not just the first. See that converter's class comment for what's still deliberately not
 * modeled (precise resolved types, mainly), and TreeSitterJavaExtractor/JavaParityRunner (in the
 * experiments package) for how much further Java's own tree-sitter parser already goes there.
 */
public class PolyglotLanguageParser implements LanguageParser {

    private static final Set<String> LIMITATIONS = Set.of(
            "Field/parameter types are literal source text, not resolved or simplified.",
            "The type an outgoing call's target resolves to isn't tracked (Java's own tree-sitter " +
                    "parser only gets this right ~75% of the time itself; not attempted here).",
            "Role classification (CONTROLLER/SERVICE/REPOSITORY/ENTITY) is name/annotation-pattern " +
                    "based, not type resolution; it will misclassify unconventionally named code."
    );

    private final PolyglotExtractor.Language language;
    private final PolyglotExtractor extractor = new PolyglotExtractor();

    public PolyglotLanguageParser(PolyglotExtractor.Language language) {
        this.language = language;
    }

    @Override
    public boolean canParse(File sourceFile) {
        return sourceFile != null && PolyglotExtractor.Language.forFile(sourceFile.getName())
                .filter(l -> l == language)
                .isPresent();
    }

    @Override
    public ParserCapability getCapability() {
        return new ParserCapability(language.name().toLowerCase(), Set.copyOf(language.getExtensions()), LIMITATIONS);
    }

    @Override
    public ParseResult parse(Microservice microservice, File sourceFile, RepositoryConfig config, boolean filterOutUnknownClassRoles) {
        try {
            byte[] bytes = Files.readAllBytes(sourceFile.toPath());
            String gitPathString = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());
            PolyglotExtractor.Result result = extractor.extract(language, gitPathString, bytes);

            String baseName = sourceFile.getName();
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);

            // Filtering (when requested) is applied inside convert() to every class the file produces,
            // not just the one returned here - a file with several classes could otherwise have an
            // UNKNOWN-role secondary class added while an UNKNOWN-role primary gets correctly dropped.
            AbstractClass abstractClass = PolyglotFactsToIrConverter.convert(
                    microservice, Path.of(gitPathString), baseName, result.facts(), filterOutUnknownClassRoles);
            return ParseResult.success(abstractClass);
        } catch (Exception e) {
            return ParseResult.failure(language + " parser failed on " + sourceFile.getPath() + ": " + e);
        }
    }
}
