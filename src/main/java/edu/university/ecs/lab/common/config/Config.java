package edu.university.ecs.lab.common.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;

/**
 * Model to represent the JSON configuration file
 */
@Getter
@NoArgsConstructor
public class Config {
    private static final String GIT_SCHEME_DOMAIN = "https://github.com/";
    private static final String GIT_PATH_EXTENSION = ".git";

    /**
     * The name of the system analyzed
     */
    private String systemName;

    /**
     * The path to write cloned repository files to
     */
    private String repositoryURL;

    /**
     * Initial starting commit for repository
     */
    @JsonAlias({"branch", "baseBranch"})
    private String branch;


    public Config(String systemName, String repositoryURL, String branch) throws Exception {
        validateConfig(systemName, repositoryURL, branch);

        this.systemName = systemName;
        this.repositoryURL = repositoryURL;
        this.branch = branch;
    }

    /**
     * Check that config file is valid and has all required fields
     */
    private void validateConfig(String systemName, String repositoryURL, String branch) {
        if (!systemName.isBlank() && !repositoryURL.isBlank() && !branch.isBlank()) {
           throw new IllegalStateException("An invalid configuration was found!");
        }

        Objects.requireNonNull(systemName);
        Objects.requireNonNull(repositoryURL);
        Objects.requireNonNull(branch);
        validateRepositoryURL(repositoryURL);
    }

    /**
     * The list of repository objects as indicated by config
     */
    private void validateRepositoryURL(String repositoryURL) {
        if (!(repositoryURL.isBlank() || repositoryURL.startsWith(GIT_SCHEME_DOMAIN) || repositoryURL.endsWith(GIT_PATH_EXTENSION))) {
            throw new IllegalStateException("An invalid repository URL was provided!");
        }
    }

    /**
     * This method gets the repository name parsed from the repositoryURL
     *
     * @return the plain string repository name with no path related characters
     */
    public String getRepoName() {
        int lastSlashIndex = repositoryURL.lastIndexOf("/");
        int lastDotIndex = repositoryURL.lastIndexOf('.');
        return repositoryURL.substring(lastSlashIndex + 1, lastDotIndex);
    }

}
