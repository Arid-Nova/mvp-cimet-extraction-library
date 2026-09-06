package edu.university.ecs.lab.common.parsers;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.Microservice;

import java.io.File;

public interface LanguageParser {

    boolean canParse(File sourceFile);

    ParserCapability getCapability();

    ParseResult parse(Microservice microservice, File sourceFile, RepositoryConfig config, boolean filterOutUnknownClassRoles);
}
