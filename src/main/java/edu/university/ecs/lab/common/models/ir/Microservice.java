package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.utils.FileUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents the overarching structure of a microservice system. It is composed of classes which
 * hold all information in that class.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonTypeName("Microservice")
public class Microservice extends Node {
    /**
     * The name of the service (ex: "ts-assurance-service")
     */
    private String name;

    /**
     * The path to the folder that represents the microservice
     */
    private Path path;

    /**
     * Controller classes belonging to the microservice.
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<AbstractClass> controllers;

    /**
     * Service classes to the microservice.
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<AbstractClass> services;

    /**
     * Repository classes belonging to the microservice.
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<AbstractClass> repositories;

    /**
     * Entity classes belonging to the microservice.
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<AbstractClass> entities;

    /**
     * Entity classes belonging to the microservice.
     */
     @JsonDeserialize(as = HashSet.class)
     protected Set<AbstractClass> unknowns;

    /**
     * Embeddable classes belonging to the microservice.
     */
//    private final Set<AbstractClass> embeddables;

    /**
     * Feign client classes belonging to the microservice.
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<AbstractClass> feignClients;

    /**
     * Static files belonging to the microservice.
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<ConfigFile> files;

    public Microservice(Node parent, String name, Path path) {
        super(parent, name);

        this.name = name;
        this.path = path;
        this.controllers = new HashSet<>();
        this.services = new HashSet<>();
        this.repositories = new HashSet<>();
        this.entities = new HashSet<>();
        this.unknowns = new HashSet<>();
//        this.embeddables = new HashSet<>();
        this.feignClients = new HashSet<>();
        this.files = new HashSet<>();
    }

    /**
     * Update's the microservice name of the AbstractClass and adds
     * it to the appropriate Set
     *
     * @param abstractClass the AbstractClass to add
     */
    public void addAbstractClass(AbstractClass abstractClass) {
        abstractClass.setParent(Optional.of(this));

        switch (abstractClass.getClassRole()) {
            case CONTROLLER:
                controllers.add(abstractClass);
                break;
            case SERVICE:
                services.add(abstractClass);
                break;
            case REPOSITORY:
            case REP_REST_RSC:
                repositories.add(abstractClass);
                break;
            case ENTITY:
                entities.add(abstractClass);
                break;
            case FEIGN_CLIENT:
                feignClients.add(abstractClass);
                break;
            case UNKNOWN:
                unknowns.add(abstractClass);
                break;
        }
    }

    /**
     * This method removes an AbstractClass from the microservice
     * by looking up it's path
     *
     * @param path the path to search for removal
     */
    public void removeAbstractClass(String path) {
        Set<AbstractClass> classes = getClasses();
        AbstractClass removeClass = null;

        for (AbstractClass abstractClass : classes) {
            if (abstractClass.getPath().equals(path)) {
                removeClass = abstractClass;
                break;
            }
        }

        // If we cannot find the class no problem, we will skip it quietly
        if (removeClass == null) {
            return;
        }

        removeClass.setParent(null);

        switch (removeClass.getClassRole()) {
            case CONTROLLER:
                controllers.remove(removeClass);
                break;
            case SERVICE:
                services.remove(removeClass);
                break;
            case REPOSITORY:
            case REP_REST_RSC:
                repositories.remove(removeClass);
                break;
            case ENTITY:
                entities.remove(removeClass);
                break;
            case FEIGN_CLIENT:
                feignClients.remove(removeClass);
                break;
            case UNKNOWN:
                unknowns.remove(removeClass);
                break;
        }
    }

    /**
     * This method removes a ProjectFile from the microservice
     * by looking up it's path
     *
     * @param filePath the path to search for
     */
    public void removeProjectFile(String filePath) {
        if(FileUtils.isConfigurationFile(filePath)) {
            // First search configFile because there are less
            ConfigFile removeFile = null;

            for (ConfigFile configFile : getFiles()) {
                if (configFile.getPath().equals(filePath)) {
                    removeFile = configFile;
                    break;
                }
            }

            // If we cannot find the class no problem, we will skip it quietly
            if (removeFile == null) {
                return;
            }

            removeFile.setParent(null);

            getFiles().remove(removeFile);

        } else {
            Set<AbstractClass> classes = getClasses();
            AbstractClass removeClass = null;

            for (AbstractClass abstractClass : classes) {
                if (abstractClass.getPath().equals(filePath)) {
                    removeClass = abstractClass;
                    break;
                }
            }

            // If we cannot find the class no problem, we will skip it quietly
            if (removeClass == null) {
                return;
            }

            removeClass.setParent(null);

            switch (removeClass.getClassRole()) {
                case CONTROLLER:
                    controllers.remove(removeClass);
                    break;
                case SERVICE:
                    services.remove(removeClass);
                    break;
                case REPOSITORY:
                case REP_REST_RSC:
                    repositories.remove(removeClass);
                    break;
                case ENTITY:
                    entities.remove(removeClass);
                    break;
                case FEIGN_CLIENT:
                    feignClients.remove(removeClass);
                    break;
                case UNKNOWN:
                    unknowns.remove(removeClass);
                    break;
            }

        }
    }

    /**
     * This method returns all classes of the microservice in a new set
     *
     * @return the set of all AbstractClasses
     */
    @JsonIgnore
    public Set<AbstractClass> getClasses() {
        Set<AbstractClass> classes = new HashSet<>();
        classes.addAll(getControllers());
        classes.addAll(getServices());
        classes.addAll(getRepositories());
        classes.addAll(getEntities());
        classes.addAll(getFeignClients());
        classes.addAll(getUnknowns());

        return classes;
    }

    /**
     * This method returns all files of a microservice, it is
     * the aggregate of getClasses() and getFiles()
     *
     * @return the set of all classes and files
     */
    @JsonIgnore
    public Set<ProjectFile> getAllFiles() {
        Set<ProjectFile> set = new HashSet<>(getClasses());
        set.addAll(getFiles());
        set.addAll(getClasses());
        return set;
    }

    /**
     * This method returns all rest calls of a microservice
     *
     * @return the list of all rest calls
     */
    @JsonIgnore
    public List<RestCall> getRestCalls() {
        return getClasses().stream()
                .flatMap(abstractClass -> abstractClass.getRestCalls().stream()).collect(Collectors.toList());
    }

    /**
     * This method returns all endpoints of a microservice
     *
     * @return the set of all endpoints
     */
    @JsonIgnore
    public Set<Endpoint> getEndpoints() {
        return getControllers().stream().flatMap(controller ->
                controller.getEndpoints().stream()).collect(Collectors.toSet());
    }

    /**
     * This method returns all method calls of a microservice
     *
     * @return the set of all method calls
     */
    @JsonIgnore
    public Set<MethodCall> getMethodCalls() {
        return getClasses().stream().flatMap(abstractClass -> abstractClass.getMethodCalls().stream()).collect(Collectors.toSet());
    }

    /**
     * This method returns all methods of a microservice
     *
     * @return the set of all methods
     */
    @JsonIgnore
    public Set<Method> getMethods() {
        return getClasses().stream().flatMap(abstractClass -> abstractClass.getMethods().stream()).collect(Collectors.toSet());
    }

    /**
     * See {@link Node#getID()}
     */
    @Override
    @JsonIgnore
    public String getID() {
        return this.path.normalize().toString();
    }

    @Override
    public List<? extends Node> getChildren() {
        return getClasses().stream().toList();
    }

    @Override
    public List<? extends Node> getDescendants() {
        return new ArrayList<>();
    }

    @Override
    public void clearDescendants() {}
}
