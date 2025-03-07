package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Abstract class for all code components that fall under a JClass
 * structure.
 */
@Data
@NoArgsConstructor
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = Annotation.class, name = "Annotation"),
        @JsonSubTypes.Type(value = Field.class, name = "Field"),
        @JsonSubTypes.Type(value = Method.class, name = "Method"),
        @JsonSubTypes.Type(value = MethodCall.class, name = "MethodCall"),
        @JsonSubTypes.Type(value = Parameter.class, name = "Parameter"),
})
@JsonTypeName("Component")
public abstract class Component extends SystemNode {
    /**
     * Name of the structure
     */
    protected String name;

    /**
     * Name of the package + class (package path e.g. edu.university.lab.AdminController)
     */
    protected String packageAndClassName;

    /**
     * The line range of the component
     */
    protected Location location;

    /**
     * This method generates a unique ID for datatypes that fall
     * under a JClass
     *
     * @return the string unique ID
     */
    @JsonIgnore
    public final String getID() {
        return packageAndClassName + "." + name;
    }
}
