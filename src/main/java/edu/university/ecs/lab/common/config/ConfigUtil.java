package edu.university.ecs.lab.common.config;

import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Utility class for reading and validating the input config file
 */
public class ConfigUtil {

    /**
     * Prevent instantiation
     */
    private ConfigUtil() {
    }

    /**
     * This method reads the input config and returns a Config object
     *
     * @param configPath path to the input config file
     * @return Config object
     */
    public static Config readConfigFromFile(Path configPath) throws IOException {
        return JsonReadWriteUtils.readFromJSON(configPath, Config.class);
    }

    /**
     * This method reads the input config from a String and returns a Config object
     *
     * @param config the String to parse the config from
     * @return Config object
     */
    public static Config readConfigFromString(String config) throws IOException {
        return JsonReadWriteUtils.readFromJSONString(config, Config.class);
    }
}
