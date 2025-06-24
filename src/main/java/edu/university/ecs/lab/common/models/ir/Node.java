package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.*;
import lombok.*;
import org.json.JSONObject;

import java.util.List;
import java.util.Optional;

/**
 * Class that provides a generic way of storing any type of object/node in a microservice system.
 */
@NoArgsConstructor
@Setter
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = Component.class, name = "Component"),
        @JsonSubTypes.Type(value = ProjectFile.class, name = "ProjectFile")})
@JsonTypeName("Node")
@EqualsAndHashCode
public abstract class Node {
    /**
     * The parent of this Node in the tree structure. A null parent indicates the top
     * of the tree of a floating (orphan) node.
     */
    @JsonIgnore
    @Getter
    @EqualsAndHashCode.Exclude
    protected Optional<Node> parent;

    /**
     * The name of the node
     */
    @NonNull
    @Getter
    protected String name;

    /**
     * Various optional metadata that can be attached to Nodes.
     */
    @Getter
    protected JSONObject metadata;

    /**
     * Keep track of the original stored ID for flattened Node structures.
     */
    @JsonIgnore
    @Getter
    private transient String originalDeserializedID;


    public Node(Node parent, @NonNull String name) {
        this.parent = Optional.ofNullable(parent);
        this.name = name;
    }

    /**
     * This method generates a unique ID for the Node to be referenced by.
     * Classes extending Node must implement a method for computing a unique ID.
     *
     * @return the string unique ID
     */
    //@JsonProperty(value = "id", access = JsonProperty.Access.READ_ONLY) // Add this annotation
    public abstract String getID();

    /**
     * This method returns all child Node's of the current Node.
     *
     * @return the list of Node children
     */
    @JsonIgnore
    public abstract List<? extends Node> getChildren();

    /**
     * This method returns all descendants under this Node e.g. it's
     * children's children and so on.
     *
     * @return the list of all Node descendants
     */
    @JsonIgnore
    public abstract List<? extends Node> getDescendants();

    /**
     * This method deletes all the descendants under this Node e.g. it's
     * children's children and so on.
     */
    @JsonIgnore
    public abstract void clearDescendants();


    @JsonAnySetter
    public void handleUnknown(String key, Object value) {
        if ("id".equals(key)) {
            this.originalDeserializedID = value.toString();
        }
    }
}
