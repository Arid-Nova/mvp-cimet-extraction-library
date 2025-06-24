package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import edu.university.ecs.lab.common.models.enums.FileType;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import lombok.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * This class represents any file in a project's directory
 */
@EqualsAndHashCode(callSuper = true)
@Getter
@Setter
@NoArgsConstructor
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = ConfigFile.class, name = "ConfigFile"),
        @JsonSubTypes.Type(value = JClass.class, name = "JClass"),
})
@JsonTypeName("ProjectFile")
public abstract class ProjectFile extends Node {
    /**
     * The path to the file in the project
     */
    @NonNull
    @JsonSerialize(using = JsonReadWriteUtils.PathSerializer.class)
    protected Path path;

    public ProjectFile(Node parent, @NonNull Path path) {
        super(parent, pathToName(path));

        this.path = path;
    }

    private static String pathToName(Path path) {
        String name = path.getFileName().toString();
        if(name.endsWith(".java")) {
            return name.substring(0, name.length() - 5);
        }

        return name;
    }
}
