package edu.university.ecs.lab.delta.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
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
     * The type of change that is made
     */
    private ChangeType changeType;

    /**
     * The path to the output file
     */
    private String outputPath;

    /**
     * The path to the output file
     */
    private MicroserviceSystem microserviceSystem;


    /**
     * Constructor for the DeltaExtractionService
     *
     * @param configPath path to the config file
     * @param outputPath output path for file
     * @param commitOld old commit for comparison
     * @param commitNew new commit for comparison
     */
    private DeltaExtractionService(String configPath, String oldIRPath, String outputPath, String commitOld, String commitNew) throws IOException, InterruptedException {
        this.config = ConfigUtil.readConfig(configPath);
        this.gitService = new GitService(configPath);
        this.commitOld = commitOld;
        this.commitNew = commitNew;
        this.outputPath = outputPath.isEmpty() ? "./Delta.json" : outputPath;
        this.microserviceSystem = IRExtractionService.read(Path.of(oldIRPath));
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

            changeType = ChangeType.fromDiffEntry(entry);

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

        // Output the system changes
        // JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
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
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config);
        } else {
            // TODO null right here?
            projectFile = SourceToObjectUtils.parseClass(null,
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config, "", false);
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
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config);
            return new ModifyDelta(newPath, new ArrayList<>());
        } else {

            // TODO null right here?
            AbstractClass modifyClass = SourceToObjectUtils.parseClass(null,
                    new File(FileUtils.gitPathToLocalPath(newPath.toString(), config.getRepoName())), config, "", false);

            AbstractClass oldClass = microserviceSystem.findClass(newPath.toString());

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
//                    .filter(Objects::nonNull) // Filter out potential nulls
                    .filter(o -> o instanceof MethodCall)
                    .collect(Collectors.toMap(Component::getID, Function.identity(), (existing, replacement) -> existing)); // Handle potential duplicate IDs if necessary

            Map<String, Component> modifyComponentsMap = modifyClass.getDescendants().stream()
//                    .filter(Objects::nonNull) // Filter out potential nulls
                    .filter(o -> o instanceof MethodCall)
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
                    }
                    // Remove from modifyMap to track processed components
                    modifyComponentsMap.remove(id);
                } else {
                    // Component exists in old but not in modify: DELETED
                    componentDeltas.add(new ComponentDelta(ChangeType.DELETE, oldComponent));
                }
            }

            // 3. Identify ADDED components
            // Any components left in modifyComponentsMap were not in the old map
            for (Map.Entry<String, Component> modifyEntry : modifyComponentsMap.entrySet()) {
                componentDeltas.add(new ComponentDelta(ChangeType.ADD, modifyEntry.getValue()));
            }




            return new ModifyDelta(newPath, componentDeltas);
        }
    }

    /**
     * Special consideration since MethodCall does not have unique ID
     *
     * @param oldMethodCalls
     * @param newMethodCalls
     * @return
     */
    private List<ComponentDelta> modifyMethodCalls(List<MethodCall> oldMethodCalls, List<MethodCall> newMethodCalls) {
        List<ComponentDelta> deltas = new ArrayList<>();

        // Ensure lists are not null to avoid NullPointerExceptions
        List<MethodCall> oldCalls = Objects.requireNonNullElse(oldMethodCalls, Collections.emptyList());
        List<MethodCall> newCalls = Objects.requireNonNullElse(newMethodCalls, Collections.emptyList());

        // 1. Create frequency maps (ID -> Count)
        Map<String, Long> oldCounts = oldCalls.stream()
                .filter(Objects::nonNull) // Ensure method call itself isn't null
                .map(MethodCall::getID)    // Get the ID (assuming getID() returns the non-unique identifier)
                .filter(Objects::nonNull) // Ensure ID isn't null
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        Map<String, Long> newCounts = newCalls.stream()
                .filter(Objects::nonNull)
                .map(MethodCall::getID)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        // 2. Create maps to easily find a representative MethodCall instance by ID
        // We only need one instance per ID to create the Delta object
        Map<String, MethodCall> oldRepresentativeMap = oldCalls.stream()
                .filter(mc -> mc != null && mc.getID() != null)
                .collect(Collectors.toMap(MethodCall::getID, Function.identity(), (existing, replacement) -> existing)); // Keep first found

        Map<String, MethodCall> newRepresentativeMap = newCalls.stream()
                .filter(mc -> mc != null && mc.getID() != null)
                .collect(Collectors.toMap(MethodCall::getID, Function.identity(), (existing, replacement) -> existing)); // Keep first found


        // 3. Get all unique IDs from both lists
        Set<String> allIds = new HashSet<>(oldCounts.keySet());
        allIds.addAll(newCounts.keySet());

        // 4. Compare counts for each ID
        for (String id : allIds) {
            long oldCount = oldCounts.getOrDefault(id, 0L);
            long newCount = newCounts.getOrDefault(id, 0L);
            long difference = newCount - oldCount;

            if (difference > 0) {
                // More instances in the new list -> ADD
                MethodCall representativeNewCall = newRepresentativeMap.get(id);
                if (representativeNewCall != null) { // Should always be non-null if difference > 0
                    for (int i = 0; i < difference; i++) {
                        // Use the static factory method from ComponentDelta if available
                        deltas.add(new ComponentDelta(ChangeType.ADD, representativeNewCall));
                        // Or use the constructor directly if static methods aren't used:
                        // deltas.add(new ComponentDelta(ChangeType.ADD, null, representativeNewCall));
                    }
                } else {
                    // Log a warning or handle error - shouldn't happen if logic is correct
                    System.err.println("Warning: Could not find representative new MethodCall for added ID: " + id);
                }
            } else if (difference < 0) {
                // Fewer instances in the new list -> DELETE
                MethodCall representativeOldCall = oldRepresentativeMap.get(id);
                if (representativeOldCall != null) { // Should always be non-null if difference < 0
                    long numToDelete = Math.abs(difference);
                    for (int i = 0; i < numToDelete; i++) {
                        // Use the static factory method from ComponentDelta if available
                        deltas.add(new ComponentDelta(ChangeType.DELETE, representativeOldCall));
                        // Or use the constructor directly:
                        // deltas.add(new ComponentDelta(ChangeType.DELETE, representativeOldCall, null));
                    }
                } else {
                    // Log a warning or handle error - shouldn't happen if logic is correct
                    System.err.println("Warning: Could not find representative old MethodCall for deleted ID: " + id);
                }
            }
            // If difference == 0, counts are the same, no delta needed.
        }

        return deltas;
    }



    private SystemChange getSystemChange() {
        return this.systemChange;
    }

    /**
     * This method returns a blank JsonObject() as there is no data to parse
     *
     * @return JsonObject that is empty
     */
    private AbstractDelta delete(Path oldPath) {
        return new SimpleDelta(oldPath, ChangeType.DELETE, null);
    }

    public static SystemChange create(String configPath, String oldIRPath,  String oldCommit, String newCommit) throws IOException, InterruptedException, GitAPIException {
        DeltaExtractionService extractionService = new DeltaExtractionService(configPath,oldIRPath, "", oldCommit, newCommit);
        extractionService.generateDelta();
        return extractionService.getSystemChange();
    }

    public static void createAndWrite(String configPath, String oldIRPath, String oldCommit, String newCommit, String outputPath) throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(configPath,oldIRPath, oldCommit, newCommit);
        JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
    }

    public static SystemChange read(String fPath) throws IOException {
        SystemChange systemChange = JsonReadWriteUtils.readFromJSON(fPath, SystemChange.class);
        return systemChange;
    }

}