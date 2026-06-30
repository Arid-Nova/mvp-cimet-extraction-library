import edu.university.ecs.lab.common.config.RepositoryBranchPair;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.ConfigFile;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import edu.university.ecs.lab.intermediate.create.services.ServiceBoundaryCandidate;
import edu.university.ecs.lab.intermediate.create.services.ServiceBoundaryDetector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

class ServiceBoundaryDetectorTest {
    private static final Path FIXTURE_ROOT = Path.of(
            "src",
            "test",
            "resources",
            "service-boundary-repos"
    );

    private final ServiceBoundaryDetector detector = new ServiceBoundaryDetector();

    @Test
    void detectsMultipleServicesInSingleRepository() throws IOException {
        List<ServiceBoundaryCandidate> candidates = detectFixture("single-repo-multiple-services");

        Assertions.assertEquals(2, candidates.size());
        Assertions.assertEquals(Set.of("payment-service", "order-service"), serviceNames(candidates));

        ServiceBoundaryCandidate payment = candidateByName(candidates, "payment-service");
        assertCandidate(payment, "single-repo-multiple-services", Path.of("services", "payment-service"), 13);
        assertEvidenceContains(payment, "build file: pom.xml");
        assertEvidenceContains(payment, "application config: src\\main\\resources\\application.yml");
        assertEvidenceContains(payment, "SpringBootApplication: src\\main\\java\\example\\PaymentApplication.java");

        ServiceBoundaryCandidate order = candidateByName(candidates, "order-service");
        assertCandidate(order, "single-repo-multiple-services", Path.of("services", "order-service"), 13);
        assertEvidenceContains(order, "build file: build.gradle");
        assertEvidenceContains(order, "application config: src\\main\\resources\\application.properties");
        assertEvidenceContains(order, "SpringBootApplication: src\\main\\java\\example\\OrderApplication.java");
    }

    @Test
    void detectsOneServicePerRepository() throws IOException {
        List<ServiceBoundaryCandidate> authCandidates = detectFixture("auth-repo");
        List<ServiceBoundaryCandidate> paymentCandidates = detectFixture("payment-repo");

        ServiceBoundaryCandidate auth = singleCandidate(authCandidates);
        assertCandidate(auth, "auth-repo", Path.of(""), "auth-service", 10);
        assertEvidenceContains(auth, "build file: pom.xml");
        assertEvidenceContains(auth, "SpringBootApplication: src\\main\\java\\example\\AuthApplication.java");
        Assertions.assertFalse(hasEvidenceStartingWith(auth, "application config:"));

        ServiceBoundaryCandidate payment = singleCandidate(paymentCandidates);
        assertCandidate(payment, "payment-repo", Path.of(""), "payment-service", 10);
        assertEvidenceContains(payment, "build file: pom.xml");
        assertEvidenceContains(payment, "SpringBootApplication: src\\main\\java\\example\\PaymentApplication.java");
        Assertions.assertFalse(hasEvidenceStartingWith(payment, "application config:"));
    }

    @Test
    void detectsMultipleServicesAcrossMultipleRepositories() throws IOException {
        List<ServiceBoundaryCandidate> commerceCandidates = detectFixture("commerce-repo");
        List<ServiceBoundaryCandidate> identityCandidates = detectFixture("identity-repo");

        Assertions.assertEquals(2, commerceCandidates.size());
        Assertions.assertEquals(2, identityCandidates.size());

        Set<String> combinedServiceNames = serviceNames(commerceCandidates);
        combinedServiceNames.addAll(serviceNames(identityCandidates));
        Assertions.assertEquals(
                Set.of("payment-service", "order-service", "auth-service", "profile-service"),
                combinedServiceNames
        );

        assertCandidate(candidateByName(commerceCandidates, "payment-service"), "commerce-repo", Path.of("payment-service"), 13);
        assertCandidate(candidateByName(commerceCandidates, "order-service"), "commerce-repo", Path.of("order-service"), 13);
        assertCandidate(candidateByName(identityCandidates, "auth-service"), "identity-repo", Path.of("auth-service"), 13);
        assertCandidate(candidateByName(identityCandidates, "profile-service"), "identity-repo", Path.of("profile-service"), 13);
    }

    @Test
    void ignoresSharedLibrariesWithoutServiceEvidence() throws IOException {
        List<ServiceBoundaryCandidate> candidates = detectFixture("shared-library-repo");

        Assertions.assertEquals(1, candidates.size());
        ServiceBoundaryCandidate payment = singleCandidate(candidates);
        assertCandidate(payment, "shared-library-repo", Path.of("services", "payment-service"), "payment-service", 13);
        Assertions.assertFalse(serviceNames(candidates).contains("common-utils"));
        Assertions.assertTrue(candidates.stream().noneMatch(candidate -> candidate.rootPath().toString().contains("common-utils")));
    }

    @Test
    void prefersSpringApplicationNameOverMavenArtifactAndFolderName() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("name-priority-repo"));

        assertCandidate(candidate, "name-priority-repo", Path.of("folder-name"), "configured-service-name", 13);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "application config: src\\main\\resources\\application.yaml");
        assertEvidenceContains(candidate, "SpringBootApplication: src\\main\\java\\example\\ConfiguredApplication.java");
        Assertions.assertNotEquals("maven-artifact-name", candidate.serviceName());
        Assertions.assertNotEquals("folder-name", candidate.serviceName());
    }

    @Test
    void readsSpringApplicationNameFromPropertiesBeforeMavenAndFolderName() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("properties-name-repo"));

        assertCandidate(candidate, "properties-name-repo", Path.of("folder-name"), "properties-configured-service", 13);
        assertEvidenceContains(candidate, "application config: src\\main\\resources\\application.properties");
        Assertions.assertNotEquals("maven-name", candidate.serviceName());
        Assertions.assertNotEquals("folder-name", candidate.serviceName());
    }

    @Test
    void readsFlatYamlSpringApplicationNameBeforeMavenAndFolderName() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("flat-yaml-name-repo"));

        assertCandidate(candidate, "flat-yaml-name-repo", Path.of("folder-name"), "flat-yaml-service", 13);
        assertEvidenceContains(candidate, "application config: src\\main\\resources\\application.yml");
        Assertions.assertNotEquals("maven-name", candidate.serviceName());
        Assertions.assertNotEquals("folder-name", candidate.serviceName());
    }

    @Test
    void fallsBackToGradleRootProjectNameBeforeFolderName() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("gradle-name-repo"));

        assertCandidate(candidate, "gradle-name-repo", Path.of("folder-name"), "gradle-service-name", 9);
        assertEvidenceContains(candidate, "build file: build.gradle.kts");
        assertEvidenceContains(candidate, "dockerfile: Dockerfile");
        Assertions.assertFalse(hasEvidenceStartingWith(candidate, "application config:"));
    }

    @Test
    void fallsBackToFolderNameWhenNoStrongerNameSourceExists() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("folder-name-repo"));

        assertCandidate(candidate, "folder-name-repo", Path.of("folder-service"), "folder-service", 10);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "SpringBootApplication: src\\main\\java\\example\\FolderApplication.java");
    }

    @Test
    void acceptsStandardDockerfileAsServiceBoundaryEvidence() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("docker-signals-repo"));

        assertCandidate(candidate, "docker-signals-repo", Path.of("dockerized-service"), "dockerized-service", 9);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "dockerfile: Dockerfile");
        Assertions.assertFalse(hasEvidenceStartingWith(candidate, "application config:"));
    }

    @Test
    void acceptsLegacyDockerFileCasingAsServiceBoundaryEvidence() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("legacy-dockerfile-repo"));

        assertCandidate(candidate, "legacy-dockerfile-repo", Path.of("legacy-docker-service"), "legacy-docker-service", 9);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "dockerfile: DockerFile");
    }

    @Test
    void acceptsDockerComposeOnlyWhenItDefinesServices() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("compose-service-repo"));

        assertCandidate(candidate, "compose-service-repo", Path.of("compose-service"), "compose-service", 7);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "docker-compose services: docker-compose.yaml");
        Assertions.assertFalse(hasEvidenceStartingWith(candidate, "dockerfile:"));
    }

    @Test
    void rejectsDockerComposeWithoutServiceDefinition() throws IOException {
        List<ServiceBoundaryCandidate> candidates = detectFixture("plain-compose-repo");

        Assertions.assertTrue(candidates.isEmpty());
    }

    @Test
    void acceptsKubernetesDeploymentAndHelmChartSignals() throws IOException {
        List<ServiceBoundaryCandidate> candidates = detectFixture("deployment-signals-repo");

        Assertions.assertEquals(2, candidates.size());
        Assertions.assertEquals(Set.of("k8s-service", "helm-service"), serviceNames(candidates));

        ServiceBoundaryCandidate k8s = candidateByName(candidates, "k8s-service");
        assertCandidate(k8s, "deployment-signals-repo", Path.of("k8s-service"), 8);
        assertEvidenceContains(k8s, "build file: pom.xml");
        assertEvidenceContains(k8s, "kubernetes deployment: k8s\\deployment.yaml");
        Assertions.assertFalse(hasEvidenceStartingWith(k8s, "helm chart:"));

        ServiceBoundaryCandidate helm = candidateByName(candidates, "helm-service");
        assertCandidate(helm, "deployment-signals-repo", Path.of("helm-service"), 8);
        assertEvidenceContains(helm, "build file: pom.xml");
        assertEvidenceContains(helm, "helm chart: charts\\helm-service\\Chart.yaml");
        Assertions.assertFalse(hasEvidenceStartingWith(helm, "kubernetes deployment:"));
    }

    @Test
    void acceptsControllerSignalAtMinimumThreshold() throws IOException {
        ServiceBoundaryCandidate candidate = singleCandidate(detectFixture("controller-only-repo"));

        assertCandidate(candidate, "controller-only-repo", Path.of("controller-service"), "controller-service", 7);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "controller annotation: src\\main\\java\\example\\ControllerOnlyResource.java");
        assertEvidenceContains(candidate, "RequestMapping: src\\main\\java\\example\\ControllerOnlyResource.java");
        Assertions.assertFalse(hasEvidenceStartingWith(candidate, "SpringBootApplication:"));
    }

    @Test
    void keepsChildServiceInsteadOfAcceptedParentBoundary() throws IOException {
        List<ServiceBoundaryCandidate> candidates = detectFixture("parent-child-repo");

        ServiceBoundaryCandidate child = singleCandidate(candidates);
        assertCandidate(child, "parent-child-repo", Path.of("services", "child-service"), "child-service", 13);
        Assertions.assertFalse(serviceNames(candidates).contains("parent-service"));
        Assertions.assertFalse(candidates.stream().anyMatch(candidate -> candidate.rootPath().equals(fixture("parent-child-repo"))));
    }

    @Test
    void skipsIgnoredGeneratedDirectories() throws IOException {
        List<ServiceBoundaryCandidate> candidates = detectFixture("ignored-directory-repo");

        Assertions.assertTrue(candidates.isEmpty());
    }

    @Test
    void rejectsInvalidRepositoryPaths() {
        IOException fileException = Assertions.assertThrows(
                IOException.class,
                () -> detector.detect(fixture("invalid-path-file.txt").toString(), repositoryConfig("invalid-path-file"))
        );
        Assertions.assertEquals("Repository path must exist and be directory", fileException.getMessage());

        IOException missingException = Assertions.assertThrows(
                IOException.class,
                () -> detector.detect(fixture("missing-repo").toString(), repositoryConfig("missing-repo"))
        );
        Assertions.assertEquals("Repository path must exist and be directory", missingException.getMessage());
    }

    @Test
    void parsesDockerfileConfigurationFilesWithStandardAndLegacyCasing() {
        ConfigFile standardDockerfile = SourceToObjectUtils.parseConfigurationFile(
                fixture("dockerfile-config-repo").resolve("Dockerfile").toFile(),
                repositoryConfig("dockerfile-config-repo"),
                new Microservice()
        );
        ConfigFile legacyDockerFile = SourceToObjectUtils.parseConfigurationFile(
                fixture("legacy-dockerfile-repo").resolve("legacy-docker-service").resolve("DockerFile").toFile(),
                repositoryConfig("legacy-dockerfile-repo"),
                new Microservice()
        );

        Assertions.assertNotNull(standardDockerfile);
        Assertions.assertEquals("FROM eclipse-temurin:21", standardDockerfile.getData().get("instructions").get(0).asText());
        Assertions.assertEquals("COPY target/app.jar app.jar", standardDockerfile.getData().get("instructions").get(1).asText());

        Assertions.assertNotNull(legacyDockerFile);
        Assertions.assertEquals("FROM eclipse-temurin:21", legacyDockerFile.getData().get("instructions").get(0).asText());
    }

    @Test
    void acceptsDirectDeploymentManifestAndDirectHelmChart(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("deployment-root-repo");

        writeFile(repository.resolve("direct-deployment-service").resolve("pom.xml"), pom("direct-deployment-artifact"));
        writeFile(repository.resolve("direct-deployment-service").resolve("deployment.yml"), """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: direct-deployment-service
                """);
        writeFile(repository.resolve("direct-helm-service").resolve("pom.xml"), pom("direct-helm-artifact"));
        writeFile(repository.resolve("direct-helm-service").resolve("Chart.yaml"), """
                apiVersion: v2
                name: direct-helm-service
                version: 0.1.0
                """);

        List<ServiceBoundaryCandidate> candidates = detector.detect(
                repository.toString(),
                repositoryConfig("deployment-root-repo")
        );

        Assertions.assertEquals(2, candidates.size());
        Assertions.assertEquals(Set.of("direct-deployment-artifact", "direct-helm-artifact"), serviceNames(candidates));

        ServiceBoundaryCandidate directDeployment = candidateByName(candidates, "direct-deployment-artifact");
        assertCandidate(directDeployment, repository, Path.of("direct-deployment-service"), "direct-deployment-artifact", 8);
        assertEvidenceContains(directDeployment, "kubernetes deployment: deployment.yml");
        Assertions.assertFalse(hasEvidenceStartingWith(directDeployment, "helm chart:"));

        ServiceBoundaryCandidate directHelm = candidateByName(candidates, "direct-helm-artifact");
        assertCandidate(directHelm, repository, Path.of("direct-helm-service"), "direct-helm-artifact", 8);
        assertEvidenceContains(directHelm, "helm chart: Chart.yaml");
        Assertions.assertFalse(hasEvidenceStartingWith(directHelm, "kubernetes deployment:"));
    }

    @Test
    void fallsBackWhenConfigurationNamesAreMissingMalformedOrBlank(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("fallback-name-repo");

        writeFile(repository.resolve("malformed-yaml-service").resolve("pom.xml"), pom("malformed-yaml-artifact"));
        writeFile(repository.resolve("malformed-yaml-service").resolve("src").resolve("main").resolve("resources").resolve("application.yml"), """
                spring:
                  application: [
                """);

        writeFile(repository.resolve("blank-properties-service").resolve("pom.xml"), """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        writeFile(repository.resolve("blank-properties-service").resolve("src").resolve("main").resolve("resources").resolve("application.properties"), "spring.application.name=   \n");
        writeFile(repository.resolve("blank-properties-service").resolve("Dockerfile"), "FROM eclipse-temurin:21\n");

        writeFile(repository.resolve("gradle-without-name-service").resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                """);
        writeFile(repository.resolve("gradle-without-name-service").resolve("Dockerfile"), "FROM eclipse-temurin:21\n");

        List<ServiceBoundaryCandidate> candidates = detector.detect(
                repository.toString(),
                repositoryConfig("fallback-name-repo")
        );

        Assertions.assertEquals(3, candidates.size());
        Assertions.assertEquals(
                Set.of("malformed-yaml-artifact", "blank-properties-service", "gradle-without-name-service"),
                serviceNames(candidates)
        );

        ServiceBoundaryCandidate malformedYaml = candidateByName(candidates, "malformed-yaml-artifact");
        assertCandidate(malformedYaml, repository, Path.of("malformed-yaml-service"), "malformed-yaml-artifact", 8);
        assertEvidenceContains(malformedYaml, "application config: src\\main\\resources\\application.yml");

        ServiceBoundaryCandidate blankProperties = candidateByName(candidates, "blank-properties-service");
        assertCandidate(blankProperties, repository, Path.of("blank-properties-service"), "blank-properties-service", 12);
        assertEvidenceContains(blankProperties, "application config: src\\main\\resources\\application.properties");
        assertEvidenceContains(blankProperties, "dockerfile: Dockerfile");

        ServiceBoundaryCandidate gradleWithoutName = candidateByName(candidates, "gradle-without-name-service");
        assertCandidate(gradleWithoutName, repository, Path.of("gradle-without-name-service"), "gradle-without-name-service", 9);
        assertEvidenceContains(gradleWithoutName, "build file: build.gradle");
        assertEvidenceContains(gradleWithoutName, "dockerfile: Dockerfile");
    }

    @Test
    void acceptsCaseInsensitiveDockerfileName(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("lowercase-dockerfile-repo");

        writeFile(repository.resolve("lowercase-docker-service").resolve("pom.xml"), pom("lowercase-docker-artifact"));
        writeFile(repository.resolve("lowercase-docker-service").resolve("dockerfile"), "FROM eclipse-temurin:21\n");

        ServiceBoundaryCandidate candidate = singleCandidate(detector.detect(
                repository.toString(),
                repositoryConfig("lowercase-dockerfile-repo")
        ));

        assertCandidate(candidate, repository, Path.of("lowercase-docker-service"), "lowercase-docker-artifact", 9);
        assertEvidenceContains(candidate, "build file: pom.xml");
        assertEvidenceContains(candidate, "dockerfile: dockerfile");
    }

    @Test
    void rejectsComposeFilesWithInvalidYamlOrEmptyServices(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("compose-edge-repo");

        writeFile(repository.resolve("invalid-compose-service").resolve("pom.xml"), pom("invalid-compose-artifact"));
        writeFile(repository.resolve("invalid-compose-service").resolve("docker-compose.yml"), "services: [\n");
        writeFile(repository.resolve("empty-compose-service").resolve("pom.xml"), pom("empty-compose-artifact"));
        writeFile(repository.resolve("empty-compose-service").resolve("docker-compose.yaml"), "services: {}\n");

        List<ServiceBoundaryCandidate> candidates = detector.detect(
                repository.toString(),
                repositoryConfig("compose-edge-repo")
        );

        Assertions.assertTrue(candidates.isEmpty());
    }

    @Test
    void ignoresRootDeploymentYamlWhenKindIsNotDeployment(@TempDir Path tempDir) throws IOException {
        Path repository = tempDir.resolve("non-deployment-root-repo");

        writeFile(repository.resolve("service-manifest-service").resolve("pom.xml"), pom("service-manifest-artifact"));
        writeFile(repository.resolve("service-manifest-service").resolve("Dockerfile"), "FROM eclipse-temurin:21\n");
        writeFile(repository.resolve("service-manifest-service").resolve("deployment.yaml"), """
                apiVersion: v1
                kind: Service
                metadata:
                  name: service-manifest-service
                """);

        ServiceBoundaryCandidate candidate = singleCandidate(detector.detect(
                repository.toString(),
                repositoryConfig("non-deployment-root-repo")
        ));

        assertCandidate(candidate, repository, Path.of("service-manifest-service"), "service-manifest-artifact", 9);
        assertEvidenceContains(candidate, "dockerfile: Dockerfile");
        Assertions.assertFalse(hasEvidenceStartingWith(candidate, "kubernetes deployment:"));
    }

    private List<ServiceBoundaryCandidate> detectFixture(String fixtureName) throws IOException {
        return detector.detect(fixture(fixtureName).toString(), repositoryConfig(fixtureName));
    }

    private Path fixture(String fixtureName) {
        return FIXTURE_ROOT.resolve(fixtureName);
    }

    private RepositoryConfig repositoryConfig(String repoName) {
        return new RepositoryConfig(
                new RepositoryBranchPair("https://github.com/example/%s.git".formatted(repoName), "main"),
                "HEAD"
        );
    }

    private Set<String> serviceNames(List<ServiceBoundaryCandidate> candidates) {
        return candidates.stream()
                .map(ServiceBoundaryCandidate::serviceName)
                .collect(Collectors.toSet());
    }

    private ServiceBoundaryCandidate singleCandidate(List<ServiceBoundaryCandidate> candidates) {
        Assertions.assertEquals(1, candidates.size());
        return candidates.getFirst();
    }

    private ServiceBoundaryCandidate candidateByName(List<ServiceBoundaryCandidate> candidates, String serviceName) {
        return candidates.stream()
                .filter(candidate -> candidate.serviceName().equals(serviceName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected candidate named " + serviceName));
    }

    private void assertCandidate(
            ServiceBoundaryCandidate candidate,
            String fixtureName,
            Path relativeRootPath,
            int confidenceScore
    ) {
        assertCandidate(candidate, fixtureName, relativeRootPath, candidate.serviceName(), confidenceScore);
    }

    private void assertCandidate(
            ServiceBoundaryCandidate candidate,
            String fixtureName,
            Path relativeRootPath,
            String serviceName,
            int confidenceScore
    ) {
        Assertions.assertEquals(serviceName, candidate.serviceName());
        Assertions.assertEquals(confidenceScore, candidate.confidenceScore());
        Assertions.assertEquals(fixture(fixtureName).resolve(relativeRootPath).normalize(), candidate.rootPath().normalize());
        Assertions.assertFalse(candidate.evidence().isEmpty());
    }

    private void assertCandidate(
            ServiceBoundaryCandidate candidate,
            Path repositoryRoot,
            Path relativeRootPath,
            String serviceName,
            int confidenceScore
    ) {
        Assertions.assertEquals(serviceName, candidate.serviceName());
        Assertions.assertEquals(confidenceScore, candidate.confidenceScore());
        Assertions.assertEquals(repositoryRoot.resolve(relativeRootPath).normalize(), candidate.rootPath().normalize());
        Assertions.assertFalse(candidate.evidence().isEmpty());
    }

    private void assertEvidenceContains(ServiceBoundaryCandidate candidate, String expectedEvidence) {
        String normalizedExpectedEvidence = normalizeEvidence(expectedEvidence);
        Assertions.assertTrue(
                candidate.evidence().stream()
                        .map(this::normalizeEvidence)
                        .anyMatch(normalizedExpectedEvidence::equals),
                () -> "Expected evidence `" + expectedEvidence + "` in " + candidate.evidence()
        );
    }

    private boolean hasEvidenceStartingWith(ServiceBoundaryCandidate candidate, String evidencePrefix) {
        return candidate.evidence().stream().anyMatch(evidence -> evidence.startsWith(evidencePrefix));
    }

    private String normalizeEvidence(String evidence) {
        return evidence.replace('\\', '/');
    }

    private String pom(String artifactId) {
        return """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>example</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0.0</version>
                </project>
                """.formatted(artifactId);
    }

    private void writeFile(Path file, String contents) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
    }
}
