package edu.university.ecs.lab.delta.models;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
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
public abstract class AbstractDelta {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The path to the changed file in question
     */
    @JsonSerialize(using = PathSerializer.class)
    protected final Path path;

    /**
     * The type of change that occurred
     */
    protected final ChangeType changeType;

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
