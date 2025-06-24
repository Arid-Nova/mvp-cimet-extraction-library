package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.ir.Component;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull; // Use NonNull for required fields if applicable
import lombok.Setter;

/**
 * Represents a change to a single Component within a modified ProjectFile.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonTypeName("ComponentDelta")
public class ComponentDelta {

    /**
     * The type of change that occurred to this specific component (ADD, DELETE, MODIFY).
     */
    @NonNull
    protected ChangeType changeType;

    /**
     * The component that was changed
     */
    @NonNull
    protected Component component;

    public ComponentDelta(@NonNull ChangeType changeType, @NonNull Component component) {
        this.changeType = changeType;
        this.component = component;
    }
}