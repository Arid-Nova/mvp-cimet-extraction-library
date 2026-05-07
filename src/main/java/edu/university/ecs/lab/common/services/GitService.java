package edu.university.ecs.lab.common.services;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.utils.GitHubTokenClient;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;

import lombok.Getter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service to perform Git operations
 */
public class GitService {
    private static final String HEAD_COMMIT = "HEAD";
    private String rawToken;

    private final Config config;
    private final GitHubTokenClient tokenClient;

    /**
     * Map of repository branch pairs to JGit repositories
     */
    @Getter
    private Map<RepositoryBranchPair, Repository> repositories;

    /**
     * Create a Git service object from a project configuration file
     * 
     * @param config the Config for this project
     */
    public GitService(Config config) throws IOException, InterruptedException {
        this.config = config;
        this.tokenClient = new GitHubTokenClient();

        FileUtils.makeDirs();
        prepareRepositories();
    }

    /**
     * Injects the GitHub token into the HTTPS URL if available.
     */
    private String getAuthenticatedUrl(String repoUrl) {
        if (this.rawToken != null && !this.rawToken.trim().isEmpty() && repoUrl.startsWith("https://")) {
            return repoUrl.replace("https://", "https://" + this.rawToken + "@");
        }
        return repoUrl;
    }

    /**
     * Clones repositories for each Git repo that does not already have an IR generated for it
     */
    public void prepareRepositories() throws IOException, InterruptedException {
        this.rawToken = tokenClient.fetchAndDecryptToken();

        repositories = new HashMap<>();

        // For each repository
        for (RepositoryConfig rc : config.getSystemRepositories()) {
            // Check for existing IR part
            String irName = "PART_" + rc.getRepoName() + "_" + rc.repoBranchPair().branchName() + "_" + rc.commitID() + ".json";
            File existingIR = new File(FileUtils.getOutputPath() + File.separator + irName);

            // If an IR already exists, just set up a bare mirror of the repository for deltas
            if (existingIR.exists()) {
                cloneRemote(rc);
                repositories.put(rc.repoBranchPair(), initRepository(rc, true));
            }
            // Else clone it and prepare to generate an IR
            else {
                cloneRemote(rc);
                repositories.put(rc.repoBranchPair(), initRepository(rc, false));
            }
        }
    }

    /**
     * Method to clone a repository
     */
    public void cloneRemote(RepositoryConfig config) throws InterruptedException, IOException {
        String repositoryPath = FileUtils.getRepositoryPath(config.getRepoName());

        // Check if repository was already cloned
        if (new File(repositoryPath).exists()) {
            return;
        }
//        if (new File(repositoryPath).exists()) {
//            // Make sure repository is unbare
//            ProcessBuilder unbare = new ProcessBuilder("git", "config", "--bool", "core.bare", "false");
//            unbare.directory(new File(repositoryPath));
//            unbare.inheritIO();
//            Process unbareProcess = unbare.start();
//            if(unbareProcess.waitFor() != 0) {
//                throw new IOException("Failed to set repository non-bare");
//            }
//
//            // Checkout specific commit
//            ProcessBuilder checkout = new ProcessBuilder("git", "checkout", config.commitID());
//            checkout.directory(new File(repositoryPath));
//            checkout.inheritIO();
//            Process checkoutProcess = checkout.start();
//            if(checkoutProcess.waitFor() != 0) {
//                throw new IOException("Failed to checkout repository");
//            }
//        }
//        else {
            String authUrl = getAuthenticatedUrl(config.repoBranchPair().repositoryURL());

            // Create and execute operating system process to clone repository
            ProcessBuilder processBuilder =
                    new ProcessBuilder("git", "clone", authUrl, repositoryPath);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Failed to clone repository (Exit Code: " + exitCode + "): " + config.getRepoName());
            } else {
                System.out.println("Cloned " + config.getRepoName());
            }
//        }
    }

//    /**
//     * Method to clone a repository using JGit to securely apply credentials.
//     * Deprecated above version because it was running low local OS commands
//     * which is not easy to deal with credential providers.
//     */
//    public void cloneRemote(RepositoryConfig config) throws IOException {
//        String repositoryPath = FileUtils.getRepositoryPath(config.getRepoName());
//
//        // Check if repository was already cloned
//        if (new File(repositoryPath).exists()) {
//            return;
//        }
//
//        try {
//            CloneCommand cloneCommand = Git.cloneRepository()
//                    .setURI(config.repoBranchPair().repositoryURL())
//                    .setDirectory(new File(repositoryPath));
//
//            // Injecting the decrypted GitHub token
//            if (this.credentialsProvider != null) {
//                cloneCommand.setCredentialsProvider(this.credentialsProvider);
//            }
//
//            // Executing the repository clone
//            try (Git _ = cloneCommand.call()) {
//                System.out.println("Successfully cloned " + config.getRepoName());
//            }
//        } catch (GitAPIException e) {
//            throw new IOException("Failed to clone repository: " + config.getRepoName(), e);
//        }
//    }

    /**
     * Method to reset repository to a given commit
     * 
     * @param commitID commit id to reset to
     */
    public void resetLocal(RepositoryConfig rc, String commitID) throws GitAPIException {
        validateLocalExists(rc);

        if (Objects.isNull(commitID) || commitID.isEmpty()) {
            return;
        }

        // Reset branch to old commit
        try (Git git = new Git(repositories.get(rc.repoBranchPair()))) {
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commitID).call();
        }
    }

    /**
     * Method to check that local directory exists
     */
    private void validateLocalExists(RepositoryConfig config) {
        File file = new File(FileUtils.getRepositoryPath(config.getRepoName()));
        if (!(file.exists() && file.isDirectory())) {
            throw new IllegalStateException("The local directory does not exist");
        }
    }

    /**
     * Method to initialize repository from repository configuration
     *
     * @param rc The RepositoryConfig
     * @param bareMirror Whether the checkout should just be a bare mirror
     * @return file repository
     */
    public Repository initRepository(RepositoryConfig rc, boolean bareMirror) throws IOException {
        validateLocalExists(rc);

        File repositoryPath = new File(FileUtils.getRepositoryPath(rc.getRepoName()));
        FileRepositoryBuilder builder = new FileRepositoryBuilder().setGitDir(new File(repositoryPath, ".git"));

        //if (bareMirror) {
        //    builder.setBare();
        //}

        return builder.build();
    }

    /**
     * Method to get differences between old and new commits
     *
     * @param rbp The RepositoryBranchPair
     * @param commitOld old commit id
     * @param commitNew new commit id
     * @return list of changes from old commit to new commit
     */
    public List<DiffEntry> getDifferences(RepositoryBranchPair rbp, String commitOld, String commitNew) throws IOException, GitAPIException {
        Repository repository = repositories.get(rbp);

        List<DiffEntry> returnList = null;
        RevCommit oldCommit = null, newCommit = null;
        RevWalk revWalk = new RevWalk(repository);

        // Parse the old and new commits
        oldCommit = revWalk.parseCommit(repository.resolve(commitOld));
        newCommit = revWalk.parseCommit(repository.resolve(commitNew));

        // Prepare tree parsers for both commits
        try (ObjectReader reader = repository.newObjectReader()) {
            CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
            CanonicalTreeParser newTreeParser = new CanonicalTreeParser();

            // Use tree objects from the commits
            oldTreeParser.reset(reader, oldCommit.getTree().getId());
            newTreeParser.reset(reader, newCommit.getTree().getId());

            // Compute differences between the trees of the two commits
            try (Git git = new Git(repository)) {
                List<DiffEntry> rawDiffs = git.diff()
                        .setOldTree(oldTreeParser)
                        .setNewTree(newTreeParser)
                        .call();

                // Filter out diffs that only contain whitespace or comment changes
                RevCommit finalOldCommit = oldCommit;
                RevCommit finalNewCommit = newCommit;
                returnList = rawDiffs.stream()
                        .filter(diff -> isCodeChange(diff, repository, finalOldCommit, finalNewCommit))
                        .collect(Collectors.toList());
            }
        }

        return returnList;
    }

    /**
     * Method to get differences between old and new commits
     * on a line by line basis
     *
     * @param rc The RepositoryConfig
     * @param commitOld old commit id
     * @param commitNew new commit id
     * @return map of changes from old commit to new commit
     */
    public Map<DiffEntry, EditList> getGranularDifferences(RepositoryConfig rc,
                                                           String commitOld,
                                                           String commitNew) throws IOException, GitAPIException {
        Repository repository = repositories.get(rc.repoBranchPair());

        Map<DiffEntry, EditList> returnMap = null;
        RevCommit oldCommit = null, newCommit = null;
        RevWalk revWalk = new RevWalk(repository);

        // Parse the old and new commits
        oldCommit = revWalk.parseCommit(repository.resolve(commitOld));
        newCommit = revWalk.parseCommit(repository.resolve(commitNew));


        // Prepare tree parsers for both commits
        ObjectReader reader = repository.newObjectReader();
        CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
        CanonicalTreeParser newTreeParser = new CanonicalTreeParser();

        // Use tree objects from the commits
        oldTreeParser.reset(reader, oldCommit.getTree().getId());
        newTreeParser.reset(reader, newCommit.getTree().getId());

        // Compute differences between the trees of the two commits
        Git git = new Git(repository);
        List<DiffEntry> diffEntryList = git.diff()
                .setOldTree(oldTreeParser)
                .setNewTree(newTreeParser)
                .call();

        // Filter out diffs that only contain whitespace or comment changes
        RevCommit finalOldCommit = oldCommit;
        RevCommit finalNewCommit = newCommit;

//                returnMap = rawDiffs.stream()
//                        .filter(diff -> isCodeChange(diff, repository, finalOldCommit, finalNewCommit))
//                        .collect(Collectors.toList());

        for(DiffEntry diffEntry : diffEntryList) {
            switch (diffEntry.getChangeType()) {
                case ADD:
                    returnMap.put(diffEntry, new EditList());
                    break;
                case MODIFY:

                    break;
                case DELETE:
                    returnMap.put(diffEntry, new EditList());
                    break;
            }
        }

        return returnMap;
    }

    /**
     * Method to check if a commit difference was a change to the code
     * 
     * @param diff DiffEntry object
     * @param repository repository to check
     * @param oldCommit old commit id
     * @param newCommit new commit id
     * 
     * @return true if difference was a change to the code, false otherwise
     */
    private boolean isCodeChange(DiffEntry diff, Repository repository, RevCommit oldCommit, RevCommit newCommit) {
        if((!diff.getOldPath().endsWith(".java") && !diff.getNewPath().endsWith(".java"))) {
            return true;
        }

        // Read the file contents before and after the changes
        String oldContent = getContentFromTree(repository, oldCommit.getTree().getId(), diff.getOldPath());
        String newContent = getContentFromTree(repository, newCommit.getTree().getId(), diff.getNewPath());

        // Remove comments and whitespace from both contents
        String oldCode = stripCommentsAndWhitespace(oldContent);
        String newCode = stripCommentsAndWhitespace(newContent);

        // If the meaningful code is different, return true
        return !oldCode.equals(newCode);
    }

    /**
     * Get file data from a file tree
     * 
     * @param repository repository to check
     * @param treeId id of the tree to check
     * @param filePath file to get data from
     * @return data from the file, or an empty string if an error occurs or file is not found
     */
    private String getContentFromTree(Repository repository, ObjectId treeId, String filePath) {
        try (ObjectReader reader = repository.newObjectReader();
             TreeWalk treeWalk = new TreeWalk(repository)) {

            // Add the tree to the tree walker
            treeWalk.addTree(treeId);
            treeWalk.setRecursive(true); // We want to search recursively

            // Walk through the tree to find the file
            while (treeWalk.next()) {
                String currentPath = treeWalk.getPathString();
                if (currentPath.equals(filePath)) {
                    // Ensure we have a blob (file) and not a tree
                    if (treeWalk.getFileMode(0).getObjectType() == Constants.OBJ_BLOB) {
                        // Read the file content and return it
                        byte[] data = reader.open(treeWalk.getObjectId(0)).getBytes();
                        return new String(data, StandardCharsets.UTF_8);
                    }
                }
            }

        } catch (Exception e) {
            // Return an empty string in case of an error
            return "";
        }

        // If the file is not found, return an empty string
        return "";
    }

    /**
     * Remove comments and whitespace from file content
     * 
     * @param content string of all file content
     * @return string of file content with whitespace and comments removed
     */
    private String stripCommentsAndWhitespace(String content) {
        return content.replaceAll("(//.*|/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/|\\s+)", "");
    }

    /**
     * Get Git log
     *
     * @param rc The RepositoryConfig
     * @return Git log as a list
     */
    public Iterable<RevCommit> getLog(RepositoryConfig rc) throws GitAPIException {
        Iterable<RevCommit> returnList = null;

        try (Git git = new Git(repositories.get(rc.repoBranchPair()))) {
            returnList = git.log().call();
        }

        return returnList;
    }

    /**
     * Get head commit for the repository
     *
     * @param rc The RepositoryConfig
     * @return commit id of head commit
     */
    public String getHeadCommit(RepositoryConfig rc) throws IOException {
        Repository repository = repositories.get(rc.repoBranchPair());
        String commitID = "";

        Ref head = repository.findRef(HEAD_COMMIT);
        RevWalk walk = new RevWalk(repository);
        ObjectId commitId = head.getObjectId();
        RevCommit commit = walk.parseCommit(commitId);
        commitID = commit.getName();
        walk.close();

        return commitID;
    }
}
