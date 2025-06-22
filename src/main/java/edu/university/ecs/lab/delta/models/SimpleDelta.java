package edu.university.ecs.lab.delta.models;

import edu.university.ecs.lab.common.models.ir.ProjectFile;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import javax.annotation.Nullable;
import java.nio.file.Path;

@Getter
@Setter
public class SimpleDelta extends AbstractDelta {

    @NonNull
    protected final ProjectFile projectFile;

    public SimpleDelta(Path path, ChangeType changeType, @NonNull ProjectFile projectFile) {
        super(path, changeType);

        this.projectFile = projectFile;
    }
}