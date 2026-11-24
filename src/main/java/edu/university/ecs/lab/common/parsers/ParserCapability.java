package edu.university.ecs.lab.common.parsers;

import java.util.Set;
import java.util.Collections;
import java.util.Objects;
import java.util.TreeSet;

public final class ParserCapability {
    private final String language;
    private final Set<String> supportedExtensions;
    private final Set<String> limitations;

    public ParserCapability(String language, Set<String> supportedExtensions, Set<String> limitations) {
        this.language = Objects.requireNonNull(language);
        if (language.isBlank()) throw new IllegalArgumentException("A parser language is required");
        this.supportedExtensions = Collections.unmodifiableSet(new TreeSet<>(supportedExtensions));
        this.limitations = Collections.unmodifiableSet(new TreeSet<>(limitations));
    }

    public String getLanguage() { return language; }
    public Set<String> getSupportedExtensions() { return supportedExtensions; }
    public Set<String> getLimitations() { return limitations; }
}
