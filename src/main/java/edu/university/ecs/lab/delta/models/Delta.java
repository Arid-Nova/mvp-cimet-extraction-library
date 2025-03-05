package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.models.ir.ConfigFile;
import edu.university.ecs.lab.common.models.ir.JClass;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * This class represents a single Delta change between two commits.
 * In the case of ChangeType.DELETE @see {@link ChangeType} the
 * classChange will respectively be null as the instance of this class
 * is no longer locally present for parsing at the new commit
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonTypeName("Delta")
public class Delta {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The new path to the file changed/added
     * Note: The path may be null in the event of an add
     */
    private String oldPath;

    /**
     * The old path to the file changed/added
     * Note: The path may be null in the event of an delete
     */
    private String newPath;

    /**
     * The type of change that occurred
     */
    private ChangeType changeType;

    /**
     * The changed contents, could be a changed class or
     * a changed configuration file
     */
    private JsonNode data;

    /**
     * This method returns an instance of JClass if parsable.
     *
     * @return JClass instance if parsable otherwise null
     */
    @JsonIgnore
    public JClass getClassChange() {
        if(data.isEmpty()) {
            return null;
        }
        try {
            if(data.has("fileType") && ("JCLASS".equals(data.get("fileType").asText()) ||
                "JINTERFACE".equals(data.get("fileType").asText()) ||
                "JENUM".equals(data.get("fileType").asText()) ||
                "JRECORD".equals(data.get("fileType").asText()))) {
                return objectMapper.treeToValue(data, JClass.class);
            }
        } catch (JsonProcessingException e) {
            return null;
        }
        return null;
    }

    /**
     * This method returns an instance of ConfigFile if parsable.
     *
     * @return ConfigFile instance if parsable otherwise null
     */
    @JsonIgnore
    public ConfigFile getConfigChange() {
        if(data.isEmpty()) {
            return null;
        }
        try {
            if (data.has("fileType") && "CONFIG".equals(data.get("fileType").asText())) {
                return objectMapper.treeToValue(data, ConfigFile.class);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
