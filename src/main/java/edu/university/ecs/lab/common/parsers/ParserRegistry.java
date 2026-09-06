package edu.university.ecs.lab.common.parsers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class ParserRegistry {
    private final List<LanguageParser> parsers = new ArrayList<>();

    public void register(LanguageParser parser) {
        parsers.add(parser);
    }

    public Optional<LanguageParser> findParserFor(File sourceFile) {
        return parsers.stream()
                .filter(p -> p.canParse(sourceFile))
                .findFirst();
    }
}
