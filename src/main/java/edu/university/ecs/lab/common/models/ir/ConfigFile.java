package edu.university.ecs.lab.common.models.ir;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.JsonNode;
import edu.university.ecs.lab.common.models.enums.FileType;
import lombok.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a project configuration file
 */
@NoArgsConstructor
@Getter
@Setter
@JsonTypeName("ConfigFile")
public class ConfigFile extends ProjectFile {
    private JsonNode data;

    public ConfigFile(Node parent, Path path, JsonNode data) {
        super(parent, path);

        this.path = path;
        this.data = data;
    }

    /**
     * See {@link Node#getID()}
     */
    @Override
    @JsonIgnore
    public String getID() {
        return getPath().toString();
    }

    @Override
    public List<Node> getChildren() {
        return new ArrayList<>();
    }

    @Override
    public List<? extends Node> getDescendants() {
        return new ArrayList<>();
    }
}