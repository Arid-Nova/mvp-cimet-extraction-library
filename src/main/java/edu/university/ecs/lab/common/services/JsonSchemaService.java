package edu.university.ecs.lab.common.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.JsonSchemaGenerator;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.SystemChange;

import java.io.FileWriter;
import java.io.IOException;

/**
 * Service for authomatically generating JSON documentation.
 */
public class JsonSchemaService {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final JsonSchemaGenerator schemaGenerator = new JsonSchemaGenerator(mapper);

    /**
     * Creates a JSON representation of the MicroserviceSystem JSON schema
     * @return JsonSchema containing the JSON schema
     */
    private static JsonSchema getMicroserviceSystemSchema() throws JsonProcessingException {
        return schemaGenerator.generateSchema(MicroserviceSystem.class);
    }

    /**
     * Creates a JSON representation of the SystemChange JSON schema
     * @return JsonSchema containing the JSON schema
     */
    private static JsonSchema getSystemChangeSchema() throws JsonProcessingException {
        return schemaGenerator.generateSchema(SystemChange.class);
    }

    /**
     * Writes JSON schema documentation for MicroserviceSystem and SystemChange to the default location (./docs/).
     */
    public static void writeSchemas() throws IOException {
        // Ensure the local directories exist
        FileUtils.makeDirs();

        writeSchemas("./docs/");
    }

    /**
     * Writes JSON schema documentation for MicroserviceSystem and SystemChange.
     * @param directoryPath A path to the folder where the schemas should be written
     */
    public static void writeSchemas(String directoryPath) throws IOException {
        // Ensure the local directories exist
        FileUtils.makeDirs();

        JsonReadWriteUtils.writeToJSON(directoryPath + "MicroserviceSystemSchema.json", getMicroserviceSystemSchema());
        JsonReadWriteUtils.writeToJSON(directoryPath + "SystemChangeSchema.json", getSystemChangeSchema());
    }
}
