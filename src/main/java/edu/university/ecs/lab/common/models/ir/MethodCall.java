package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents a method call in Java.
 */
@NoArgsConstructor
@Getter
@Setter
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = RestCall.class, name = "RestCall")})
@JsonTypeName("MethodCall")
@EqualsAndHashCode(callSuper = true)
public class MethodCall extends Component {

    /**
     * Name of object that defines the called method (Maybe a static class instance, just whatever is before
     * the ".")
     */
    protected String objectName;

    /**
     * Type of object that defines that method
     */
    protected String objectType;

    /**
     * Name of method that contains this call
     */
    protected String calledFrom;

    /**
     * Contents within the method call (params) but as a raw string
     */
    protected String parameterContents;

    /**
     * The name of the microservice this MethodCall is called from
     */
    @EqualsAndHashCode.Exclude
    protected String microserviceName;

    public MethodCall(Node parent, String name, Location location) {
        super(parent, name, location);

        this.objectName = "";
        this.objectType = "";
        this.calledFrom = "";
        this.parameterContents = "";
        this.microserviceName = "";

    }

    public MethodCall(MethodCall methodCall) {
        this(methodCall.getParent().orElse(null), methodCall.getName(), methodCall.getLocation());
        this.objectName = methodCall.getObjectName();
        this.objectType = methodCall.getObjectType();
        this.calledFrom = methodCall.getCalledFrom();
        this.parameterContents = methodCall.getParameterContents();
        this.microserviceName = methodCall.getMicroserviceName();
    }

    public MethodCall(Node parent, MethodCallExpr methodCallExpr, String objectType) {
        super(parent, methodCallExpr.getNameAsString(), new Location(methodCallExpr.getRange().get()));

        this.objectName = getCallingObjectName(methodCallExpr);
        this.objectType = objectType;
        this.calledFrom = getParent().isPresent() ? getParent().get().getName() : "";
        this.parameterContents = methodCallExpr.getArguments().stream().map(Objects::toString).collect(Collectors.joining(","));
    }

    private static String getCallingObjectName(MethodCallExpr mce) {
        Expression scope = mce.getScope().orElse(null);

        if (Objects.nonNull(scope) && scope instanceof NameExpr) {
            NameExpr fae = scope.asNameExpr();
            return fae.getNameAsString();
        }

        return "";
    }

    @Override
    public List<Component> getChildren() {
        return new ArrayList<>();
    }
}