package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ReferenceType;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.*;

/**
 * Represents a method declaration in Java.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = Endpoint.class, name = "Endpoint")})
@JsonTypeName("Method")
public class Method extends Component {
    /**
     * The protection level for this Method
     */
    protected AccessModifier protection;

    /**
     * Set of fields representing parameters
     */
    protected Set<Parameter> parameters;

    /**
     * Java return type of the method
     */
    protected String returnType;

    /**
     * The microservice id that this method belongs to
     */
    protected String microserviceName;

    /**
     * Method definition level annotations
     */
    protected Set<Annotation> annotations;

    /**
     * The class id that this method belongs to
     */
    protected String className;

    /**
     * Whether the function is abstract
     */
    private boolean isAbstract;

    /**
     * Whether the function is static
     */
    private boolean isStatic;

    /**
     * Whether the function is final
     */
    private boolean isFinal;

    /**
     * A list of exceptions that the function can throw when called
     */
    private Set<String> thrownExceptions;

    public Method(String name, String packageAndClassName, Set<Parameter> parameters, String typeAsString, Set<Annotation> annotations, String microserviceName,
                  String className, AccessModifier protection, Boolean isAbstract, Boolean isStatic, Boolean isFinal, Set<String> thrownExceptions, Location location) {
        this.name = name;
        setPackageAndClassNames(packageAndClassName);
        this.parameters = parameters;
        this.returnType = typeAsString;
        this.annotations = annotations;
        this.microserviceName = microserviceName;
        this.className = className;
        this.protection = protection;
        this.isAbstract = isAbstract;
        this.isStatic = isStatic;
        this.isFinal = isFinal;
        this.thrownExceptions = thrownExceptions;
        this.location = location;

        // Fill back references
        this.parameters.forEach(pam -> pam.setParent(this));
        this.annotations.forEach(ann -> ann.setParent(this));
    }

    public Method(MethodDeclaration methodDeclaration) {
        this.name = methodDeclaration.getNameAsString();
        this.packageName = methodDeclaration.getClass().getPackageName();
        this.className = methodDeclaration.getClass().getName();
        this.parameters = parseParameters(methodDeclaration.getParameters());
        this.protection = AccessModifier.fromAccessSpecifier(methodDeclaration.getAccessSpecifier());
        this.isAbstract = methodDeclaration.isAbstract();
        this.isStatic = methodDeclaration.isStatic();
        this.isFinal = methodDeclaration.isFinal();
        NodeList<ReferenceType> exceptions = methodDeclaration.getThrownExceptions();
        this.thrownExceptions = new HashSet<>();
        exceptions.forEach(exception -> this.thrownExceptions.add(exception.toString()));

        // Fill back references
        this.parameters.forEach(pam -> pam.setParent(this));
        this.annotations.forEach(ann -> ann.setParent(this));
    }

    /**
     * Get set of parameters from node list
     * 
     * @param parameters Node list of javaparser parameter objects
     * @return set of parameter objects
     */
    private Set<Parameter> parseParameters(NodeList<com.github.javaparser.ast.body.Parameter> parameters) {
        HashSet<Parameter> parameterSet = new HashSet<>();

        for(com.github.javaparser.ast.body.Parameter parameter : parameters) {
            parameterSet.add(new Parameter(parameter, packageName + "." + className));
        }

        return parameterSet;
    }

}
