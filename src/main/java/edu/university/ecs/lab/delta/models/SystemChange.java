package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents the overall change in the IR from oldCommit
 * to newCommit as a list of Deltas see {@link AbstractDelta}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonTypeName("SystemChange")
public class SystemChange {

    /**
     * The old commitID
     */
    private String oldCommit;

    /**
     * The new commitID
     */
    private String newCommit;

    /**
     * List of delta changes
     */
    private List<AbstractDelta> changes = new ArrayList<>();
}