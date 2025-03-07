package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Class that provides a generic way of storing any type of object/node in a microservice system.
 */
@Data
@NoArgsConstructor
public class SystemNode {
    /**
     * The parent of this SystemNode
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @JsonIgnore
    protected SystemNode parent = null;
}
