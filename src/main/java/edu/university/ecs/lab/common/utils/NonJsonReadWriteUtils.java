package edu.university.ecs.lab.common.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.ConfigFile;
import edu.university.ecs.lab.common.models.ir.Microservice;
import org.json.JSONObject;
import org.json.XML;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Utility class for reading files that don't abide by JSON format
 */
public class NonJsonReadWriteUtils {
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Private constructor to prevent instantiation.
     */
    private NonJsonReadWriteUtils() {}

    /**
     * This method reads YAML from a file returning structure as JsonObject
     * @param path the path to the YAML file.
     * @return JsonObject YAML file structure as json object
     */
    public static ConfigFile readFromYaml(String path, RepositoryConfig config, Microservice microservice) {
        JsonNode data;
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        try (FileInputStream fis = new FileInputStream(path)) {
            Map<String, Object> yamlMap = yaml.load(fis);
            data = (yamlMap == null || yamlMap.isEmpty()) ?
                    JsonNodeFactory.instance.objectNode() :
                    mapper.valueToTree(yamlMap);
        } catch (Exception e) {
            // Handle I/O errors (file not found, etc.)
            return null;
        }
        return new ConfigFile(microservice, Path.of(FileUtils.localPathToGitPath(path, config.getRepoName())), data);
    }

    public static ConfigFile readFromDocker(Path path, RepositoryConfig config, Microservice microservice) {
        ArrayNode jsonArray = mapper.createArrayNode();
        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                jsonArray.add(line.trim());
            }
        } catch (Exception e) {
            return null;
        }
        ObjectNode jsonObject = mapper.createObjectNode();
        jsonObject.set("instructions", jsonArray);
        return new ConfigFile(microservice, Path.of(FileUtils.localPathToGitPath(path.toString(), config.getRepoName())), jsonObject);
    }

    public static ConfigFile readFromPom(Path path, RepositoryConfig config, Microservice microservice) {
        JsonNode jsonObject;
        try {
            String xmlContent = new String(Files.readAllBytes(path)).trim();
            if (xmlContent.isEmpty()) {
                jsonObject = JsonNodeFactory.instance.objectNode();
            } else {
                JSONObject jsonObjectOld = XML.toJSONObject(xmlContent);
                jsonObject = mapper.readTree(jsonObjectOld.toString());
            }
        } catch (Exception e) {
            return null;
        }
        return new ConfigFile(microservice, Path.of(FileUtils.localPathToGitPath(path.toString(), config.getRepoName())), jsonObject);
    }

    public static ConfigFile readFromGradle(Path path, RepositoryConfig config, Microservice microservice) {
        ObjectNode jsonObject = mapper.createObjectNode();
        Stack<ObjectNode> jsonStack = new Stack<>();
        jsonStack.push(jsonObject);

        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String line;
            String currentKey = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) continue;

                if (line.endsWith("{")) {
                    String key = line.substring(0, line.length() - 1).trim();
                    ObjectNode newObject = mapper.createObjectNode();
                    jsonStack.peek().set(key, newObject);
                    jsonStack.push(newObject);
                    currentKey = key;
                } else if (line.equals("}")) {
                    jsonStack.pop();
                    currentKey = null;
                } else if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        jsonStack.peek().put(parts[0].trim(), parts[1].trim().replace("'", "\""));
                    }
                } else {
                    if (currentKey != null) {
                        ArrayNode array = jsonStack.peek().has(currentKey) ?
                                (ArrayNode) jsonStack.peek().get(currentKey) :
                                mapper.createArrayNode();
                        array.add(line);
                        jsonStack.peek().set(currentKey, array);
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return new ConfigFile(microservice, Path.of(FileUtils.localPathToGitPath(path.toString(), config.getRepoName())), jsonObject);
    }

    public static ConfigFile readFromProperties(Path path, RepositoryConfig config, Microservice microservice) {
        ObjectNode jsonObject = mapper.createObjectNode();
        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
            for (String propertyName : properties.stringPropertyNames()) {
                jsonObject.put(propertyName, properties.getProperty(propertyName));
            }
        } catch (IOException e) {
            return null;
        }

        return new ConfigFile(microservice, Path.of(FileUtils.localPathToGitPath(path.toString(), config.getRepoName())), jsonObject);
    }
}
