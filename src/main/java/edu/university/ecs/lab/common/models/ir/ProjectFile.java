package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * This class represents any file in a project's directory
 */
@EqualsAndHashCode(callSuper = true)
@Data
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
    protected String name;
    protected String path;
    protected FileType fileType;

}
