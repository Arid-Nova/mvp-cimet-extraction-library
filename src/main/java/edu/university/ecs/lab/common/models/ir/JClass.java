package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a class in Java. It holds all information regarding that class including all method
 * declarations, method calls, fields, etc.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = JEnum.class, name = "JEnum"),
               @JsonSubTypes.Type(value = JRecord.class, name = "JRecord"),
               @JsonSubTypes.Type(value = JInterface.class, name = "JInterface")})
public class JClass extends ProjectFile {
    /**
     * The name of the package containing this class
     */
    private String packageName;

    /**
     * The protection level assigned to the class
     */
    private AccessModifier protection;

    /**
     * Whether the class is final
     */
    private boolean isFinal;

    /**
     * A list of imports that the class includes
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<Import> imports;

    /**
     * Class implementations
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<String> implementedTypes;

    /**
     * Class extensions
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<String> extendedTypes;

    /**
     * Role of the class in the microservice system. See {@link ClassRole}
     */
    private ClassRole classRole;

    /**
     * Set of methods in the class
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<Method> methods;

    /**
     * Set of class fields
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<Field> fields;

    /**
     * Set of class level annotations
     */
    @JsonDeserialize(as = HashSet.class)
    private Set<Annotation> annotations;

    /**
     * List of method invocations made from within this class
     */
    @JsonDeserialize(as = ArrayList.class)
    private List<MethodCall> methodCalls;

    /**
     * Whether the class is static (nested classes only)
     */
    private boolean isStatic;

    /**
     * Whether the class is abstract
     */
    private boolean isAbstract;

    public JClass(String name, String path, String packageName, ClassRole classRole) {
        this.name = name;
        this.packageName = packageName;
        this.path = path;
        this.classRole = classRole;
        this.imports = new HashSet<>();
        this.methods = new HashSet<>();
        this.fields = new HashSet<>();
        this.annotations = new HashSet<>();
        this.methodCalls = new ArrayList<>();
        this.implementedTypes = new HashSet<>();
        this.fileType = FileType.JCLASS;
        this.protection = AccessModifier.PACKAGE_PRIVATE;
        this.isFinal = false;
    }

    public JClass(String name, String path, String packageName, ClassRole classRole, Set<Import> imports, Set<Method> methods,
                  Set<Field> fields, Set<Annotation> classAnnotations, List<MethodCall> methodCalls, Set<String> implementedTypes,
                  Set<String> extendedTypes, AccessModifier protection, Boolean isFinal, Boolean isAbstract, Boolean isStatic) {
        this.name = name;
        this.packageName = packageName;
        this.path = path;
        this.classRole = classRole;
        this.imports = imports;
        this.methods = methods;
        this.fields = fields;
        this.annotations = classAnnotations;
        this.methodCalls = methodCalls;
        this.implementedTypes = implementedTypes;
        this.extendedTypes = extendedTypes;
        this.fileType = FileType.JCLASS;
        this.protection = protection;
        this.isFinal = isFinal;
        this.isAbstract = isAbstract;
        this.isStatic = isStatic;

        // Fill back references
        this.imports.forEach(imp -> imp.setParent(this));
        this.methods.forEach(met -> met.setParent(this));
        this.fields.forEach(fie -> fie.setParent(this));
        this.annotations.forEach(ann -> ann.setParent(this));
        this.methodCalls.forEach(mec -> mec.setParent(this));
    }

    /**
     * This method returns all endpoints found in the methods of this class,
     * grouped under the same list as an Endpoint is an extension of a Method
     * see {@link Endpoint}
     * @return set of all endpoints
     */
    @JsonIgnore
    public Set<Endpoint> getEndpoints() {
        if((!getClassRole().equals(ClassRole.CONTROLLER) && !getClassRole().equals(ClassRole.REP_REST_RSC)) || getMethods().isEmpty()) {
            return new HashSet<>();
        }
        return methods.stream().filter(method -> method instanceof Endpoint).map(method -> (Endpoint) method).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * This method returns all restCalls found in the methodCalls of this class,
     * grouped under the same list as an RestCall is an extension of a MethodCall
     * see {@link RestCall}
     * @return set of all restCalls
     */
    @JsonIgnore
    public List<RestCall> getRestCalls() {

        return methodCalls.stream().filter(methodCall -> methodCall instanceof RestCall).map(methodCall -> (RestCall) methodCall).collect(Collectors.toUnmodifiableList());
    }

    @JsonIgnore
    public List<Component> getComponents() {
        List<Component> components = new ArrayList<>();
        components.addAll(getMethodCalls());
        components.addAll(getFields());
        components.addAll(getAnnotations());
        components.addAll(getMethods());
        components.addAll(getMethodCalls());

        return components;
    }

    /**
     * If we are adding a class or a class is being adopted/orphanized lets update ms name
     *
     * @param name
     */
    public void updateMicroserviceName(String name) {
        methodCalls.forEach(methodCall -> methodCall.setMicroserviceName(name));
        methods.forEach(methodCall -> methodCall.setMicroserviceName(name));
    }

    /**
     * See {@link Node#getID()}
     */
    @Override
    @JsonIgnore
    public String getID() {
        return this.path;
    }
}
