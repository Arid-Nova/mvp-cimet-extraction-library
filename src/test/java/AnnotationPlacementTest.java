import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

public class AnnotationPlacementTest {

    @Test
    public void testFieldAndClassAnnotationPlacement() throws IOException {
        final String TEST_FILE = TestUtilities.JAVA_FILES_PATH + File.separator + "TestFile.java";
        final Config TEST_CONFIG = ConfigUtil.readConfigFromFile(Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json"));
        RepositoryConfig rc = TEST_CONFIG.getSystemRepositories().getFirst();

        Microservice ms = new Microservice();
        ms.setName("test-ms");

        AbstractClass abstractClass = SourceToObjectUtils.parseClass(ms, new File(TEST_FILE), rc, false);
        Assertions.assertNotNull(abstractClass);

        // 1. Verify Class-level annotations: Should ONLY contain @Service, not @Autowired
        Set<Annotation> classAnnotations = abstractClass.getAnnotations();
        Assertions.assertEquals(1, classAnnotations.size());
        Annotation classAnn = classAnnotations.iterator().next();
        Assertions.assertEquals("Service", classAnn.getName());

        // 2. Verify Field-level annotations: restTemplate and discoveryClient should have @Autowired
        Set<Field> fields = abstractClass.getFields();
        Assertions.assertFalse(fields.isEmpty());

        Field restTemplateField = fields.stream()
                .filter(f -> f.getName().equals("restTemplate"))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(restTemplateField);
        Assertions.assertEquals(1, restTemplateField.getAnnotations().size());
        Assertions.assertEquals("Autowired", restTemplateField.getAnnotations().iterator().next().getName());

        Field discoveryClientField = fields.stream()
                .filter(f -> f.getName().equals("discoveryClient"))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(discoveryClientField);
        Assertions.assertEquals(1, discoveryClientField.getAnnotations().size());
        Assertions.assertEquals("Autowired", discoveryClientField.getAnnotations().iterator().next().getName());

        // LOGGER field has no annotations
        Field loggerField = fields.stream()
                .filter(f -> f.getName().equals("LOGGER"))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(loggerField);
        Assertions.assertTrue(loggerField.getAnnotations().isEmpty());
    }

    @Test
    public void testComplexSingleMemberAnnotationParsing() throws Exception {
        // Parse a dynamic dummy class snippet containing security annotations
        String classSnippet = """
                package com.example;
                import org.springframework.security.access.annotation.Secured;
                import org.springframework.security.access.prepost.PreAuthorize;
                import jakarta.annotation.security.RolesAllowed;
                
                @Secured("ROLE_ADMIN")
                public class SecurityController {
                    @RolesAllowed({"USER", "ADMIN"})
                    private String restrictedField;
                    
                    @PreAuthorize("hasRole('ROLE_USER')")
                    public void secureMethod() {}
                }
                """;

        // Let's write this snippet to a temp file and parse it
        File tempFile = File.createTempFile("SecurityController", ".java");
        tempFile.deleteOnExit();
        java.nio.file.Files.writeString(tempFile.toPath(), classSnippet);

        final Config TEST_CONFIG = ConfigUtil.readConfigFromFile(Path.of(TestUtilities.CONFIGS_PATH + File.separator + "test_config2.json"));
        RepositoryConfig rc = TEST_CONFIG.getSystemRepositories().getFirst();

        Microservice ms = new Microservice();
        ms.setName("security-ms");

        AbstractClass abstractClass = SourceToObjectUtils.parseClass(ms, tempFile, rc, false);
        Assertions.assertNotNull(abstractClass);

        // Verify class level annotation
        Set<Annotation> classAnns = abstractClass.getAnnotations();
        Assertions.assertEquals(1, classAnns.size());
        Annotation securedAnn = classAnns.iterator().next();
        Assertions.assertEquals("Secured", securedAnn.getName());
        Assertions.assertEquals("ROLE_ADMIN", securedAnn.getAttributes().get("default"));

        // Verify field level annotation with array value
        Field field = abstractClass.getFields().stream().filter(f -> f.getName().equals("restrictedField")).findFirst().orElse(null);
        Assertions.assertNotNull(field);
        Assertions.assertEquals(1, field.getAnnotations().size());
        Annotation rolesAllowed = field.getAnnotations().iterator().next();
        Assertions.assertEquals("RolesAllowed", rolesAllowed.getName());
        // Verify it captured the array literal value correctly as string representation instead of returning empty attributes map
        Assertions.assertEquals("{ \"USER\", \"ADMIN\" }", rolesAllowed.getAttributes().get("default"));

        // Verify method level annotation
        Method method = abstractClass.getMethods().stream().filter(m -> m.getName().equals("secureMethod")).findFirst().orElse(null);
        Assertions.assertNotNull(method);
        Assertions.assertEquals(1, method.getAnnotations().size());
        Annotation preAuth = method.getAnnotations().iterator().next();
        Assertions.assertEquals("PreAuthorize", preAuth.getName());
        Assertions.assertEquals("hasRole('ROLE_USER')", preAuth.getAttributes().get("default"));
    }
}
