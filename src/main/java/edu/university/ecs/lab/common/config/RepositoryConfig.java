package edu.university.ecs.lab.common.config;

import java.util.Objects;

public record RepositoryConfig(RepositoryBranchPair repoBranchPair, String commitID) {


    /**
     * Asserts that all key aspects of the RepositoryConfig are present.
     *
     * @throws NullPointerException if repositoryURL, branchName, or commitID is null
     * @throws IllegalStateException see validateRepositoryURL()
     */
    public void validateConfig() {
        Objects.requireNonNull(commitID, "commitID is required");

        repoBranchPair.validateConfig();
    }

    /**
     * Convenience function to get the repository name from the repoBranchPair
     */
    public String getRepoName() {
        return repoBranchPair.getRepoName();
    }
}
