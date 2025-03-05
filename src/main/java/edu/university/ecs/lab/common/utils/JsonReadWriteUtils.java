package edu.university.ecs.lab.common.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.*;

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
    public static <T> void writeToJSON(String filePath, T object) throws IOException {
        setupObjectWriter().writeValue(new File(filePath), object);
    }

    /**
     * Reads a JSON file from a given path and converts it into an object of the specified type.
     *
     * @param <T>      the type of the object to return
     * @param filePath the file path to the JSON file
     * @param type     the Class representing the type of the object to deserialize
     * @return an object of type T containing the data from the JSON file
     */
    public static <T> T readFromJSON(String filePath, Class<T> type) throws IOException {
        return setupObjectReader().readValue(new File(filePath), type);
    }

    public static ObjectWriter setupObjectWriter() {
        return new ObjectMapper().writerWithDefaultPrettyPrinter();
    }

    public static ObjectReader setupObjectReader() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return objectMapper.reader();
    }
}