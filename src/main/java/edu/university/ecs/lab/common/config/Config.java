package edu.university.ecs.lab.common.config;

import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/**
 * Model to represent the JSON configuration file
 */
@Getter
@NoArgsConstructor
public class Config {
    /**
     * The name of the system analyzed
     */
    private String systemName;

    /**
     * List of repository configurations
     */
    private List<RepositoryConfig> systemRepositories;

    public Config(String systemName, List<RepositoryConfig> systemRepositories) throws Exception {
        validateConfig(systemName, systemRepositories);

        this.systemName = systemName;
        this.systemRepositories = systemRepositories;
    }

    /**
     * Check that config file is valid and has all required fields
     */
    private void validateConfig(String systemName, List<RepositoryConfig> systemRepositories) {
        if (systemName.isBlank()) {
           throw new IllegalStateException("An invalid configuration was found!");
        }

        Objects.requireNonNull(systemName);

        if (systemRepositories.isEmpty()) {
            throw new IllegalStateException("At least one repository must be specified!");
        }

        systemRepositories.forEach(RepositoryConfig::validateConfig);
    }
}
