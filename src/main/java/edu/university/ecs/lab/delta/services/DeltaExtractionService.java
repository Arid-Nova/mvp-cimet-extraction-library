package edu.university.ecs.lab.delta.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.config.Config;
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
     * Config object representing the contents of the config file
     */
    private final Config config;

    /**
     * GitService instance for interacting with the local repository
     */
    private final GitService gitService;

    /**
     * The old commit for comparison
     */
    private final String commitOld;

    /**
     * The new commit for comparison
     */
    private final String commitNew;

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
     * @param commitNew The new commit for comparison
     */
    private DeltaExtractionService(Config config, MicroserviceSystem intermediateSystem, String commitNew) throws IOException, InterruptedException {
        this.config = config;
        this.gitService = new GitService(config);
        this.commitOld = intermediateSystem.getCommitID();
        this.commitNew = commitNew;
        this.microserviceSystem = intermediateSystem;
    }

    /**
     * Generates Delta file representing changes between commitOld and commitNew
     */
    private void generateDelta() throws GitAPIException, IOException {
        List<DiffEntry> differences = null;

        // Ensure we start at commitOld
        gitService.resetLocal(commitOld);

        // Get the differences between commits
        differences = gitService.getDifferences(commitOld, commitNew);

        // Advance the local commit for parsing
        gitService.resetLocal(commitNew);

        // process/write differences to delta output
        processDelta(differences);
    }

    /**
     * Process differences between commits
     *
     * @param diffEntries list of differences
     */
    private void processDelta(List<DiffEntry> diffEntries) {
        // Set up a new SystemChangeObject
        systemChange = new SystemChange();
        systemChange.setOldCommit(commitOld);
        systemChange.setNewCommit(commitNew);
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
                    abstractDelta = add(Path.of(newPath));
                    break;
                case MODIFY:
                    abstractDelta = modify(Path.of(oldPath), microserviceSystem);
                    break;
                case DELETE:
                    abstractDelta = delete(Path.of(oldPath));
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
    private AbstractDelta add(Path newPath) {
        ProjectFile projectFile = null;
        if (FileUtils.isConfigurationFile(newPath.toString())) {
            projectFile = SourceToObjectUtils.parseConfigurationFile(
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config, null);
        } else {
            projectFile = SourceToObjectUtils.parseClass(null,
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config,  false);
        }

        return new SimpleDelta(newPath, ChangeType.ADD, projectFile);
    }

    /**
     * This method parses a newly added file into a JsonObject containing
     * the data of the change (updated file). Returns a blank JsonObject if
     * parsing fails (returns null).
     *
     * @return JsonObject of data of the new file
     */
    private AbstractDelta modify(Path newPath, MicroserviceSystem microserviceSystem) {
        if (FileUtils.isConfigurationFile(newPath.toString())) {
            ConfigFile configFile = SourceToObjectUtils.parseConfigurationFile(
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config, null);
            return new ModifyDelta(newPath, new ArrayList<>(), new SimpleDelta(newPath, ChangeType.MODIFY, configFile));
        } else {
            AbstractClass modifyClass = SourceToObjectUtils.parseClass(null,
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config,  false);

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

            return new ModifyDelta(newPath, componentDeltas, new SimpleDelta(newPath, ChangeType.MODIFY, modifyClass));
        }
    }

    /**
     * This method returns a blank JsonObject() as there is no data to parse
     *
     * @return JsonObject that is empty
     */
    private AbstractDelta delete(Path oldPath) {
        return new SimpleDelta(oldPath, ChangeType.DELETE, null);
    }

    private SystemChange getSystemChange() {
        return this.systemChange;
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit
     * @param config The configuration to use
     * @param intermediateSystem The base MicroserviceSystem
     * @param newCommit The commit to compare the base system with
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange create(Config config, MicroserviceSystem intermediateSystem, String newCommit) throws IOException, InterruptedException, GitAPIException {
        DeltaExtractionService extractionService = new DeltaExtractionService(config, intermediateSystem, newCommit);
        extractionService.generateDelta();
        return extractionService.getSystemChange();
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * @param config The configuration to use
     * @param baseIRPath The path to the base IR to compare with
     * @param newCommit The commit to compare the base system with
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange create(Config config, Path baseIRPath, String newCommit) throws IOException, InterruptedException, GitAPIException {
        return create(config, IRExtractionService.read(baseIRPath), newCommit);
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * Additionally, it writes the Delta to a file.
     * @param config The configuration to use
     * @param intermediateSystem The base MicroserviceSystem
     * @param newCommit The commit to compare the base system with
     * @param outputPath The path to output the Delta to
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange createAndWrite(Config config, MicroserviceSystem intermediateSystem, String newCommit, Path outputPath) throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(config, intermediateSystem, newCommit);
        JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
        return systemChange;
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * Additionally, it writes the Delta to a file.
     * @param config The configuration to use
     * @param baseIRPath The path to the base IR to compare with
     * @param newCommit The commit to compare the base system with
     * @param outputPath The path to output the Delta to
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange createAndWrite(Config config, Path baseIRPath, String newCommit, Path outputPath) throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(config, baseIRPath, newCommit);
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