package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import edu.university.ecs.lab.common.models.ir.JClass;
import edu.university.ecs.lab.common.models.ir.JEnum;
import edu.university.ecs.lab.common.models.ir.JInterface;
import edu.university.ecs.lab.common.models.ir.JRecord;
import edu.university.ecs.lab.delta.models.enums.ChangeType;
import lombok.*;

import java.io.IOException;
import java.nio.file.Path;

/**
 * This class represents a single Delta change between two commits.
 * In the case of ChangeType.DELETE @see {@link ChangeType} the
 * classChange will respectively be null as the instance of this class
 * is no longer locally present for parsing at the new commit
 */
@Getter
@Setter
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type"
)
@JsonSubTypes({@JsonSubTypes.Type(value = SimpleDelta.class, name = "SimpleDelta"),
        @JsonSubTypes.Type(value = ModifyDelta.class, name = "ModifyDelta")})

@JsonTypeName("AbstractDelta")
public abstract class AbstractDelta {
    /**
     * The path to the changed file in question
     */
    @JsonSerialize(using = PathSerializer.class)
    protected Path path;

    /**
     * The type of change that occurred
     */
    protected ChangeType changeType;

    protected AbstractDelta() {}

    protected AbstractDelta(Path path, ChangeType changeType) {
        this.path = path;
        this.changeType = changeType;
    }

    private static class PathSerializer extends JsonSerializer<Path> {

        @Override
        public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value != null) {
                // Convert the Path object to a simple string in the JSON
                gen.writeString(value.toString().replace('\\', '/'));
            } else {
                // Handle null Path objects by writing a JSON null
                gen.writeNull();
            }
        }
    }
}
