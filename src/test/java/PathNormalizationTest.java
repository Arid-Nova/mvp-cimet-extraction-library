import com.fasterxml.jackson.databind.ObjectMapper;
import edu.university.ecs.lab.common.models.ir.AbstractClass;
import edu.university.ecs.lab.common.models.ir.JClass;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.FileUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

class PathNormalizationTest {
    @Test
    void normalizesCloneRootPathsToRepoRelativeGitPaths() {
        String expectedPath = "/ts-train-service/src/main/java/train/controller/TrainController.java";

        Assertions.assertEquals(expectedPath, FileUtils.normalizeRepositoryPath(
                "/app/clone/train-ticket/ts-train-service/src/main/java/train/controller/TrainController.java",
                "train-ticket"
        ));
        Assertions.assertEquals(expectedPath, FileUtils.normalizeRepositoryPath(
                "file:///app/clone/train-ticket/ts-train-service/src/main/java/train/controller/TrainController.java",
                "train-ticket"
        ));
        Assertions.assertEquals(expectedPath, FileUtils.normalizeRepositoryPath(
                "C:\\work\\clone\\train-ticket\\ts-train-service\\src\\main\\java\\train\\controller\\TrainController.java",
                "train-ticket"
        ));
        Assertions.assertEquals(expectedPath, FileUtils.localPathToGitPath(
                ".\\clone\\train-ticket\\ts-train-service\\src\\main\\java\\train\\controller\\TrainController.java",
                "train-ticket"
        ));
    }

    @Test
    void findsFilesWhenStoredIrPathHasCloneRootPrefix() {
        String gitPath = "/ts-train-service/src/main/java/train/controller/TrainController.java";
        String storedPath = "/app/clone/train-ticket" + gitPath;

        MicroserviceSystem system = new MicroserviceSystem("train-ticket", new HashSet<>(), new HashSet<>());
        Microservice microservice = new Microservice(
                system,
                "ts-train-service",
                "https://github.com/FudanSELab/train-ticket.git",
                "313886e99befb94be6cd45f085c98e0019f59829",
                Path.of("/app/clone/train-ticket/ts-train-service")
        );
        AbstractClass oldClass = new JClass(
                microservice,
                Path.of(storedPath),
                "train.controller",
                "",
                Set.of()
        );

        microservice.addAbstractClass(oldClass);
        system.getMicroservices().add(microservice);

        Assertions.assertSame(microservice, system.findMicroserviceByPath(Path.of(gitPath)));
        Assertions.assertSame(oldClass, system.findClass(Path.of(gitPath)));
        Assertions.assertSame(oldClass, system.findFile(Path.of(gitPath)));
    }

    @Test
    void serializesMicroservicePathAsStringWithDefaultObjectMapper() throws IOException {
        Microservice microservice = new Microservice(
                null,
                "ts-train-service",
                "https://github.com/FudanSELab/train-ticket.git",
                "313886e99befb94be6cd45f085c98e0019f59829",
                Path.of("/ts-train-service")
        );

        String json = new ObjectMapper().writeValueAsString(microservice);

        Assertions.assertTrue(json.contains("\"path\":\"/ts-train-service\""));
        Assertions.assertFalse(json.contains("file:///"));
    }
}
