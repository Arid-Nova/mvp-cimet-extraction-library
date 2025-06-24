package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.enums.ClassType;
import lombok.*;

import jakarta.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This class represents any file in a project's directory
 */
@NoArgsConstructor
@Getter
@Setter
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = JClass.class, name = "JClass"),
        @JsonSubTypes.Type(value = JEnum.class, name = "JEnum"),
        @JsonSubTypes.Type(value = JRecord.class, name = "JRecord"),
        @JsonSubTypes.Type(value = JInterface.class, name = "JInterface")})

@JsonTypeName("ProjectFile")
public abstract class AbstractClass extends ProjectFile {
    /**
     * Role of the class in the microservice system. See {@link ClassRole}
     */
    protected ClassRole classRole;

    /**
     *  The type of the Java Class
     */
    @NonNull
    protected ClassType classType;

    /**
     * The name of the package containing this Java File
     */
    @Nullable
    protected String packageName;

    /**
     * The protection level assigned to the Java File
     */
    @NonNull
    protected AccessModifier protection;

    /**
     * Whether the Java File is final
     */
    @NonNull
    protected Boolean isFinal;

    /**
     * Whether the Java File is static
     */
    @NonNull
    protected Boolean isStatic;

    /**
     * Whether the Java File is abstract
     */
    protected Boolean isAbstract;

    /**
     * A list of imports that the class includes
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    protected Set<Import> imports;

    /**
     * Set of class level annotations
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    protected Set<Annotation> annotations;

    /**
     * Set of class fields
     */
    @JsonDeserialize(as = HashSet.class)
    @NonNull
    protected Set<Field> fields;

    /**
     * Set of methods in the class
     */
    @JsonDeserialize(as = HashSet.class)
    protected Set<Method> methods;

    public AbstractClass(Node parent, Path path, @NonNull ClassRole classRole, @NonNull ClassType classType, String packageName, @NonNull AccessModifier protection, @NonNull Boolean isFinal, @NonNull Boolean isStatic, @NonNull Boolean isAbstract) {
        super(parent, path);

        this.classRole = classRole;
        this.classType = classType;
        this.packageName = packageName;
        this.protection = protection;
        this.isFinal = isFinal;
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;

        this.imports = new HashSet<>();
        this.annotations = new HashSet<>();
        this.fields = new HashSet<>();
        this.methods = new HashSet<>();
    }

    public AbstractClass(Node parent, Path path, @NonNull ClassRole classRole, @NonNull ClassType classType, String packageName, @NonNull AccessModifier protection, @NonNull Boolean isFinal, @NonNull Boolean isStatic, @NonNull Boolean isAbstract, @NonNull Set<Import> imports, @NonNull Set<Annotation> annotations, @NonNull Set<Field> fields, @NonNull Set<Method> methods) {
        super(parent, path);

        this.classRole = classRole;
        this.classType = classType;
        this.packageName = packageName;
        this.protection = protection;
        this.isFinal = isFinal;
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;

        this.imports = new HashSet<>(imports);
        this.annotations = new HashSet<>(annotations);
        this.fields = new HashSet<>(fields);
        this.methods = new HashSet<>(methods);

        // Fill back references
        this.imports.forEach(imp -> imp.setParent(Optional.of(this)));
        this.methods.forEach(met -> met.setParent(Optional.of(this)));
        this.fields.forEach(fie -> fie.setParent(Optional.of(this)));
        this.annotations.forEach(ann -> ann.setParent(Optional.of(this)));
    }

    @Override
    public String getID() {
        return getPath().toString().replace('\\', '/');
    }

    @Override
    public List<Component> getChildren() {
        List<Component> children = new ArrayList<>();
        children.addAll(getFields());
        children.addAll(getAnnotations());
        children.addAll(getMethods());
        children.addAll(getImports());

        return children;
    }

    @Override
    public final List<Component> getDescendants() {
        List<Component> allDescendants = new ArrayList<>();
        List<Component> thisChildren = getChildren();

        if (thisChildren != null && !thisChildren.isEmpty()) {
            allDescendants.addAll(thisChildren);

            for (Component child : thisChildren) {
                if (child != null) {
                    allDescendants.addAll(child.getDescendants());
                }
            }
        }

        // Return the populated list (or an empty list if no children were found)
        return allDescendants;
    }

    @JsonIgnore
    public List<MethodCall> getMethodCalls() {
        return getDescendants().stream().filter(obj -> obj instanceof MethodCall).map(obj -> (MethodCall) obj).toList();
    }

    /**
     * This method returns all restCalls found in the methodCalls of this class,
     * grouped under the same list as an RestCall is an extension of a MethodCall
     * see {@link RestCall}
     * @return set of all restCalls
     */
    @JsonIgnore
    public List<RestCall> getRestCalls() {
        return getMethodCalls().stream().filter(methodCall -> methodCall instanceof RestCall).map(methodCall -> (RestCall) methodCall).collect(Collectors.toUnmodifiableList());
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

    @Override
    public void clearDescendants() {
        setFields(new HashSet<>());
        setAnnotations(new HashSet<>());
        setMethods(new HashSet<>());
        setImports(new HashSet<>());
    }
}
