package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Represents a project configuration file
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@JsonTypeName("ConfigFile")
public class ConfigFile extends ProjectFile {
    private JsonNode data;

    public ConfigFile(String path, String name, JsonNode data) {
        this.path = path;
        this.name = name;
        this.data = data;
        this.fileType = FileType.CONFIG;
    }

    /**
     * See {@link Node#getID()}
     */
    @Override
    @JsonIgnore
    public String getID() {
        return this.path;
    }
}