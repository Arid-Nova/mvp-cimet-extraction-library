package edu.university.ecs.lab.intermediate.merge.services;

import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.StringUtils;
import edu.university.ecs.lab.delta.models.*;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;

import java.io.File;
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
    private final MicroserviceSystem microserviceSystem;
    private final SystemChange systemChange;

    public MergeService(
            MicroserviceSystem intermediateSystem,
            SystemChange delta) {
        this.microserviceSystem = intermediateSystem;
        this.systemChange = delta;
    }

    /**
     * This method generates the new IR from the old IR + Delta file
     */
    public void generateMergeIR() {
        // If no changes are present, return
        if (Objects.isNull(systemChange.getChanges())) {
            return;
        }

        // First we make necessary changes to microservices
        updateMicroservices();

        for (AbstractDelta d : systemChange.getChanges()) {

            switch (d.getChangeType()) {
                case ADD:
                    addFile((SimpleDelta) d);
                    break;
                case MODIFY:
                    modifyFile((ModifyDelta) d);
                    break;
                case DELETE:
                    removeFile((SimpleDelta) d);
                    break;
            }
        }

        microserviceSystem.setCommitID(systemChange.getNewCommit());
        MicroserviceSystem.setParentReferencesRecursively(microserviceSystem, null);
    }


    /**
     * This method adds a ProjectFile based on a Delta change
     *
     * @param delta the delta change for adding
     */
    public void addFile(SimpleDelta delta) {
        Microservice ms = microserviceSystem.findMicroserviceByPath(delta.getPath());

        // If no ms is found, it will be held in orphans
        if (Objects.isNull(ms)) {
            microserviceSystem.getOrphans().add(delta.getProjectFile());
            return;
        }

        // If we found it's ms
        if(delta.getProjectFile() instanceof ConfigFile) {
            ms.getFiles().add((ConfigFile) delta.getProjectFile());
        } else {
            // Add the AbstractClass, the microservice name is updated see addJClass()
            ms.addAbstractClass((AbstractClass) delta.getProjectFile());
        }
    }

    /**
     * This method modifies a ProjectFile based on a Delta change
     *
     * @param delta the delta change for modifying
     */
    public void modifyFile(ModifyDelta delta) {
        Microservice ms = microserviceSystem.findMicroserviceByPath(delta.getPath());

        // If no ms is found, it will be held in orphans
        if (Objects.isNull(ms)) {
            if (delta.getFileDelta().getProjectFile() instanceof ConfigFile) {
                microserviceSystem.getOrphans().add(delta.getFileDelta().getProjectFile());
            }
            else {
                unflattenAndAddClass(delta);
            }
        }

        // If we found it's ms
        if(delta.getFileDelta().getProjectFile() instanceof ConfigFile) {
            ms.removeProjectFile(delta.getFileDelta().getProjectFile().getPath().normalize().toString());
            ms.getFiles().add((ConfigFile) delta.getFileDelta().getProjectFile());
        } else {
            List<Component> components = new ArrayList<>();
            Optional<AbstractClass> old = ms.getClasses().stream().filter(cf -> Objects.equals(cf.getID(), delta.getFileDelta().getProjectFile().getID())).findFirst();

            if (old.isPresent()) {
                // Copy over new class name, etc. so IDs match up
                AbstractClass aClass = (AbstractClass) delta.getFileDelta().getProjectFile();
                aClass.setImports(old.get().getImports());
                aClass.setFields(old.get().getFields());
                aClass.setMethods(old.get().getMethods());
                aClass.setAnnotations(old.get().getAnnotations());

                components.addAll(aClass.getDescendants());
                for (ComponentDelta cd : delta.getComponentDeltas()) {
                    switch (cd.getChangeType()) {
                        case ADD:
                            components.add(cd.getComponent());
                            break;
                        case DELETE:
                            components.remove(components.stream().filter(c -> c.getID().equals(cd.getComponent().getID())).findFirst().get());
                            break;
                        case MODIFY:
                            components.remove(components.stream().filter(c -> c.getID().equals(cd.getComponent().getID())).findFirst().get());
                            components.add(cd.getComponent());
                            break;
                    }
                }

                // Prepare to unflatten the class
                aClass = (AbstractClass) delta.getFileDelta().getProjectFile();

                // Add descendants to the class
                recursiveAddDescendants(components, aClass);

                ms.addAbstractClass(aClass);

            } else {
                // Prepare to unflatten the class
                AbstractClass aClass = (AbstractClass) delta.getFileDelta().getProjectFile();

                // Get descendants of the class
                components = delta.getComponentDeltas().stream().filter(cd -> cd.getChangeType() != ChangeType.DELETE)
                        .map(ComponentDelta::getComponent).toList();

                // Add descendants to the class
                recursiveAddDescendants(components, aClass);

                ms.addAbstractClass(aClass);
            }
        }
    }

    /**
     * This method removes a ProjectFile based on a Delta change
     * Note it might not be found, so it will handle this gracefully
     *
     * @param delta the delta change for removal
     */
    public void removeFile(AbstractDelta delta) {
        Microservice ms = microserviceSystem.findMicroserviceByPath(delta.getPath());

        // If we are removing a file, and its microservice doesn't exist
        if (Objects.isNull(ms)) {
            // Check the orphan pool
            for (ProjectFile orphan : microserviceSystem.getOrphans()) {
                // If found remove it and return
                if (orphan.getPath().equals(delta.getPath())) {
                    microserviceSystem.getOrphans().remove(orphan);
                    return;
                }
            }
            return;
        }

        // Remove the file depending on which is null, skips gracefully if not found in microservice
        // see removeProjectFile()
        ms.removeProjectFile(delta.getPath().normalize().toString());
    }

    /**
     * Method for updating MicroserviceSystem structure (microservices) based on
     * pom.xml changes in Delta file
     */
    private void updateMicroservices() {

        // See filterBuildDeltas()
        List<AbstractDelta> buildDeltas = filterBuildDeltas();

        if(buildDeltas.isEmpty()) {
            return;
        }

        // Loop through changes to pom.xml files
        for (AbstractDelta delta : buildDeltas) {
            Microservice microservice;
            String[] tokens;

            String path = delta.getPath().normalize().toString();
            String separator = File.separator;
            if (separator.equals("\\"))
                separator = "\\\\";
            tokens = path.split(separator);

            match: {
                switch (delta.getChangeType()) {
                    case ADD:
                        // If a delta is more/less specific than an active microservice
                        Microservice removeMicroservice = null;
                        for (Microservice compareMicroservice : microserviceSystem.getMicroservices()) {
                            // If delta is more specific than compareMicroservice, we remove this one
                            if (delta.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "").matches((compareMicroservice.getPath().normalize().toString() + File.separator + ".*").replace("\\", "\\\\"))) {
                                removeMicroservice = compareMicroservice;
                            // If a microservice already exists that is more specific, skip the addition
                            } else if (compareMicroservice.getPath().normalize().toString().matches((delta.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "") + separator + ".*").replace("\\", "\\\\"))) {
                                break match;
                            }
                        }

                        // If a match was found, orphanize and remove. They will be adopted below
                        if (Objects.nonNull(removeMicroservice)) {
                            microserviceSystem.getMicroservices().remove(removeMicroservice);
                            microserviceSystem.orphanize(removeMicroservice);
                        }

                        microservice = new Microservice(microserviceSystem, tokens[tokens.length - 2], Path.of(delta.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "")));
                        // Here we must check if any orphans are waiting on this creation
                        microserviceSystem.adopt(microservice);
                        microserviceSystem.getMicroservices().add(microservice);
                        break;

                    case DELETE:
                        microservice = microserviceSystem.findMicroserviceByPath(Path.of(delta.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "")));
                        // Here we must orphan all the classes of this microservice
                        microserviceSystem.getMicroservices().remove(microservice);
                        microserviceSystem.orphanize(microservice);
                        break;

                }
            }

        }
    }

    /**
     * Filters the delta files that deal with building project
     * so either pom.xml or build.gradle
     *
     * @return a list of system changes that deal with build files that aren't modifications
     */
    private List<AbstractDelta> filterBuildDeltas() {
        // deltaChanges.stream().filter(delta -> (delta.getOldPath() == null || delta.getOldPath().isEmpty() ? delta.getNewPath() : delta.getOldPath()).endsWith("/pom.xml")).collect(Collectors.toUnmodifiableList());
        List<AbstractDelta> filteredDeltas = new ArrayList<>(systemChange.getChanges());

        // Remove non build related files
        filteredDeltas.removeIf(delta -> !(delta.getPath().endsWith("pom.xml")) && !(delta.getPath().endsWith("build.gradle")));

        // Remove modified files, doesn't change microservice structure
        filteredDeltas.removeIf(delta -> delta.getChangeType().equals(ChangeType.MODIFY));

        // Remove deleted files, if their microservice doesn't exist (they were less specific and were filtered out)
        filteredDeltas.removeIf(delta -> (delta.getChangeType().equals(ChangeType.DELETE) && microserviceSystem.findMicroserviceByPath(delta.getPath()) == null));

        // Remove more specific build deltas in the same system change
        List<AbstractDelta> filteredDeltasCopy = new ArrayList<>(List.copyOf(filteredDeltas));

        // If a delta is more specific than another in same SystemChange,
        // we need to remove the more general option in case of add
        List<AbstractDelta> addDeltas = filteredDeltas.stream().filter(d -> d.getChangeType().equals(ChangeType.ADD)).toList();
        boolean deletedFirst = false;
        for(AbstractDelta delta1 : addDeltas) {
            for(AbstractDelta delta2 : addDeltas) {
                // If they are equal or they aren't both additions
                if(delta1.equals(delta2) || !delta1.getChangeType().equals(ChangeType.ADD) || !delta2.getChangeType().equals(ChangeType.ADD)) {
                    continue;
                }
                String delta1Path = delta1.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "");
                String delta2Path = delta2.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "");
                if(delta1Path.equals(delta2Path) && !deletedFirst) {
                    filteredDeltas.remove(delta1);
                    deletedFirst = true;
                    continue;
                }

                // Check if paths are more/less specific
                if(delta1Path.matches(delta2Path + File.separator + ".*")) {
                    filteredDeltasCopy.remove(delta2);
                } else if(delta2Path.matches(delta1Path + File.separator + ".*")) {
                    filteredDeltasCopy.remove(delta1);
                }
            }
        }

        deletedFirst = false;
        // Remove duplicate deletes (pom.xml and build.gradle) of the same microservice
        List<AbstractDelta> deleteDeltas = filteredDeltas.stream().filter(d -> d.getChangeType().equals(ChangeType.DELETE)).collect(Collectors.toList());
        for(AbstractDelta delta1 : deleteDeltas) {
            for(AbstractDelta delta2 : deleteDeltas) {
                String delta1Path = delta1.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "");
                String delta2Path = delta2.getPath().normalize().toString().replace(File.separator + "pom.xml", "").replace(File.separator + "build.gradle", "");

                // If they are equal and they aren't both additions, arbitrarily remove one of them
                if(delta1Path.equals(delta2Path) && !delta1.getPath().equals(delta2.getPath()) && !deletedFirst) {
                    filteredDeltasCopy.remove(delta1);
                    deletedFirst = true;
                }
            }
        }

        return filteredDeltasCopy;
    }

    private void unflattenAndAddClass(ModifyDelta delta) {
        // Prepare to unflatten the class
        AbstractClass aClass = (AbstractClass) delta.getFileDelta().getProjectFile();

        // Get descendants of the class
        List<Component> components = delta.getComponentDeltas().stream().filter(cd -> cd.getChangeType() != ChangeType.DELETE)
                .map(ComponentDelta::getComponent).toList();

        // Add descendants to the class
        recursiveAddDescendants(components, aClass);

        microserviceSystem.getOrphans().add(aClass);
    }

    private void recursiveAddDescendants(List<Component> componentList, Node root) {
        // Get ID
        String rootID = root.getID();
        if (root instanceof AbstractClass) {
            rootID = ((AbstractClass) root).getPackageName() + "." + root.getName();
        }

        // Look for all children that have the parent ID starting their ID
        String finalRootID = rootID;
        List<Component> children = componentList.stream().filter(c -> c.getID().startsWith(finalRootID)
                && StringUtils.countOccurrences(c.getID(), "&") == StringUtils.countOccurrences(finalRootID, "&") + 1).toList();

        // Return if there are no children
        if (children.isEmpty())
            return;

        // Add subcomponents based on object type
        if (root instanceof AbstractClass) {
            ((AbstractClass) root).getImports().addAll(children.stream().filter(c -> c instanceof Import).map(c -> (Import) c).toList());
            ((AbstractClass) root).getAnnotations().addAll(children.stream().filter(c -> c instanceof Annotation).map(c -> (Annotation) c).toList());
            ((AbstractClass) root).getFields().addAll(children.stream().filter(c -> c instanceof Field).map(c -> (Field) c).toList());
            ((AbstractClass) root).getMethods().addAll(children.stream().filter(c -> c instanceof Method).map(c -> (Method) c).toList());

            componentList.removeAll((List<Component>) root.getChildren());
            for (Node c : root.getChildren())
                recursiveAddDescendants(componentList, c);

        } else if (root instanceof Method) {
            ((Method) root).getMethodCalls().addAll(children.stream().filter(c -> c instanceof MethodCall).map(c -> (MethodCall) c).toList());
            ((Method) root).getAnnotations().addAll(children.stream().filter(c -> c instanceof Annotation).map(c -> (Annotation) c).toList());
            ((Method) root).getParameters().addAll(children.stream().filter(c -> c instanceof Parameter).map(c -> (Parameter) c).toList());

            componentList.removeAll((List<Component>) root.getChildren());
            for (Node c : root.getChildren())
                recursiveAddDescendants(componentList, c);
        } else if (root instanceof Parameter) {
            ((Parameter) root).getAnnotations().addAll(children.stream().filter(c -> c instanceof Annotation).map(c -> (Annotation) c).toList());
        }
    }

    private MicroserviceSystem getMicroserviceSystem() {
        return this.microserviceSystem;
    }

    /**
     * Creates a new IR at commit B from an existing IR at commit A and a Delta from commit A to commit B.
     * @param intermediateSystem The MicroserviceSystem at commit A
     * @param delta The SystemChange from commit A to commit B
     * @return A {@link MicroserviceSystem} at commit B
     */
    public static MicroserviceSystem create(MicroserviceSystem intermediateSystem, SystemChange delta) throws IOException {
        MergeService mergeService = new MergeService(intermediateSystem, delta);
        mergeService.generateMergeIR();
        return mergeService.getMicroserviceSystem();
    }

    /**
     * Creates a new IR at commit B from an existing IR at commit A and a Delta from commit A to commit B.
     * @param intermediatePath A path to the intermediate representation
     * @param deltaPath A path to the delta
     * @return A {@link MicroserviceSystem} at commit B
     */
    public static MicroserviceSystem create(Path intermediatePath, Path deltaPath) throws IOException {
        MicroserviceSystem microserviceSystem = IRExtractionService.read(intermediatePath);
        SystemChange systemChange = JsonReadWriteUtils.readFromJSON(deltaPath, SystemChange.class);

        return create(microserviceSystem, systemChange);
    }

    /**
     * Creates a new IR at commit B from an existing IR at commit A and a Delta from commit A to commit B.
     * Additionally, it writes the resulting {@link MicroserviceSystem} to the specified path.
     * @param intermediatePath A path to the intermediate representation
     * @param deltaPath A path to the delta
     * @return A {@link MicroserviceSystem} at commit B
     */
    public static MicroserviceSystem createAndWrite(Path intermediatePath, Path deltaPath, Path outputPath) throws IOException {
        MicroserviceSystem microserviceSystem = create(intermediatePath, deltaPath);
        JsonReadWriteUtils.writeToJSON(outputPath, microserviceSystem);
        return microserviceSystem;
    }

    /**
     * Creates a new IR at commit B from an existing IR at commit A and a Delta from commit A to commit B.
     * Additionally, it writes the resulting {@link MicroserviceSystem} to the specified path.
     * @param intermediateSystem The MicroserviceSystem at commit A
     * @param delta The SystemChange from commit A to commit B
     * @return A {@link MicroserviceSystem} at commit B
     */
    public static MicroserviceSystem createAndWrite(MicroserviceSystem intermediateSystem, SystemChange delta, Path outputPath) throws IOException {
        MicroserviceSystem microserviceSystem = create(intermediateSystem, delta);
        JsonReadWriteUtils.writeToJSON(outputPath, microserviceSystem);
        return microserviceSystem;
    }
}