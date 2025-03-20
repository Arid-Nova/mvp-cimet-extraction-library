package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents an extension of a method declaration. An endpoint exists at the controller level and
 * signifies an open mapping that can be the target of a rest call.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonTypeName("Endpoint")
public class Endpoint extends Method {

    /**
     * The URL of the endpoint e.g. /api/v1/users/login, May have parameters like {param}
     * which are converted to {?}
     */
    private String url;

    /**
     * The HTTP method of the endpoint, e.g. GET, POST, etc.
     */
    private HttpMethod httpMethod;

    public Endpoint(Method method, String url, HttpMethod httpMethod) {
        super(method.name, method.packageName + "." + method.className, method.parameters, method.returnType, method.annotations, method.microserviceName, method.className, method.protection, method.isAbstract(), method.isStatic(), method.isFinal(), method.getThrownExceptions(), method.getLocation());
        this.url = url;
        this.httpMethod = httpMethod;
    }
}