package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;

import java.io.File;
import java.util.Set;
import org.json.JSONObject;

/**
 * Wraps CIMET's existing Java parser (JavaParser + a symbol solver, in {@link SourceToObjectUtils})
 * behind the {@link LanguageParser} contract, so it can be looked up through a {@link ParserRegistry}
 * instead of being called directly.
 *
 * This is a pure wrapper: it changes nothing about how Java is parsed or what it produces. Its only
 * purpose is to prove the plug-in mechanism end to end on the one language whose output was already
 * fully validated (see JavaParityRunner / the #16 tree-sitter comparison), before other languages are
 * added behind the same interface.
 */
public class JavaLanguageParser implements LanguageParser {

    private static final Set<String> LIMITATIONS = Set.of(
            "Resolving the type an external (library) method call is made on can fail; JavaParser's " +
                    "symbol solver falls back to an unresolved-symbol message in that case rather than a type name."
    );

    @Override
    public boolean canParse(File sourceFile) {
        return sourceFile != null && sourceFile.getName().endsWith(".java");
    }

    @Override
    public ParserCapability getCapability() {
        return new ParserCapability("java", Set.of(".java"), LIMITATIONS);
    }

    @Override
    public ParseResult parse(Microservice microservice, File sourceFile, RepositoryConfig config, boolean filterOutUnknownClassRoles) {
        try {
            // May legitimately return null: not a class/interface/enum/record, or filtered out by role.
            AbstractClass abstractClass = SourceToObjectUtils.parseClass(microservice, sourceFile, config, filterOutUnknownClassRoles);
            if (abstractClass != null) {
                JSONObject metadata = abstractClass.getMetadata() == null ? new JSONObject() : abstractClass.getMetadata();
                metadata.put("sourcePath", abstractClass.getPath().toString().replace('\\', '/'));
                metadata.put("language", "java");
                abstractClass.setMetadata(metadata);
            }
            return ParseResult.success(abstractClass);
        } catch (Exception e) {
            return ParseResult.failure("JavaParser failed on " + sourceFile.getPath() + ": " + e);
        }
    }
}
