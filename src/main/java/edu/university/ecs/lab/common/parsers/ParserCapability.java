package edu.university.ecs.lab.common.parsers;

import java.util.Set;

public class ParserCapability {
    private final String language;
    private final Set<String> supportedExtensions;
    private final Set<String> limitations;

    public ParserCapability(String language, Set<String> supportedExtensions, Set<String> limitations) {
        this.language = language;
        this.supportedExtensions = supportedExtensions;
        this.limitations = limitations;
    }

    public String getLanguage() { return language; }
    public Set<String> getSupportedExtensions() { return supportedExtensions; }
    public Set<String> getLimitations() { return limitations; }
}
