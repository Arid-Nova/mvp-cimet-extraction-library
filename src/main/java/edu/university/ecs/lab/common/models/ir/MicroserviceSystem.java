package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents the intermediate structure of a microservice system.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonTypeName("MicroserviceSystem")
public class MicroserviceSystem extends Node {
    /**
     * The name of the system
     */
    private String name;

    /**
     * The commit ID of the system
     */
    private String commitID;

    /**
     * Set of microservices in the system
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<Microservice> microservices;

    /**
     * Set of present files (class or configurations) who have no microservice
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<ProjectFile> orphans;

    public MicroserviceSystem(String name, String commitID, Set<Microservice> microservices, Set<ProjectFile> orphans) {
        this.name = name;
        this.commitID = commitID;
        this.microservices = microservices;
        this.orphans = orphans;

        // Fill back references
        this.microservices.forEach(mis -> mis.setParent(this));
        this.orphans.forEach(orp -> orp.setParent(this));
    }

    /**
     * Returns the microservice whose path is the start of the passed path
     *
     * @param path the path to search for
     * @return microservice instance of matching path or null
     */
    public Microservice findMicroserviceByPath(String path) {
        return getMicroservices().stream().filter(microservice -> path.startsWith(microservice.getPath())).findFirst().orElse(null);
    }

    /**
     * Given an existing microservice, if it must now be orphanized
     * then all JClasses belonging to that service will be added to
     * the system's pool of orphans for later use
     *
     * @param microservice the microservice to orphanize
     */
    public void orphanize(Microservice microservice) {
        Set<JClass> classes = microservice.getClasses();
        classes.forEach(c -> c.updateMicroserviceName(""));
        classes.forEach(c -> c.setParent(this));
        orphans.addAll(classes);
    }

    /**
     * Given a new or modified microservice, we must adopt awaiting
     * orphans based on their file paths containing the microservices
     * (folder) path
     *
     * @param microservice the microservice adopting orphans
     */
    public void adopt(Microservice microservice) {
        Set<ProjectFile> updatedOrphans = new HashSet<>(getOrphans());

        for (ProjectFile file : getOrphans()) {
            // If the microservice is in the same folder as the path to the microservice
            if (file.getPath().contains(microservice.getPath())) {
                if(file.getFileType().equals(FileType.JCLASS)) {
                    JClass jClass = (JClass) file;
                    jClass.updateMicroserviceName(microservice.getName());
                    jClass.setParent(microservice);
                    microservice.addJClass(jClass);
                    updatedOrphans.remove(file);
                } else {
                    microservice.getFiles().add((ConfigFile) file);
                    file.setParent(microservice);
                }
            }

        }
        setOrphans(updatedOrphans);
    }

    /**
     * Get the class of a given endpoint
     * 
     * @param path endpoint 
     * @return class that endpoint is in
     */
    @JsonIgnore
    public JClass findClass(String path){
        JClass returnClass = null;
        returnClass = getMicroservices().stream().flatMap(m -> m.getClasses().stream()).filter(c -> c.getPath().equals(path)).findFirst().orElse(null);
        if(returnClass == null){
            returnClass = getOrphans().stream().filter(c -> c instanceof JClass).filter(c -> c.getPath().equals(path)).map(c -> (JClass) c).findFirst().orElse(null);
        }

        return returnClass;
    }

    /**
     * Get the file of a given endpoint
     * 
     * @param path endpoint
     * @return file that endpoint is in
     */
    @JsonIgnore
    public ProjectFile findFile(String path){
        ProjectFile returnFile = null;
        returnFile = getMicroservices().stream().flatMap(m -> m.getAllFiles().stream()).filter(c -> c.getPath().equals(path)).findFirst().orElse(null);
        if(returnFile == null){
            returnFile = getOrphans().stream().filter(c -> c.getPath().equals(path)).findFirst().orElse(null);
        }

        return returnFile;
    }

    /**
     * This method returns the name of the microservice associated with
     * a file that exists in the system. Note this method will not work
     * if the file is not present somewhere in the system
     *
     * @param path the ProjectFile path
     * @return string name of microservice or "" if it does not exist
     */
    @JsonIgnore
    public String getMicroserviceFromFile(String path){
        for(Microservice microservice : getMicroservices()) {
            for(ProjectFile file : microservice.getFiles()) {
                if(file.getPath().equals(path)) {
                    return microservice.getName();
                }
            }
        }

        return "";
    }

    public void orphanizeAndAdopt(Microservice microservice) {
        orphanize(microservice);
        for(Microservice m : getMicroservices()){
            adopt(m);
        }
    }

    /**
     * Microservice systems don't have parents, so none can be set
     * @param parent Ignored parameter
     */
    @Override
    public void setParent(Node parent) {}

    /**
     * See {@link Node#getID()}
     */
    @Override
    @JsonIgnore
    public String getID() {
        return this.name + " " + this.commitID;
    }
}
