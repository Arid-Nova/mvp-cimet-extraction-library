package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import edu.university.ecs.lab.common.models.metadata.NodeMetadata;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.HashSet;
import java.util.Set;

/**
 * Class that provides a generic way of storing any type of object/node in a microservice system.
 */
@Data
@NoArgsConstructor
public abstract class Node {
    /**
     * The parent of this Node.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @JsonIgnore
    protected Node parent = null;

    /**
     * Various optional metadata that can be attached to Nodes.
     */
    @JsonDeserialize(as = HashSet.class)
    protected Set<NodeMetadata> metadata;

    /**
     * This method generates a unique ID for datatypes in a microservice system.
     *
     * @return the string unique ID
     */
    @JsonIgnore
    public abstract String getID();
}
