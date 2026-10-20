package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonInclude;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
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

    /** Complete target structure for parser-neutral, topology-preserving merges. Older deltas omit it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private MicroserviceSystem targetSnapshot;

    /** Binds a snapshot delta to the base it was calculated from. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String baseFingerprint;

    public SystemChange(Map<String, String> oldCommits, Map<String, String> newCommits,
                        List<AbstractDelta> changes) {
        this.oldCommits = oldCommits;
        this.newCommits = newCommits;
        this.changes = changes;
    }
}