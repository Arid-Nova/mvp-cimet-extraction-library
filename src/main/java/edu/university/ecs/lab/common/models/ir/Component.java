package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Abstract class for all code components that fall under a JClass
 * structure.
 */
@Getter
@Setter
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
        @JsonSubTypes.Type(value = Import.class, name = "Import"),
})
@JsonTypeName("Component")
@EqualsAndHashCode(callSuper = true)
public abstract class Component extends Node {
    /**
     * The line range of the component
     */
    @JsonIgnore
    @EqualsAndHashCode.Exclude
    protected Location location;

    public Component(Node parent, String name, Location location) {
        super(parent, name);

        if (!(parent instanceof AbstractClass) && !(parent instanceof Component))
            throw new RuntimeException("Invalid parent provided to Component.");

        this.location = location;
    }

    /**
     * This method generates a unique ID for datatypes that fall
     * under a JClass
     *
     * @return the string unique ID
     */
    @Override
    public String getID() {
        if(getParent() == null) {
            return getOriginalDeserializedID();
        }
        if(getParent().isPresent()) {
            if(getParent().get() instanceof AbstractClass abstractClass) {
                return abstractClass.getPackageName() + "." + abstractClass.getName() + "&" + getName();
            } else {
                return getParent().get().getID() + "&" + getName();
            }
        } else {
            return getName();
        }
    }

    @Override
    public abstract List<Component> getChildren();

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
}
