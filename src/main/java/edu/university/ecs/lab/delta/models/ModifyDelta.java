package edu.university.ecs.lab.delta.models;

import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.nio.file.Path;
import java.util.List;

@Getter
@Setter
public class ModifyDelta extends AbstractDelta {
    @NonNull
    protected List<ComponentDelta> componentDeltas;

    public ModifyDelta(Path path, @NonNull List<ComponentDelta> componentDeltas) {
        super(path, ChangeType.MODIFY);

        this.componentDeltas = componentDeltas;
    }
}