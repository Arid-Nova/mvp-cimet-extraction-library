package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.SourceFileReader;
import edu.university.ecs.lab.common.parsers.treesitter.FileFacts;
import edu.university.ecs.lab.common.parsers.treesitter.PolyglotExtractor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Wraps the tree-sitter {@link PolyglotExtractor} behind the {@link LanguageParser} contract, for one
 * language at a time (Python, Go and C# are registered by default in {@link ParserRegistry}).
 *
 * Maps roles, fields, parameters, imports, annotations, functions, endpoints, and outgoing REST/gRPC
 * calls (attributed to the function that makes each one) into the real IR object graph via
 * {@link PolyglotFactsToIrConverter} - including one real class per class/struct/interface a file
 * declares, not just the first. See that converter's class comment for what's still deliberately not
 * modeled, including precise resolved types.
 */
public class PolyglotLanguageParser implements LanguageParser {

    private static final Set<String> LIMITATIONS = Set.of(
            "Field/parameter types are literal source text, not resolved or simplified.",
            "The type an outgoing call's target resolves to isn't tracked.",
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
            byte[] bytes = SourceFileReader.read(sourceFile.toPath());
            String gitPathString = FileUtils.localPathToGitPath(sourceFile.getPath(), config.getRepoName());
            PolyglotExtractor.Result result = extractor.extract(language, gitPathString, bytes);
            if (result.syntaxErrors() > 0) {
                return ParseResult.failure(language + " syntax errors in " + gitPathString + ": " + result.syntaxErrors());
            }

            String baseName = sourceFile.getName();
            int dot = baseName.lastIndexOf('.');
            if (dot > 0) baseName = baseName.substring(0, dot);

            // Return all containers without mutating the service; filtering applies to each one.
            return ParseResult.success(PolyglotFactsToIrConverter.convertAll(
                    microservice, Path.of(gitPathString), baseName, result.facts(), filterOutUnknownClassRoles));
        } catch (Exception e) {
            return ParseResult.failure(language + " parser failed on " + sourceFile.getPath() + ": " + e);
        }
    }
}
