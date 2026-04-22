package edu.university.ecs.lab.delta.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import edu.university.ecs.lab.delta.models.*;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for extracting the differences between two commits of a repository.
 * This class does cleaning of output so not all changes will be reflected in
 * the Delta output file.
 */
public class DeltaExtractionService {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String DEV_NULL = "/dev/null";

    /**
     * GitService instance for interacting with the local repository
     */
    private final GitService gitService;

    /**
     * The old commit per repository for comparison
     */
    private List<RepositoryConfig> oldCommits;

    /**
     * The new commit per repository for comparison
     */
    private final Map<RepositoryConfig, String> newCommits;

    /**
     * System change object that will be returned
     */
    private SystemChange systemChange;

    /**
     * The path to the output file
     */
    private final MicroserviceSystem microserviceSystem;

    /**
     * Constructor for the DeltaExtractionService
     *
     * @param config The Config to use
     * @param intermediateSystem The base MicroserviceSystem to generate the Delta from
     * @param newCommits The commit IDs of the new commits for each repository
     */
    private DeltaExtractionService(Config config, MicroserviceSystem intermediateSystem, Map<RepositoryConfig, String> newCommits) throws IOException, InterruptedException {
        this.gitService = new GitService(config);
        this.newCommits = newCommits;
        this.microserviceSystem = intermediateSystem;
        this.oldCommits = config.getSystemRepositories();
    }

    /**
     * Generates Delta for multiple repositories, representing changes between old commits and new commits
     */
    private void generateMultiRepoDelta() throws GitAPIException, IOException {
        for(RepositoryConfig entry : oldCommits) {
            generateDelta(entry);
        }
    }

    /**
     * Generates Delta for a repository representing changes between an old commit and new commit
     */
    private void generateDelta(RepositoryConfig rc) throws GitAPIException, IOException {

        // Old repository in concern
        RepositoryBranchPair genericRepoBranch = rc.repoBranchPair();
        String oldCommit = rc.commitID();

        // Ensure we start at commitOld
        gitService.resetLocal(rc, oldCommit);

        // Checking if there are multiple versions of the same repository in comparison?
        List<String> commitIDs = newCommits.entrySet()
                .stream()
                .filter(entry -> entry.getKey().repoBranchPair().equals(genericRepoBranch))
                .map(Map.Entry::getValue)
                .toList();

        for (String newCommit: commitIDs) {
            // Get the differences between commits
            List<DiffEntry> differences = gitService.getDifferences(genericRepoBranch, oldCommit, newCommit);

            // Advance the local commit for parsing
            gitService.resetLocal(rc, newCommit);

            // process/write differences to delta output
            processDelta(rc, newCommit, differences);
        }
    }

    /**
     * Process differences between commits
     *
     * @param diffEntries list of differences
     */
    private void processDelta(RepositoryConfig rc, String newCommit, List<DiffEntry> diffEntries) {
        // Set up a new SystemChangeObject
        systemChange = new SystemChange();
        systemChange.getOldCommits().put(rc.repoBranchPair().repositoryURL(), rc.commitID());
        systemChange.getNewCommits().put(rc.repoBranchPair().repositoryURL(), newCommit);
        AbstractDelta abstractDelta = null;

        // process each difference
        for (DiffEntry entry : diffEntries) {
            // Git path
            String path = entry.getChangeType().equals(DiffEntry.ChangeType.ADD) ? entry.getNewPath() : entry.getOldPath();

            // Special case for root pom
            if(path.equals("pom.xml")) {
                continue;
            }

            // Guard condition, skip invalid files
            if(!FileUtils.isValidFile(path)) {
                continue;
            }

            // Setup oldPath, newPath for Delta
            String oldPath = "";
            String newPath = "";

            if (DiffEntry.ChangeType.DELETE.equals(entry.getChangeType())) {
                oldPath = FileUtils.GIT_SEPARATOR + entry.getOldPath();
                newPath = DEV_NULL;

            } else if (DiffEntry.ChangeType.ADD.equals(entry.getChangeType())) {
                oldPath = DEV_NULL;
                newPath = FileUtils.GIT_SEPARATOR + entry.getNewPath();

            } else {
                oldPath = FileUtils.GIT_SEPARATOR + entry.getOldPath();
                newPath = FileUtils.GIT_SEPARATOR + entry.getNewPath();
            }

            /**
             * The type of change that is made
             */
            ChangeType changeType = ChangeType.fromDiffEntry(entry);

            switch(changeType) {
                case ADD:
                    abstractDelta = add(rc, Path.of(newPath));
                    break;
                case MODIFY:
                    abstractDelta = modify(rc, Path.of(oldPath), microserviceSystem);
                    break;
                case DELETE:
                    abstractDelta = delete(rc, Path.of(oldPath));
            }

            systemChange.getChanges().add(abstractDelta);
        }
    }

    /**
     * This method parses a newly added file into a JsonObject containing
     * the data of the change (updated file). Returns a blank JsonObject if
     * parsing fails (returns null).
     *
     * @param newPath git path of new file
     * @return JsonObject of data of the new file
     */
    private AbstractDelta add(RepositoryConfig rc, Path newPath) {
        ProjectFile projectFile = null;
        if (FileUtils.isConfigurationFile(newPath.toString())) {
            projectFile = SourceToObjectUtils.parseConfigurationFile(
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), rc.getRepoName())), rc, null);
        } else {
            projectFile = SourceToObjectUtils.parseClass(null,
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), rc.getRepoName())), rc,  false);
        }

        return new SimpleDelta(newPath, ChangeType.ADD, rc.repoBranchPair().repositoryURL(), projectFile);
    }

    /**
     * This method parses a newly added file into a JsonObject containing
     * the data of the change (updated file). Returns a blank JsonObject if
     * parsing fails (returns null).
     *
     * @return JsonObject of data of the new file
     */
    private AbstractDelta modify(RepositoryConfig rc, Path newPath, MicroserviceSystem microserviceSystem) {
        if (FileUtils.isConfigurationFile(newPath.toString())) {
            ConfigFile configFile = SourceToObjectUtils.parseConfigurationFile(
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), rc.getRepoName())), rc, null);
            return new ModifyDelta(newPath, new ArrayList<>(), rc.repoBranchPair().repositoryURL(),
                    new SimpleDelta(newPath, ChangeType.MODIFY, rc.repoBranchPair().repositoryURL(), configFile));
        } else {
            AbstractClass modifyClass = SourceToObjectUtils.parseClass(null,
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), rc.getRepoName())), rc, false);

            AbstractClass oldClass = microserviceSystem.findClass(newPath);

            if(oldClass == null && modifyClass != null) {
                throw new RuntimeException("Failed to find old class");
            } else if(oldClass == null) {
                return null;
            } else if(modifyClass == null) {
                return null;
            }

            List<ComponentDelta> componentDeltas = new ArrayList<>();

            // 1. Create maps for efficient lookup by component ID
            // Use getDescendants() which should recursively get all components
            Map<String, Component> oldComponentsMap = oldClass.getDescendants().stream()
                    .collect(Collectors.toMap(Component::getID, Function.identity(), (existing, replacement) -> existing)); // Handle potential duplicate IDs if necessary

            Map<String, Component> modifyComponentsMap = modifyClass.getDescendants().stream()
                    .collect(Collectors.toMap(Component::getID, Function.identity(), (existing, replacement) -> existing)); // Handle potential duplicate IDs

            // 2. Identify MODIFIED and DELETED components
            for (Map.Entry<String, Component> oldEntry : oldComponentsMap.entrySet()) {
                String id = oldEntry.getKey();
                Component oldComponent = oldEntry.getValue();
                Component modifyComponent = modifyComponentsMap.get(id);

                if (modifyComponent != null) {
                    // Component exists in both: Check for modification
                    if (!Objects.equals(oldComponent, modifyComponent)) {
                        // Components with the same ID are not equal, hence modified
                        componentDeltas.add(new ComponentDelta(ChangeType.MODIFY, modifyComponent));

                        // Prevent duplication of descendants
                        modifyComponent.clearDescendants();
                    }
                    // Remove from modifyMap to track processed components
                    modifyComponentsMap.remove(id);
                } else {
                    // Component exists in old but not in modify: DELETED
                    componentDeltas.add(new ComponentDelta(ChangeType.DELETE, oldComponent));

                    // Prevent duplication of descendants
                    oldComponent.clearDescendants();
                }
            }

            // 3. Identify ADDED components
            // Any components left in modifyComponentsMap were not in the old map
            for (Map.Entry<String, Component> modifyEntry : modifyComponentsMap.entrySet()) {
                componentDeltas.add(new ComponentDelta(ChangeType.ADD, modifyEntry.getValue()));
            }

            modifyClass.clearDescendants();

            return new ModifyDelta(newPath, componentDeltas, rc.repoBranchPair().repositoryURL(),
                    new SimpleDelta(newPath, ChangeType.MODIFY, rc.repoBranchPair().repositoryURL(), modifyClass));
        }
    }

    /**
     * This method returns a blank JsonObject() as there is no data to parse
     *
     * @return JsonObject that is empty
     */
    private AbstractDelta delete(RepositoryConfig rc, Path oldPath) {
        return new SimpleDelta(oldPath, ChangeType.DELETE, rc.repoBranchPair().repositoryURL(), null);
    }

    private SystemChange getSystemChange() {
        return this.systemChange;
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit
     * @param config The configuration to use
     * @param intermediateSystem The base MicroserviceSystem
     * @param newCommits The commits to compare the base system with
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange create(Config config, MicroserviceSystem intermediateSystem, Map<RepositoryConfig, String> newCommits) throws IOException, InterruptedException, GitAPIException {
        DeltaExtractionService extractionService = new DeltaExtractionService(config, intermediateSystem, newCommits);
        extractionService.generateMultiRepoDelta();
        return extractionService.getSystemChange();
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * @param config The configuration to use
     * @param baseIRPath The path to the base IR to compare with
     * @param newCommits The commits to compare the base system with
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange create(Config config, Path baseIRPath, Map<RepositoryConfig, String> newCommits)
            throws IOException, InterruptedException, GitAPIException {
        return create(config, IRExtractionService.read(baseIRPath), newCommits);
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * Additionally, it writes the Delta to a file.
     * @param config The configuration to use
     * @param intermediateSystem The base MicroserviceSystem
     * @param newCommits The commits to compare the base system with
     * @param outputPath The path to output the Delta to
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange createAndWrite(Config config,
                                              MicroserviceSystem intermediateSystem,
                                              Map<RepositoryConfig, String> newCommits,
                                              Path outputPath)
            throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(config, intermediateSystem, newCommits);
        JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
        return systemChange;
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * Additionally, it writes the Delta to a file.
     * @param config The configuration to use
     * @param baseIRPath The path to the base IR to compare with
     * @param newCommits The commits to compare the base system with
     * @param outputPath The path to output the Delta to
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange createAndWrite(Config config,
                                              Path baseIRPath,
                                              Map<RepositoryConfig, String> newCommits,
                                              Path outputPath)
            throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(config, baseIRPath, newCommits);
        JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
        return systemChange;
    }

    /**
     * Reads a Delta from a file.
     * @param path The path to the Delta
     * @return A {@link SystemChange} containing all the changes made in a system from one commit to a newer commit
     */
    public static SystemChange read(Path path) throws IOException {
        return JsonReadWriteUtils.readFromJSON(path, SystemChange.class);
    }
}