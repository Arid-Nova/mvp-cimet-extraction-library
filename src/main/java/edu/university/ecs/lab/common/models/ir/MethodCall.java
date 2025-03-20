package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a method call in Java.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = RestCall.class, name = "RestCall")})
@JsonTypeName("MethodCall")
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
    protected String microserviceName;

    public MethodCall(String name, String packageName, String objectType, String objectName, String calledFrom, String parameterContents, String microserviceName,
                      String className, Location location) {
        this.name = name;
        this.objectName = objectName;
        this.objectType = objectType;
        this.calledFrom = calledFrom;
        this.parameterContents = parameterContents;
        this.microserviceName = microserviceName;
        this.packageName = packageName;
        this.className = className;
        this.location = location;
    }

    /**
     * Checks if a method call matches a given method
     * 
     * @param methodCall method call object to match
     * @param method method object to match
     * @return true if method call and method match, false otherwise
     */
    public static boolean matchMethod(MethodCall methodCall, Method method) {
        return methodCall.microserviceName.equals(method.microserviceName) && methodCall.objectType.equals(method.className)
                && methodCall.name.equals(method.name);

    }

}
