package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import java.io.File;
import java.util.Set;

/**
 * Wraps CIMET's existing Java parser (JavaParser + a symbol solver, in {@link SourceToObjectUtils})
 * behind the {@link LanguageParser} contract, so it can be looked up through a {@link
 * ParserRegistry} instead of being called directly.
 *
 * <p>Files use independent parser contexts and return all named top-level/member declarations.
 * Source paths remain physical metadata even when an additional declaration needs a synthetic ID.
 */
public class JavaLanguageParser implements LanguageParser {

  private static final Set<String> LIMITATIONS =
      Set.of(
          "External symbols may be unresolved: objectType is a compatibility short name;"
              + " qualifiedObjectType and objectTypeResolution metadata qualify identity.",
          "Local and anonymous classes, inherited Spring mappings and dynamic/multiple route"
              + " expressions are not comprehensively extracted.",
          "Spring roles and default repository collection naming remain source heuristics, not"
              + " runtime deployment proof.");

  @Override
  public boolean canParse(File sourceFile) {
    return sourceFile != null && sourceFile.getName().endsWith(".java");
  }

  @Override
  public ParserCapability getCapability() {
    return new ParserCapability("java", Set.of(".java"), LIMITATIONS);
  }

  @Override
  public ParseResult parse(
      Microservice microservice,
      File sourceFile,
      RepositoryConfig config,
      boolean filterOutUnknownClassRoles) {
    try {
      return ParseResult.success(
          SourceToObjectUtils.parseClasses(
              microservice, sourceFile, config, filterOutUnknownClassRoles));
    } catch (Exception e) {
      return ParseResult.failure("JavaParser failed on " + sourceFile.getPath() + ": " + e);
    }
  }
}
