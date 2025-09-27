package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.ir.ProjectFile;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

import jakarta.annotation.Nullable;
import java.nio.file.Path;

@Getter
@Setter
@NoArgsConstructor
@JsonTypeName("SimpleDelta")
public class SimpleDelta extends AbstractDelta {
    protected ProjectFile projectFile;

    public SimpleDelta(Path path, ChangeType changeType, String repositoryURL, ProjectFile projectFile) {
        super(path, changeType, repositoryURL);

        this.projectFile = projectFile;
    }
}