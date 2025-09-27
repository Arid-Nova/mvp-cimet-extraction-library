package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * The old commitIDs
     */
    private Map<String, String> oldCommits = new HashMap<>();

    /**
     * The new commitIDs
     */
    private Map<String, String> newCommits = new HashMap<>();

    /**
     * List of delta changes
     */
    private List<AbstractDelta> changes = new ArrayList<>();
}