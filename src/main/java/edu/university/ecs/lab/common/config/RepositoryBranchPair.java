package edu.university.ecs.lab.common.config;

import java.util.Objects;

public record RepositoryBranchPair(String repositoryURL, String branchName) {
    private static final String GIT_SCHEME_DOMAIN = "https://github.com/";
    private static final String GIT_PATH_EXTENSION = ".git";

    /**
     * Asserts that all key aspects of the RepositoryBranchPair are present.
     *
     * @throws NullPointerException if repositoryURL or branchName is null
     * @throws IllegalStateException see validateRepositoryURL()
     */
    public void validateConfig() {
        Objects.requireNonNull(repositoryURL, "repositoryURL is required");
        Objects.requireNonNull(branchName, "branchName is required");

        validateRepositoryURL();
    }

    /**
     * This method parses the repository name
     *
     * @return the plain string repository name with no path related characters
     */
    public String getRepoName() {
        int lastSlashIndex = repositoryURL.lastIndexOf("/");
        int lastDotIndex = repositoryURL.lastIndexOf('.');
        return repositoryURL.substring(lastSlashIndex + 1, lastDotIndex);
    }

    /**
     * Validates the repository URL.
     *
     * @throws IllegalStateException if an invalid url was provided
     */
    private void validateRepositoryURL() {
        if (repositoryURL.isBlank() || !repositoryURL.startsWith(GIT_SCHEME_DOMAIN) || !repositoryURL.endsWith(GIT_PATH_EXTENSION)) {
            throw new IllegalStateException("An invalid repository URL was provided!");
        }
    }
}
