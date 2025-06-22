package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ReferenceType;
import edu.university.ecs.lab.common.models.enums.AccessModifier;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import lombok.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Represents a method declaration in Java.
 */
@NoArgsConstructor
@Getter
@Setter
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = Endpoint.class, name = "Endpoint")})
@JsonTypeName("Method")
@EqualsAndHashCode(callSuper = true)
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
     * Set of method calls made by this method
     */
    @NonNull
    @JsonSerialize(as = ArrayList.class)
    protected List<MethodCall> methodCalls;

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
     * Whether the function is abstract
     */
    private Boolean isAbstract;

    /**
     * Whether the function is static
     */
    private Boolean isStatic;

    /**
     * Whether the function is final
     */
    private Boolean isFinal;

    /**
     * A list of exceptions that the function can throw when called
     */
    private Set<String> thrownExceptions;

    public Method(Node parent, String name, Location location) {
        super(parent, name, location);

        this.annotations = new HashSet<>();
        this.parameters = new HashSet<>();
        this.methodCalls = new ArrayList<>();
        this.protection = AccessModifier.PUBLIC;
        this.isAbstract = Boolean.FALSE;
        this.isStatic = Boolean.FALSE;
        this.isFinal = Boolean.FALSE;
        this.thrownExceptions = new HashSet<>();
        this.returnType = "";
    }

    public Method(Method method) {
        this(method.getParent().orElse(null), method.getName(), method.getLocation());

        this.parameters = method.getParameters();
        this.protection = method.getProtection();
        this.isAbstract = method.getIsAbstract();
        this.isStatic = method.getIsStatic();
        this.isFinal = method.getIsFinal();
        this.thrownExceptions = method.getThrownExceptions();
    }

    public Method(Node parent, MethodDeclaration methodDeclaration) {
        super(parent, methodDeclaration.getNameAsString(), new Location(methodDeclaration.getRange().get()));

        this.annotations = SourceToObjectUtils.parseAnnotations(this, methodDeclaration.getAnnotations());
        this.parameters = parseParameters(methodDeclaration.getParameters());
        this.methodCalls = SourceToObjectUtils.parseMethodCalls(this, methodDeclaration.getChildNodes().stream().filter(m -> m instanceof MethodCallExpr).map(m -> (MethodCallExpr) m).collect(Collectors.toList()));
        this.protection = AccessModifier.fromAccessSpecifier(methodDeclaration.getAccessSpecifier());
        this.isAbstract = methodDeclaration.isAbstract();
        this.isStatic = methodDeclaration.isStatic();
        this.isFinal = methodDeclaration.isFinal();
        this.thrownExceptions = methodDeclaration.getThrownExceptions().stream().map(ReferenceType::toString).collect(Collectors.toSet());
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
            parameterSet.add(new Parameter(this, parameter));
        }

        return parameterSet;
    }

    @Override
    public List<Component> getChildren() {
        List<Component> children = new ArrayList<>();
        children.addAll(getParameters());
        children.addAll(getAnnotations());
        children.addAll(getMethodCalls());

        return children;
    }
}
