package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Objects;

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
public abstract class Component extends Node {
    /**
     * Name of the structure
     */
    protected String name;

    /**
     * Name of the package + class (package path e.g. edu.university.lab.AdminController)
     */
    protected String packageName;

    /**
     * Name of the package + class (package path e.g. edu.university.lab.AdminController)
     */
    protected String className;

    /**
     * The line range of the component
     */
    protected Location location;

    /**
     * See {@link Node#getID()}
     */
    @Override
    @JsonIgnore
    public final String getID() {
        String id = String.join(".", packageName, className, name);

        // TODO: Perhaps a better implementation strategy instead of checking type here
        if(this instanceof MethodCall) {
            id += "[" + location.startLine + "-" + location.endLine + "]";
        }

        return id;
    }

    /**
     * Equals implementation for components, if the ID is equivalent they should
     * be treated as the same component
     *
     * @param o object to compare to
     * @return boolean equality
     */
    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;
        Component component = (Component) o;
        return Objects.equals(getID(), component.getID()) ;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, packageName, className, location);
    }

    /**
     * Converts a packageAndClassName to separate package and class names
     * @param packageAndClassName The package and class name to set for this Component
     */
    public void setPackageAndClassNames(String packageAndClassName) {
        int lastDot = packageAndClassName.lastIndexOf(".");
        if (lastDot == -1) {
            packageName = packageAndClassName;
            className = "";
        }
        else {
            packageName = packageAndClassName.substring(0, lastDot);
            className = packageAndClassName.substring(lastDot + 1);
        }
    }
}
