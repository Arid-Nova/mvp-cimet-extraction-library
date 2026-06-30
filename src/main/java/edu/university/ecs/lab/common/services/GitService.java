package edu.university.ecs.lab.common.services;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.utils.GitHubTokenClient;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;

import lombok.Getter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;

import java.util.Map;
import java.util.List;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HashMap;
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
     * Clones repositories for each Git repo that does not already have an IR generated for it
     */
    public void prepareRepositories() throws IOException, InterruptedException {
        this.rawToken = tokenClient.fetchToken();
        validateConfiguredRepositoryAccess();

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
        File repoDir = new File(repositoryPath);

        // A prior run may have left a directory whose .git is incomplete (e.g. OneDrive
        // sync wiped HEAD/config, or a clone was interrupted). In that case JGit walks
        // up the parent tree and resolves SHAs against the wrong repo, producing
        // MissingObjectException at resetLocal(). Wipe and re-clone instead.
        if (repoDir.exists() && !isValidGitRepo(repoDir)) {
            System.out.println("Detected invalid clone at " + repositoryPath + ", wiping and re-cloning");
            deleteRecursively(repoDir.toPath());
        }

        // Check if repository was already cloned
        if (repoDir.exists()) {
            return;
        }

        String cleanUrl = config.repoBranchPair().repositoryURL();

        // Create and execute operating system process to clone repository
        // This is because native, OS level retrievals are faster, an advantage nessecary to handle large
        // microservices repositories.
        ProcessBuilder processBuilder =
                new ProcessBuilder("git", "clone", cleanUrl, repositoryPath);
        processBuilder.redirectErrorStream(true);

        applyGitProcessEnvironment(processBuilder);

        Process process = processBuilder.start();
        String output = readProcessOutput(process);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to clone repository (Exit Code: " + exitCode + "): "
                    + config.getRepoName() + summarizeGitOutput(output));
        } else {
            System.out.println("Cloned " + config.getRepoName());
        }
    }

    private void validateConfiguredRepositoryAccess() throws IOException, InterruptedException {
        List<RepositoryAccessFailure> failures = new ArrayList<>();

        for (RepositoryConfig rc : config.getSystemRepositories()) {
            RepositoryAccessCheck accessCheck = checkRemoteAccess(rc);
            if (!accessCheck.accessible()) {
                failures.add(new RepositoryAccessFailure(rc, accessCheck.output()));
            }
        }

        if (failures.isEmpty()) {
            return;
        }

        IOException accessDenied = new IOException(formatAccessDeniedMessage(failures));
        try {
            cleanConfiguredRepositoryDirectories();
        } catch (IOException cleanupException) {
            accessDenied.addSuppressed(cleanupException);
        }
        throw accessDenied;
    }

    private RepositoryAccessCheck checkRemoteAccess(RepositoryConfig rc) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                "git",
                "ls-remote",
                "--exit-code",
                rc.repoBranchPair().repositoryURL(),
                "HEAD"
        );
        processBuilder.redirectErrorStream(true);
        applyGitProcessEnvironment(processBuilder);

        Process process = processBuilder.start();
        String output = readProcessOutput(process);
        int exitCode = process.waitFor();
        return new RepositoryAccessCheck(exitCode == 0, output);
    }

    private void applyGitProcessEnvironment(ProcessBuilder processBuilder) {
        Map<String, String> env = processBuilder.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");

        if (this.rawToken != null && !this.rawToken.trim().isEmpty()) {
            String authStr = "x-access-token:" + this.rawToken;
            String b64Auth = Base64.getEncoder().encodeToString(authStr.getBytes(StandardCharsets.UTF_8));

            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "AUTHORIZATION: basic " + b64Auth);
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        }
    }

    private String formatAccessDeniedMessage(List<RepositoryAccessFailure> failures) {
        String inaccessibleRepositories = failures.stream()
                .map(failure -> failure.repositoryConfig().getRepoName()
                        + " (" + failure.repositoryConfig().repoBranchPair().repositoryURL() + ")"
                        + summarizeGitOutput(failure.output()))
                .collect(Collectors.joining("; "));

        return "Access denied while checking repository clone permissions. "
                + "Cleaned configured local clone directories. "
                + "Inaccessible repositories: " + inaccessibleRepositories;
    }

    private String summarizeGitOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }

        String summary = output.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(3)
                .collect(Collectors.joining(" "));
        if (this.rawToken != null && !this.rawToken.isBlank()) {
            summary = summary.replace(this.rawToken, "[redacted]");
        }

        return summary.isBlank() ? "" : " - " + summary;
    }

    private void cleanConfiguredRepositoryDirectories() throws IOException {
        IOException cleanupFailure = null;
        for (RepositoryConfig rc : config.getSystemRepositories()) {
            try {
                deleteRecursively(Path.of(FileUtils.getRepositoryPath(rc.getRepoName())));
            } catch (IOException e) {
                if (cleanupFailure == null) {
                    cleanupFailure = e;
                } else {
                    cleanupFailure.addSuppressed(e);
                }
            }
        }

        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

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
     * Decide whether a working tree's .git is a usable Git repository. A bare
     * directory whose .git is missing HEAD/config will cause JGit to walk up
     * to a parent repository, so we treat that as invalid.
     */
    private static boolean isValidGitRepo(File workTree) {
        File gitDir = new File(workTree, ".git");
        if (!gitDir.exists()) {
            return false;
        }
        // .git can also be a file (gitlink/worktree pointer); accept it.
        if (gitDir.isFile()) {
            return true;
        }
        return new File(gitDir, "HEAD").isFile() && new File(gitDir, "config").isFile();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // Read-only files (common under .git on Windows) need their write bit set
                // before delete, otherwise Files.delete throws AccessDeniedException.
                file.toFile().setWritable(true);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
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

    private record RepositoryAccessCheck(boolean accessible, String output) {
    }

    private record RepositoryAccessFailure(RepositoryConfig repositoryConfig, String output) {
    }
}
