package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.Microservice;

import java.io.File;
import java.nio.file.Path;

public interface LanguageParser {

    /** Starts a new immutable checkout analysis. Repository-aware parsers invalidate their caches here. */
    default void prepareRepository(Path root, RepositoryConfig config) { }

    boolean canParse(File sourceFile);

    ParserCapability getCapability();

    ParseResult parse(Microservice microservice, File sourceFile, RepositoryConfig config, boolean filterOutUnknownClassRoles);
}
