package edu.university.ecs.lab.delta.models;

import edu.university.ecs.lab.common.models.ir.Component;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.Getter;
import lombok.NonNull; // Use NonNull for required fields if applicable
import lombok.Setter;

import javax.annotation.Nullable;

/**
 * Represents a change to a single Component within a modified ProjectFile.
 */
@Getter
@Setter
public class ComponentDelta {

    /**
     * The type of change that occurred to this specific component (ADD, DELETE, MODIFY).
     */
    @NonNull
    protected final ChangeType changeType;

    /**
     * The component that was changed
     */
    @NonNull
    protected final Component component;

    public ComponentDelta(@NonNull ChangeType changeType, @NonNull Component component) {
        this.changeType = changeType;
        this.component = component;
    }
}