package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.json.JSONObject;

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
    protected JSONObject metadata;

    /**
     * This method generates a unique ID for datatypes in a microservice system.
     *
     * @return the string unique ID
     */
    @JsonIgnore
    public abstract String getID();
}
