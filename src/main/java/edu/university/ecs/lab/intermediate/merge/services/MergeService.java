package edu.university.ecs.lab.intermediate.merge.services;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.Delta;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.delta.models.enums.ChangeType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;


/**
 * This class is used for creating new IR's from old IR + Delta
 * and provides all functionality related to updating the old
 * IR
 */
public class MergeService {
    private final Config config;
    private final MicroserviceSystem microserviceSystem;
    private final SystemChange systemChange;
    private final String outputPath;

    // TODO handle exceptions here
    public MergeService(
            String intermediatePath,
            String deltaPath,
            String configPath,
            String outputPath) throws IOException {
        this.config = ConfigUtil.readConfig(configPath);
        this.microserviceSystem = JsonReadWriteUtils.readFromJSON(Path.of(intermediatePath).toAbsolutePath().toString(), MicroserviceSystem.class);
        this.systemChange = JsonReadWriteUtils.readFromJSON(Path.of(deltaPath).toAbsolutePath().toString(), SystemChange.class);
        this.outputPath = outputPath.isEmpty() ? "./NewIR.json" : outputPath;
    }

    /**
     * This method generates the new IR from the old IR + Delta file
     */
    public void generateMergeIR(String newCommitID) {

        // If no changes are present we will write back out same IR
        if (Objects.isNull(systemChange.getChanges())) {
            // JsonReadWriteUtils.writeToJSON(outputPath, microserviceSystem);
            return;
        }


        // First we make necessary changes to microservices
        updateMicroservices();

        for (Delta d : systemChange.getChanges()) {

            switch (d.getChangeType()) {
                case ADD:
                    addFile(d);
                    break;
                case MODIFY:
                    removeFile(d);
                    addFile(d);
                    break;
                case DELETE:
                    removeFile(d);
                    break;
            }
        }

        microserviceSystem.setCommitID(systemChange.getNewCommit());

        //  JsonReadWriteUtils.writeToJSON(outputPath, microserviceSystem);
    }


    /**
     * This method adds a JClass based on a Delta change
     *
     * @param delta the delta change for adding
     */
    public void addFile(Delta delta) {
        // Check for unparsable files
        if(delta.getClassChange() == null && delta.getConfigChange() == null)
            return;

        Microservice ms = microserviceSystem.findMicroserviceByPath(delta.getNewPath());

        // If no ms is found, it will be held in orphans
        if (Objects.isNull(ms)) {
            if(delta.getClassChange() != null) {
                microserviceSystem.getOrphans().add(delta.getClassChange());
            } else if(delta.getConfigChange() != null) {
                microserviceSystem.getOrphans().add(delta.getConfigChange());
            }

            return;
        }

        // If we found it's ms
        if(delta.getConfigChange() != null) {
            ms.getFiles().add(delta.getConfigChange());
        } else {
            // Add the JClass, the microservice name is updated see addJClass()
            ms.addJClass(delta.getClassChange());
        }
    }

    /**
     * This method removes a JClass based on a Delta change
     * Note it might not be found, so it will handle this gracefully
     *
     * @param delta the delta change for removal
     */
    public void removeFile(Delta delta) {
        Microservice ms = microserviceSystem.findMicroserviceByPath(delta.getOldPath());

        // If we are removing a file and it's microservice doesn't exist
        if (Objects.isNull(ms)) {
            // Check the orphan pool
            for (ProjectFile orphan : microserviceSystem.getOrphans()) {
                // If found remove it and return
                if (orphan.getPath().equals(delta.getOldPath())) {
                    microserviceSystem.getOrphans().remove(orphan);
                    return;
                }
            }
            return;
        }

        // Remove the file depending on which is null, skips gracefully if not found in microservice
        // see removeProjectFile()
        ms.removeProjectFile(delta.getOldPath());
    }


    /**
     * Method for updating MicroserviceSystem structure (microservices) based on
     * pom.xml changes in Delta file
     *
     */
    private void updateMicroservices() {

        // See filterBuildDeltas()
        List<Delta> buildDeltas = filterBuildDeltas();

        if(buildDeltas.isEmpty()) {
            return;
        }

        // Loop through changes to pom.xml files
        for (Delta delta : buildDeltas) {
            Microservice microservice;
            String[] tokens;

            String path = delta.getOldPath().equals("/dev/null") ? delta.getNewPath() : delta.getOldPath();
            tokens = path.split("/");

            match: {
                switch (delta.getChangeType()) {
                    case ADD:

                        // If a delta is more/less specific than an active microservice
                        Microservice removeMicroservice = null;
                        for (Microservice compareMicroservice : microserviceSystem.getMicroservices()) {
                            // If delta is more specific than compareMicroservice, we remove this one
                            if (delta.getNewPath().replace("/pom.xml", "").replace("/build.gradle", "").matches(compareMicroservice.getPath() + "/.*")) {
                                removeMicroservice = compareMicroservice;

                            // If a microservice already exists that is more specific, skip the addition
                            } else if (compareMicroservice.getPath().matches(delta.getNewPath().replace("/pom.xml", "").replace("/build.gradle", "") + "/.*")) {
                                break match;
                            }
                        }

                        // If a match was found, orphanize and remove. They will be adopted below
                        if (Objects.nonNull(removeMicroservice)) {
                            microserviceSystem.getMicroservices().remove(removeMicroservice);
                            microserviceSystem.orphanize(removeMicroservice);
                        }

                        microservice = new Microservice(tokens[tokens.length - 2], delta.getNewPath().replace("/pom.xml", "").replace("/build.gradle", ""));
                        // Here we must check if any orphans are waiting on this creation
                        microserviceSystem.adopt(microservice);
                        microserviceSystem.getMicroservices().add(microservice);
                        break;


                    case DELETE:
                        microservice = microserviceSystem.findMicroserviceByPath(delta.getOldPath().replace("/pom.xml", "").replace("/build.gradle", ""));

                        // Here we must orphan all the classes of this microservice
                        microserviceSystem.getMicroservices().remove(microservice);
                        microserviceSystem.orphanize(microservice);
                        break;

                }
            }

        }


    }

    /**
     * Filter's the delta files that deal with building project
     * so either pom.xml or build.gradle
     *
     * @return a list of system changes that deal with build files that aren't modifications
     */
    private List<Delta> filterBuildDeltas() {
        // deltaChanges.stream().filter(delta -> (delta.getOldPath() == null || delta.getOldPath().isEmpty() ? delta.getNewPath() : delta.getOldPath()).endsWith("/pom.xml")).collect(Collectors.toUnmodifiableList());
        List<Delta> filteredDeltas = new ArrayList<>(systemChange.getChanges());

        // Remove non build related files
        filteredDeltas.removeIf(delta -> !(delta.getOldPath().endsWith("/pom.xml") || delta.getNewPath().endsWith("/pom.xml")) && !(delta.getOldPath().endsWith("/build.gradle") || delta.getNewPath().endsWith("/build.gradle")));

        // Remove modified files, doesn't change microservice structure
        filteredDeltas.removeIf(delta -> delta.getChangeType().equals(ChangeType.MODIFY));

        // Remove deleted files, if their microservice doesn't exist (they were less specific and were filtered out)
        filteredDeltas.removeIf(delta -> (delta.getChangeType().equals(ChangeType.DELETE) && microserviceSystem.findMicroserviceByPath(delta.getOldPath()) == null));

        // Remove more specific build deltas in the same system change
        List<Delta> filteredDeltasCopy = new ArrayList<>(List.copyOf(filteredDeltas));


        // If a delta is more specific than another in same SystemChange,
        // we need to remove the more general option in case of add
        List<Delta> addDeltas = filteredDeltas.stream().filter(d -> d.getChangeType().equals(ChangeType.ADD)).collect(Collectors.toList());
        boolean deletedFirst = false;
        for(Delta delta1 : addDeltas) {
            for(Delta delta2 : addDeltas) {
                // If they are equal or they aren't both additions
                if(delta1.equals(delta2) || !delta1.getChangeType().equals(ChangeType.ADD) || !delta2.getChangeType().equals(ChangeType.ADD)) {
                    continue;
                }
                String delta1Path = delta1.getNewPath().replace("/pom.xml", "").replace("/build.gradle", "");
                String delta2Path = delta2.getNewPath().replace("/pom.xml", "").replace("/build.gradle", "");
                if(delta1Path.equals(delta2Path) && !deletedFirst) {
                    filteredDeltas.remove(delta1);
                    deletedFirst = true;
                    continue;
                }

                // Check if paths are more/less specific
                if(delta1Path.matches(delta2Path + "/.*")) {
                    filteredDeltasCopy.remove(delta2);
                } else if(delta2Path.matches(delta1Path + "/.*")) {
                    filteredDeltasCopy.remove(delta1);
                }
            }
        }

        deletedFirst = false;
        // Remove duplicate deletes (pom.xml and build.gradle) of the same microservice
        List<Delta> deleteDeltas = filteredDeltas.stream().filter(d -> d.getChangeType().equals(ChangeType.DELETE)).collect(Collectors.toList());
        for(Delta delta1 : deleteDeltas) {
            for(Delta delta2 : deleteDeltas) {
                String delta1Path = delta1.getOldPath().replace("/pom.xml", "").replace("/build.gradle", "");
                String delta2Path = delta2.getOldPath().replace("/pom.xml", "").replace("/build.gradle", "");


                // If they are equal and they aren't both additions, arbitrarily remove one of them
                if(delta1Path.equals(delta2Path) && !delta1.getOldPath().equals(delta2.getOldPath()) && !deletedFirst) {
                    filteredDeltasCopy.remove(delta1);
                    deletedFirst = true;
                }
            }
        }


        return filteredDeltasCopy;
    }

    private String getMicroserviceNameFromPath(String path) {
        for (Microservice microservice : microserviceSystem.getMicroservices()) {
            if (path.contains(microservice.getPath())) {
                return microservice.getName();
            }
        }

        return null;
    }

    private MicroserviceSystem getMicroserviceSystem() {
        return this.microserviceSystem;
    }

    public static MicroserviceSystem create(String configPath, String intermediatePath, String deltaPath, String newCommitID) throws IOException {
        MergeService mergeService = new MergeService(intermediatePath, deltaPath, configPath, "");
        mergeService.generateMergeIR(newCommitID);
        return mergeService.getMicroserviceSystem();
    }

    public static void createAndWrite(String configPath, String intermediatePath, String deltaPath, String newCommitID, String outputPath) throws IOException {
        MicroserviceSystem microserviceSystem = create(configPath, intermediatePath, deltaPath, newCommitID);
        JsonReadWriteUtils.writeToJSON(outputPath, microserviceSystem);
    }

}
