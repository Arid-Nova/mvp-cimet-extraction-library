package edu.university.ecs.lab.common.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.*;
import java.nio.file.Path;

/**
 * Utility class for reading and writing JSON to a file.
 */
public class JsonReadWriteUtils {
    /**
     * Private constructor to prevent instantiation.
     */
    private JsonReadWriteUtils() {
    }

    /**
     * Writes an object to a JSON file at a specified path.
     *
     * @param <T>      the type of the object to write
     * @param object   the object to serialize into JSON
     * @param filePath the file path where the JSON should be saved
     */
    public static <T> void writeToJSON(Path filePath, T object) throws IOException {
        setupObjectWriter().writeValue(new File(filePath.normalize().toString()), object);
    }

    /**
     * Reads a JSON file from a given path and converts it into an object of the specified type.
     *
     * @param <T>      the type of the object to return
     * @param filePath the file path to the JSON file
     * @param type     the Class representing the type of the object to deserialize
     * @return an object of type T containing the data from the JSON file
     */
    public static <T> T readFromJSON(Path filePath, Class<T> type) throws IOException {
        return setupObjectReader().readValue(new File(filePath.normalize().toString()), type);
    }

    /**
     * Reads a JSON object from a String
     *
     * @param <T>      the type of the object to return
     * @param data     the String containing JSON data
     * @param type     the Class representing the type of the object to deserialize
     * @return an object of type T containing the data from the JSON file
     */
    public static <T> T readFromJSONString(String data, Class<T> type) throws IOException {
        return setupObjectReader().readValue(data, type);
    }

    public static ObjectWriter setupObjectWriter() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();

        module.addSerializer(new PathSerializer());
        objectMapper.registerModule(module);

        return objectMapper.writerWithDefaultPrettyPrinter();
    }

    public static ObjectReader setupObjectReader() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return objectMapper.reader();
    }

    public static class PathSerializer extends StdSerializer<Path> {

        public PathSerializer() {
            super(Path.class);
        }

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