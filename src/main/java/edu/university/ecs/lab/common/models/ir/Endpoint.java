package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.enums.HttpMethod;
import lombok.*;

/**
 * Represents an extension of a method declaration. An endpoint exists at the controller level and
 * signifies an open mapping that can be the target of a rest call.
 */
@NoArgsConstructor
@Getter
@Setter
@JsonTypeName("Endpoint")
@EqualsAndHashCode(callSuper = true)
public class Endpoint extends Method {

    /**
     * The URL of the endpoint e.g. /api/v1/users/login, May have parameters like {param}
     * which are converted to {?}
     */
    protected String url;

    /**
     * The HTTP method of the endpoint, e.g. GET, POST, etc.
     */
    protected HttpMethod httpMethod;

    public Endpoint(Method method, String url, HttpMethod httpMethod) {
        super(method);

        this.url = url;
        this.httpMethod = httpMethod;
    }

}