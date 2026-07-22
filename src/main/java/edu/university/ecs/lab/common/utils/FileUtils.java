package edu.university.ecs.lab.common.utils;

import edu.university.ecs.lab.common.config.RepositoryConfig;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Manages all file paths and file path conversion functions.
 */
public class FileUtils {
    public static final Set<String> VALID_FILES = Set.of(
            "pom.xml",
            ".java",
            ".yml",
            ".yaml",
            ".properties",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "Dockerfile",
            "DockerFile",
            "docker-compose.yml",
            "docker-compose.yaml"
    );
    public static final String SYS_SEPARATOR = System.getProperty("file.separator");
    public static final String SPECIAL_SEPARATOR = SYS_SEPARATOR.replace("\\", "\\\\");
    private static final String DEFAULT_OUTPUT_PATH = "output";
    private static final String DEFAULT_CLONE_PATH = "clone";
    private static final String DEFAULT_DOCS_PATH = ".docs";
    private static final String DOT = ".";
    public static final String GIT_SEPARATOR = "/";

    /**
     * Private constructor to prevent instantiation.
     */
    private FileUtils() {}

    /**
     * This method returns the relative path of the cloned repository directory as ./DEFAULT_CLONE_PATH/repoName.
     * This will be a working relative path to the repository directory on the local file system.
     *
     * @param repoName the name of the repo
     * @return the relative path string where that repository is cloned to
     */
    public static String getRepositoryPath(String repoName) {
        return getClonePath() + SYS_SEPARATOR + repoName;
    }

    /**
     * This method returns the relative local path of the output directory as ./DEFAULT_OUTPUT_PATH.
     * This will be a working relative path to the output directory on the local file system.
     *
     * @return the relative path string where the output will exist
     */
    public static String getOutputPath() {
        return DOT + SYS_SEPARATOR + DEFAULT_OUTPUT_PATH;
    }

    /**
     * This method returns the relative local path of the documentation output directory as ./DEFAULT_DOCS_PATH.
     * This will be a working relative path to the documentation output directory on the local file system.
     *
     * @return the relative path string where the documentation output will exist
     */
    public static String getDocsPath() {
        return DOT + SYS_SEPARATOR + DEFAULT_DOCS_PATH;
    }

    /**
     * This method returns the relative local path of the output directory as ./DEFAULT_OUTPUT_PATH.
     * This will be a working relative path to the output directory on the local file system.
     *
     * @return the relative path string where the output will exist
     */
    public static String getClonePath() {
        return DOT + SYS_SEPARATOR + DEFAULT_CLONE_PATH;
    }

    /**
     * This method converts a path of the form .\clone\repoName\pathToFile to the form
     * /pathToFile
     *
     * @param localPath the local path to be converted
     * @param repoName the name of the repo cloned locally
     * @return the relative repo path
     */
    public static String localPathToGitPath(String localPath, String repoName) {
        return normalizeRepositoryPath(localPath, repoName);
    }

    /**
     * This method converts a git path of the form /pathToFile to the local
     * path under .\clone\repoName\pathToFile.
     *
     * @param localPath the git path to be converted
     * @param repoName the name of the repo cloned locally
     * @return the local file path
     */
    public static String gitPathToLocalPath(String localPath, String repoName) {
        String gitPath = normalizeRepositoryPath(localPath, repoName);
        if (GIT_SEPARATOR.equals(gitPath)) {
            return getRepositoryPath(repoName);
        }

        return getRepositoryPath(repoName) + gitPath.replace(GIT_SEPARATOR, SYS_SEPARATOR);
    }

    /**
     * Normalizes any path pointing into a cloned repository to the canonical
     * repo-relative git path used in the IR. This accepts the historical
     * /pathToFile form, local clone paths, absolute container paths, Windows
     * paths, and file:// URIs.
     *
     * @param path the path to normalize
     * @param repoName the repository folder name under clone/
     * @return a normalized repo-relative path beginning with /
     */
    public static String normalizeRepositoryPath(String path, String repoName) {
        String normalizedPath = normalizePathString(path);

        if (normalizedPath == null || normalizedPath.isBlank()) {
            return normalizedPath;
        }

        String cloneRepoMarker = GIT_SEPARATOR + DEFAULT_CLONE_PATH + GIT_SEPARATOR + repoName;
        int cloneRepoIndex = normalizedPath.indexOf(cloneRepoMarker);
        if (cloneRepoIndex >= 0) {
            return ensureLeadingGitSeparator(normalizedPath.substring(cloneRepoIndex + cloneRepoMarker.length()));
        }

        String relativeCloneRepoMarker = DEFAULT_CLONE_PATH + GIT_SEPARATOR + repoName;
        if (normalizedPath.equals(relativeCloneRepoMarker)) {
            return GIT_SEPARATOR;
        }
        if (normalizedPath.startsWith(relativeCloneRepoMarker + GIT_SEPARATOR)) {
            return ensureLeadingGitSeparator(normalizedPath.substring(relativeCloneRepoMarker.length()));
        }

        return ensureLeadingGitSeparator(normalizedPath);
    }

    /**
     * Normalizes a path string for comparison while preserving whatever root it
     * already has. Use {@link #normalizeRepositoryPath(String, String)} when a
     * repository name is available and clone-root prefixes should be stripped.
     *
     * @param path the path to normalize
     * @return a slash-normalized path with file:// stripped and trailing / removed
     */
    public static String normalizePathString(String path) {
        if (path == null) {
            return null;
        }

        String normalizedPath = path.trim().replace('\\', '/');
        if (normalizedPath.startsWith("file://")) {
            normalizedPath = normalizedPath.substring("file://".length());
        } else if (normalizedPath.startsWith("file:")) {
            normalizedPath = normalizedPath.substring("file:".length());
        }

        while (normalizedPath.startsWith("./")) {
            normalizedPath = normalizedPath.substring(2);
        }

        normalizedPath = normalizedPath.replaceAll("/+", GIT_SEPARATOR);
        return stripTrailingGitSeparators(normalizedPath);
    }

    /**
     * Compares two paths using normalized separators and a suffix fallback. The
     * fallback is intentionally useful for pre-existing IRs that stored paths
     * with an absolute clone root such as /app/clone/repo/service/File.java.
     *
     * @param storedPath a path already stored in the IR
     * @param queryPath a path being looked up
     * @return true when both paths point to the same file
     */
    public static boolean pathsMatch(Path storedPath, Path queryPath) {
        String stored = normalizePathString(storedPath == null ? null : storedPath.toString());
        String query = normalizePathString(queryPath == null ? null : queryPath.toString());

        if (stored == null || query == null) {
            return false;
        }
        if (stored.equals(query)) {
            return true;
        }

        return endsWithPathSegment(stored, query) || endsWithPathSegment(query, stored);
    }

    /**
     * Checks if a path belongs under a possible parent path. This handles both
     * canonical repo-relative paths and old clone-root-prefixed IR paths.
     *
     * @param childPath the file or directory path to check
     * @param parentPath the containing directory path
     * @return true when childPath is equal to or under parentPath
     */
    public static boolean pathStartsWith(Path childPath, Path parentPath) {
        String child = normalizePathString(childPath == null ? null : childPath.toString());
        String parent = normalizePathString(parentPath == null ? null : parentPath.toString());

        if (child == null || parent == null) {
            return false;
        }
        if (startsWithPathSegment(child, parent)) {
            return true;
        }

        for (String parentSuffix : pathSuffixes(parent)) {
            if (startsWithPathSegment(child, parentSuffix)) {
                return true;
            }
        }

        return false;
    }

    private static String ensureLeadingGitSeparator(String path) {
        String normalizedPath = stripTrailingGitSeparators(path);
        if (normalizedPath == null || normalizedPath.isBlank() || DOT.equals(normalizedPath)) {
            return GIT_SEPARATOR;
        }
        if (normalizedPath.startsWith(GIT_SEPARATOR)) {
            return normalizedPath;
        }

        return GIT_SEPARATOR + normalizedPath;
    }

    private static String stripTrailingGitSeparators(String path) {
        if (path == null) {
            return null;
        }

        String stripped = path;
        while (stripped.length() > 1 && stripped.endsWith(GIT_SEPARATOR)) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }

        return stripped;
    }

    private static boolean startsWithPathSegment(String path, String prefix) {
        if (path.equals(prefix)) {
            return true;
        }

        return path.startsWith(prefix.endsWith(GIT_SEPARATOR) ? prefix : prefix + GIT_SEPARATOR);
    }

    private static boolean endsWithPathSegment(String path, String suffix) {
        if (path.equals(suffix)) {
            return true;
        }

        return path.endsWith(suffix.startsWith(GIT_SEPARATOR) ? suffix : GIT_SEPARATOR + suffix);
    }

    private static Set<String> pathSuffixes(String path) {
        Set<String> suffixes = new java.util.LinkedHashSet<>();
        String normalizedPath = path.startsWith(GIT_SEPARATOR) ? path.substring(1) : path;
        String[] segments = normalizedPath.split(GIT_SEPARATOR);

        for (int i = 0; i < segments.length; i++) {
            suffixes.add(GIT_SEPARATOR + String.join(GIT_SEPARATOR, Arrays.copyOfRange(segments, i, segments.length)));
        }

        return suffixes;
    }

    /**
     * Fallback to get microservice name if other methods such as reading pom.xml, settings.gradle, etc. fail
     * @param path The path to the microservice
     * @return Raw microservice name based on the path
     */
    public static Optional<String> getMicroserviceNameFromPath(String path) {
        if (!path.startsWith(DOT + SYS_SEPARATOR + DEFAULT_CLONE_PATH + SYS_SEPARATOR)) {
            throw new IllegalArgumentException("A malformed path was provided");
        }

        String[] split = path.replace(DOT + SYS_SEPARATOR + DEFAULT_CLONE_PATH + SYS_SEPARATOR, "").split(SPECIAL_SEPARATOR);

        if (split.length == 0) {
            return Optional.empty();
        }

        return Optional.of(split[split.length-1]);
    }

    /**
     * This method returns a Git path without the filename at the end.
     *
     * @param path the path to remove filename from
     * @return the path without the file name or if too short just GIT_SEPARATOR
     */
    public static String getGitPathNoFileName(String path) {
        String[] split = path.split(GIT_SEPARATOR);

        if(split.length > 1) {
            return String.join(GIT_SEPARATOR, Arrays.copyOfRange(split, 0, split.length - 1));
        } else {
            return GIT_SEPARATOR;
        }
    }

    /**
     * This method creates the default output and clone directories
     */
    public static void makeDirs() {
        new File(getOutputPath()).mkdirs();
        new File(getClonePath()).mkdirs();
        new File(getDocsPath()).mkdirs();
    }

    /**
     * This method filters the file's that should be present in the project
     *
     * @param path the file for checking
     * @return boolean true if it belongs in the project
     */
    public static boolean isValidFile(String path) {
        // Special check for github metadata files
        if(path.contains(".github")) {
            return false;
        }

        for(String f : VALID_FILES) {
            if(path.endsWith(f)) {
                return true;
            }
        }

        return false;
    }

    /**
     * This method filters the static files present in the project,
     * not including Java source file but configuration files only
     *
     * @param path the file for checking
     * @return boolean true if it is a configuration file
     */
    public static boolean isConfigurationFile(String path) {
        return isValidFile(path) && !path.endsWith(".java");
    }

    /**
     * This method returns a path for a partial IR file based on a RepositoryConfig.
     *
     * @param rc The RepositoryConfig
     * @return a Path including the file name where the partial IR should be written
     */
    public static Path getPartialIRPath(RepositoryConfig rc) {
        String fileName = "PART_" + rc.getRepoName() + "_" + rc.repoBranchPair().branchName() + "_" + rc.commitID() + ".json";
        return Path.of(getOutputPath() + File.separator + fileName);
    }
}
