package edu.university.ecs.lab.intermediate.create.services;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public record ServiceBoundaryCandidate(
        Path rootPath,
        String serviceName,
        int confidenceScore,
        Set<String> evidence
) {
    public ServiceBoundaryCandidate {
        Objects.requireNonNull(rootPath, "rootPath is required");
        Objects.requireNonNull(serviceName, "serviceName is required");
        evidence = Set.copyOf(Objects.requireNonNull(evidence, "evidence is required"));
    }
}
