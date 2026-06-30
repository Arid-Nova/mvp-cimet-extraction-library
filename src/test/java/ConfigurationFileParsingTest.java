import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.ConfigFile;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

class ConfigurationFileParsingTest {

    @Test
    void parsesYamlPropertiesPomGradleAndDockerConfigurationFiles(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("parser-repo");
        RepositoryConfig config = repositoryConfig("parser-repo");
        Microservice microservice = new Microservice();

        Path yaml = repository.resolve("application.yaml");
        Path properties = repository.resolve("application.properties");
        Path pom = repository.resolve("pom.xml");
        Path gradle = repository.resolve("build.gradle");
        Path dockerfile = repository.resolve("Dockerfile");

        writeFile(yaml, """
                spring:
                  application:
                    name: parser-service
                server:
                  port: 8080
                """);
        writeFile(properties, """
                spring.application.name=properties-service
                feature.enabled=true
                """);
        writeFile(pom, """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>parser-artifact</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        writeFile(gradle, """
                plugins {
                    id 'java'
                }
                version = '1.0.0'
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                """);
        writeFile(dockerfile, """
                FROM eclipse-temurin:21
                COPY target/app.jar app.jar
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """);

        ConfigFile yamlConfig = SourceToObjectUtils.parseConfigurationFile(yaml.toFile(), config, microservice);
        ConfigFile propertiesConfig = SourceToObjectUtils.parseConfigurationFile(properties.toFile(), config, microservice);
        ConfigFile pomConfig = SourceToObjectUtils.parseConfigurationFile(pom.toFile(), config, microservice);
        ConfigFile gradleConfig = SourceToObjectUtils.parseConfigurationFile(gradle.toFile(), config, microservice);
        ConfigFile dockerConfig = SourceToObjectUtils.parseConfigurationFile(dockerfile.toFile(), config, microservice);
        ConfigFile unsupportedConfig = SourceToObjectUtils.parseConfigurationFile(
                repository.resolve("README.md").toFile(),
                config,
                microservice
        );

        Assertions.assertNotNull(yamlConfig);
        Assertions.assertEquals("parser-service", yamlConfig.getData().get("spring").get("application").get("name").asText());
        Assertions.assertEquals(8080, yamlConfig.getData().get("server").get("port").asInt());

        Assertions.assertNotNull(propertiesConfig);
        Assertions.assertEquals("properties-service", propertiesConfig.getData().get("spring.application.name").asText());
        Assertions.assertEquals("true", propertiesConfig.getData().get("feature.enabled").asText());

        Assertions.assertNotNull(pomConfig);
        Assertions.assertTrue(pomConfig.getData().toString().contains("parser-artifact"));

        Assertions.assertNotNull(gradleConfig);
        Assertions.assertTrue(gradleConfig.getData().has("plugins"));
        Assertions.assertEquals("\"1.0.0\"", gradleConfig.getData().get("version").asText());
        Assertions.assertTrue(gradleConfig.getData().has("dependencies"));

        Assertions.assertNotNull(dockerConfig);
        Assertions.assertEquals("FROM eclipse-temurin:21", dockerConfig.getData().get("instructions").get(0).asText());
        Assertions.assertEquals("COPY target/app.jar app.jar", dockerConfig.getData().get("instructions").get(1).asText());
        Assertions.assertEquals("ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]", dockerConfig.getData().get("instructions").get(2).asText());

        Assertions.assertNull(unsupportedConfig);
    }

    @Test
    void parsesEmptyYamlAndPomFilesAsEmptyObjects(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("empty-config-repo");
        RepositoryConfig config = repositoryConfig("empty-config-repo");
        Microservice microservice = new Microservice();

        Path yaml = repository.resolve("application.yml");
        Path pom = repository.resolve("pom.xml");

        writeFile(yaml, "");
        writeFile(pom, "");

        ConfigFile yamlConfig = SourceToObjectUtils.parseConfigurationFile(yaml.toFile(), config, microservice);
        ConfigFile pomConfig = SourceToObjectUtils.parseConfigurationFile(pom.toFile(), config, microservice);

        Assertions.assertNotNull(yamlConfig);
        Assertions.assertTrue(yamlConfig.getData().isObject());
        Assertions.assertEquals(0, yamlConfig.getData().size());

        Assertions.assertNotNull(pomConfig);
        Assertions.assertTrue(pomConfig.getData().isObject());
        Assertions.assertEquals(0, pomConfig.getData().size());
    }

    @Test
    void returnsNullForMissingConfigurationFiles(@TempDir Path tempDir) {
        Path repository = tempDir.resolve("missing-config-repo");
        RepositoryConfig config = repositoryConfig("missing-config-repo");
        Microservice microservice = new Microservice();

        Assertions.assertNull(SourceToObjectUtils.parseConfigurationFile(
                repository.resolve("application.yml").toFile(),
                config,
                microservice
        ));
        Assertions.assertNull(SourceToObjectUtils.parseConfigurationFile(
                repository.resolve("application.properties").toFile(),
                config,
                microservice
        ));
        Assertions.assertNull(SourceToObjectUtils.parseConfigurationFile(
                repository.resolve("pom.xml").toFile(),
                config,
                microservice
        ));
        Assertions.assertNull(SourceToObjectUtils.parseConfigurationFile(
                repository.resolve("build.gradle.kts").toFile(),
                config,
                microservice
        ));
        Assertions.assertNull(SourceToObjectUtils.parseConfigurationFile(
                repository.resolve("Dockerfile").toFile(),
                config,
                microservice
        ));
    }

    private RepositoryConfig repositoryConfig(String repoName) {
        return new RepositoryConfig(
                new RepositoryBranchPair("https://github.com/example/%s.git".formatted(repoName), "main"),
                "HEAD"
        );
    }

    private void writeFile(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
