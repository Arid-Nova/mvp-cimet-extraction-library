package edu.university.ecs.lab.intermediate.create.services;

import edu.university.ecs.lab.common.config.RepositoryConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServiceBoundaryDetector {
    private static final int MINIMUM_SERVICE_SCORE = 7;
    private static final int BUILD_FILE_SCORE = 5;
    private static final int SPRING_BOOT_APPLICATION_SCORE = 5;
    private static final int DOCKERFILE_SCORE = 4;
    private static final int APPLICATION_CONFIG_SCORE = 3;
    private static final int KUBERNETES_DEPLOYMENT_SCORE = 3;
    private static final int CONTROLLER_SCORE = 2;
    private static final int DOCKER_COMPOSE_SERVICE_SCORE = 2;

    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final List<String> DOCKER_FILES = List.of("Dockerfile", "DockerFile");
    private static final List<String> DOCKER_COMPOSE_FILES = List.of("docker-compose.yml", "docker-compose.yaml");
    private static final List<String> APPLICATION_CONFIG_FILES = List.of(
            "application.yml",
            "application.yaml",
            "application.properties"
    );
    private static final List<String> GRADLE_NAME_FILES = List.of(
            "settings.gradle",
            "settings.gradle.kts",
            "build.gradle",
            "build.gradle.kts"
    );
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git",
            ".gradle",
            ".idea",
            ".mvn",
            "build",
            "node_modules",
            "out",
            "target"
    );
    private static final Set<String> DEPLOYMENT_DIRECTORIES = Set.of(
            "chart",
            "charts",
            "deploy",
            "deployment",
            "deployments",
            "helm",
            "k8s",
            "kubernetes"
    );
    private static final Pattern GRADLE_ROOT_PROJECT_NAME = Pattern.compile(
            "(?m)^\\s*rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern KUBERNETES_DEPLOYMENT_KIND = Pattern.compile(
            "(?m)^\\s*kind\\s*:\\s*Deployment\\s*$"
    );

    public List<ServiceBoundaryCandidate> detect(String repositoryPath, RepositoryConfig rc) throws IOException {
        Path repositoryRoot = Path.of(repositoryPath);

        if (!Files.exists(repositoryRoot) || !Files.isDirectory(repositoryRoot)) {
            throw new IOException("Repository path must exist and be directory");
        }

        List<ScoredServiceRoot> scoredRoots = new ArrayList<>();
        for (Path possibleRoot : findPossibleServiceRoots(repositoryRoot)) {
            ScoredServiceRoot scoredRoot = score(possibleRoot, rc);
            if (scoredRoot.score() >= MINIMUM_SERVICE_SCORE) {
                scoredRoots.add(scoredRoot);
            }
        }

        return removeParentCandidates(scoredRoots).stream()
                .sorted(Comparator.comparing(candidate -> candidate.rootPath().toString()))
                .toList();
    }

    private List<Path> findPossibleServiceRoots(Path repositoryRoot) throws IOException {
        List<Path> serviceRoots = new ArrayList<>();

        Files.walkFileTree(repositoryRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(repositoryRoot) && isIgnoredDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (hasBoundaryMarker(dir)) {
                    serviceRoots.add(dir);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return serviceRoots;
    }

    private boolean hasBoundaryMarker(Path dir) {
        return hasAnyFile(dir, BUILD_FILES)
                || hasAnyFile(dir, DOCKER_FILES)
                || hasAnyFile(dir, DOCKER_COMPOSE_FILES)
                || hasAnyApplicationConfig(dir)
                || Files.isDirectory(dir.resolve("src").resolve("main").resolve("java"))
                || Files.exists(dir.resolve("Chart.yaml"))
                || containsDeploymentDirectory(dir);
    }

    private ScoredServiceRoot score(Path possibleRoot, RepositoryConfig rc) throws IOException {
        int score = 0;
        Set<String> evidence = new LinkedHashSet<>();

        Optional<String> buildFile = firstExistingFileName(possibleRoot, BUILD_FILES);
        if (buildFile.isPresent()) {
            score += BUILD_FILE_SCORE;
            evidence.add("build file: " + buildFile.get());
        }

        if (Files.isDirectory(possibleRoot.resolve("src").resolve("main").resolve("java"))) {
            evidence.add("source tree: src/main/java");
        }

        Optional<Path> applicationConfig = firstApplicationConfig(possibleRoot);
        if (applicationConfig.isPresent()) {
            score += APPLICATION_CONFIG_SCORE;
            evidence.add("application config: " + displayPath(possibleRoot, applicationConfig.get()));
        }

        Optional<String> dockerfile = firstExistingFileName(possibleRoot, DOCKER_FILES);
        if (dockerfile.isPresent()) {
            score += DOCKERFILE_SCORE;
            evidence.add("dockerfile: " + dockerfile.get());
        }

        Optional<Path> dockerCompose = dockerComposeWithServices(possibleRoot);
        if (dockerCompose.isPresent()) {
            score += DOCKER_COMPOSE_SERVICE_SCORE;
            evidence.add("docker-compose services: " + displayPath(possibleRoot, dockerCompose.get()));
        }

        Optional<Path> deploymentManifest = findKubernetesDeployment(possibleRoot);
        if (deploymentManifest.isPresent()) {
            score += KUBERNETES_DEPLOYMENT_SCORE;
            evidence.add("kubernetes deployment: " + displayPath(possibleRoot, deploymentManifest.get()));
        }

        Optional<Path> helmChart = findHelmChart(possibleRoot);
        if (helmChart.isPresent()) {
            score += KUBERNETES_DEPLOYMENT_SCORE;
            evidence.add("helm chart: " + displayPath(possibleRoot, helmChart.get()));
        }

        JavaEvidence javaEvidence = findJavaEvidence(possibleRoot);
        if (javaEvidence.hasSpringBootApplication()) {
            score += SPRING_BOOT_APPLICATION_SCORE;
            evidence.add("SpringBootApplication: " + displayPath(possibleRoot, javaEvidence.springBootApplicationPath()));
        }
        if (javaEvidence.hasController()) {
            score += CONTROLLER_SCORE;
            evidence.add("controller annotation: " + displayPath(possibleRoot, javaEvidence.controllerPath()));
        }
        if (javaEvidence.hasRequestMapping()) {
            evidence.add("RequestMapping: " + displayPath(possibleRoot, javaEvidence.requestMappingPath()));
        }

        String serviceName = inferServiceName(possibleRoot, rc);
        return new ScoredServiceRoot(new ServiceBoundaryCandidate(possibleRoot, serviceName, score, evidence), score);
    }

    private List<ServiceBoundaryCandidate> removeParentCandidates(List<ScoredServiceRoot> scoredRoots) {
        List<ServiceBoundaryCandidate> candidates = scoredRoots.stream()
                .map(ScoredServiceRoot::candidate)
                .toList();
        List<ServiceBoundaryCandidate> filtered = new ArrayList<>();

        for (ServiceBoundaryCandidate candidate : candidates) {
            boolean hasAcceptedChild = candidates.stream()
                    .anyMatch(other -> !other.rootPath().equals(candidate.rootPath())
                            && normalized(other.rootPath()).startsWith(normalized(candidate.rootPath())));
            if (!hasAcceptedChild) {
                filtered.add(candidate);
            }
        }

        return filtered;
    }

    private JavaEvidence findJavaEvidence(Path possibleRoot) throws IOException {
        Path sourceRoot = possibleRoot.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(sourceRoot)) {
            return JavaEvidence.empty();
        }

        JavaEvidence evidence = JavaEvidence.empty();
        try (var paths = Files.walk(sourceRoot)) {
            for (Path path : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String contents = Files.readString(path);
                if (!evidence.hasSpringBootApplication() && contents.contains("@SpringBootApplication")) {
                    evidence = evidence.withSpringBootApplication(path);
                }
                if (!evidence.hasController()
                        && (contents.contains("@RestController") || contents.contains("@Controller"))) {
                    evidence = evidence.withController(path);
                }
                if (!evidence.hasRequestMapping() && contents.contains("@RequestMapping")) {
                    evidence = evidence.withRequestMapping(path);
                }

                if (evidence.hasSpringBootApplication()
                        && evidence.hasController()
                        && evidence.hasRequestMapping()) {
                    break;
                }
            }
        }

        return evidence;
    }

    private String inferServiceName(Path possibleRoot, RepositoryConfig rc) {
        return readSpringApplicationName(possibleRoot)
                .or(() -> readMavenArtifactId(possibleRoot.resolve("pom.xml")))
                .or(() -> readGradleRootProjectName(possibleRoot))
                .or(() -> Optional.ofNullable(possibleRoot.getFileName()).map(Path::toString))
                .filter(name -> !name.isBlank())
                .orElse(rc.getRepoName());
    }

    private Optional<String> readSpringApplicationName(Path possibleRoot) {
        for (Path applicationConfig : orderedApplicationConfigPaths(possibleRoot)) {
            if (!Files.isRegularFile(applicationConfig)) {
                continue;
            }

            Optional<String> serviceName = applicationConfig.getFileName().toString().endsWith(".properties")
                    ? readSpringApplicationNameFromProperties(applicationConfig)
                    : readSpringApplicationNameFromYaml(applicationConfig);
            if (serviceName.isPresent()) {
                return serviceName;
            }
        }

        return Optional.empty();
    }

    private Optional<String> readSpringApplicationNameFromYaml(Path applicationConfig) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        try (InputStream inputStream = Files.newInputStream(applicationConfig)) {
            Object yamlRoot = yaml.load(inputStream);
            return findSpringApplicationName(yamlRoot);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> findSpringApplicationName(Object yamlRoot) {
        if (!(yamlRoot instanceof Map<?, ?> rootMap)) {
            return Optional.empty();
        }

        Optional<String> flatName = asNonBlankString(rootMap.get("spring.application.name"));
        if (flatName.isPresent()) {
            return flatName;
        }

        Object spring = rootMap.get("spring");
        if (!(spring instanceof Map<?, ?> springMap)) {
            return Optional.empty();
        }

        Object application = springMap.get("application");
        if (!(application instanceof Map<?, ?> applicationMap)) {
            return Optional.empty();
        }

        return asNonBlankString(applicationMap.get("name"));
    }

    private Optional<String> readSpringApplicationNameFromProperties(Path applicationConfig) {
        Properties properties = new Properties();

        try (Reader reader = Files.newBufferedReader(applicationConfig)) {
            properties.load(reader);
            return asNonBlankString(properties.getProperty("spring.application.name"));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<String> readMavenArtifactId(Path pomPath) {
        if (!Files.isRegularFile(pomPath)) {
            return Optional.empty();
        }

        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = documentBuilderFactory.newDocumentBuilder().parse(pomPath.toFile());
            document.getDocumentElement().normalize();

            Element project = document.getDocumentElement();
            for (int i = 0; i < project.getChildNodes().getLength(); i++) {
                Node child = project.getChildNodes().item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE && "artifactId".equals(child.getNodeName())) {
                    return asNonBlankString(child.getTextContent());
                }
            }
        } catch (Exception e) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private Optional<String> readGradleRootProjectName(Path possibleRoot) {
        for (String fileName : GRADLE_NAME_FILES) {
            Path gradleFile = possibleRoot.resolve(fileName);
            if (!Files.isRegularFile(gradleFile)) {
                continue;
            }

            try {
                Matcher matcher = GRADLE_ROOT_PROJECT_NAME.matcher(Files.readString(gradleFile));
                if (matcher.find()) {
                    return asNonBlankString(matcher.group(1));
                }
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

    private Optional<Path> firstApplicationConfig(Path possibleRoot) {
        return orderedApplicationConfigPaths(possibleRoot).stream()
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private List<Path> orderedApplicationConfigPaths(Path possibleRoot) {
        List<Path> configPaths = new ArrayList<>();
        Path resourcesRoot = possibleRoot.resolve("src").resolve("main").resolve("resources");

        for (String configFile : APPLICATION_CONFIG_FILES) {
            configPaths.add(resourcesRoot.resolve(configFile));
        }
        for (String configFile : APPLICATION_CONFIG_FILES) {
            configPaths.add(possibleRoot.resolve(configFile));
        }

        return configPaths;
    }

    private boolean hasAnyApplicationConfig(Path dir) {
        return firstApplicationConfig(dir).isPresent();
    }

    private Optional<Path> dockerComposeWithServices(Path possibleRoot) {
        for (String composeFileName : DOCKER_COMPOSE_FILES) {
            Path composeFile = possibleRoot.resolve(composeFileName);
            if (!Files.isRegularFile(composeFile)) {
                continue;
            }

            if (yamlHasTopLevelServices(composeFile)) {
                return Optional.of(composeFile);
            }
        }

        return Optional.empty();
    }

    private boolean yamlHasTopLevelServices(Path composeFile) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

        try (InputStream inputStream = Files.newInputStream(composeFile)) {
            Object yamlRoot = yaml.load(inputStream);
            if (yamlRoot instanceof Map<?, ?> map) {
                Object services = map.get("services");
                return services instanceof Map<?, ?> serviceMap && !serviceMap.isEmpty();
            }
        } catch (Exception e) {
            return false;
        }

        return false;
    }

    private Optional<Path> findKubernetesDeployment(Path possibleRoot) throws IOException {
        for (Path root : deploymentSearchRoots(possibleRoot)) {
            Optional<Path> deployment = findDeploymentManifestUnder(root);
            if (deployment.isPresent()) {
                return deployment;
            }
        }

        return Optional.empty();
    }

    private Optional<Path> findDeploymentManifestUnder(Path root) throws IOException {
        if (!Files.exists(root)) {
            return Optional.empty();
        }

        if (Files.isRegularFile(root) && isYamlFile(root) && containsKubernetesDeploymentKind(root)) {
            return Optional.of(root);
        }

        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }

        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isYamlFile)
                    .filter(this::containsKubernetesDeploymentKind)
                    .findFirst();
        }
    }

    private List<Path> deploymentSearchRoots(Path possibleRoot) {
        List<Path> roots = new ArrayList<>();
        roots.add(possibleRoot.resolve("deployment.yml"));
        roots.add(possibleRoot.resolve("deployment.yaml"));

        for (String deploymentDirectory : DEPLOYMENT_DIRECTORIES) {
            roots.add(possibleRoot.resolve(deploymentDirectory));
        }

        return roots;
    }

    private Optional<Path> findHelmChart(Path possibleRoot) throws IOException {
        Path directChart = possibleRoot.resolve("Chart.yaml");
        if (Files.isRegularFile(directChart)) {
            return Optional.of(directChart);
        }

        for (String deploymentDirectory : DEPLOYMENT_DIRECTORIES) {
            Path root = possibleRoot.resolve(deploymentDirectory);
            if (!Files.isDirectory(root)) {
                continue;
            }

            try (var paths = Files.walk(root)) {
                Optional<Path> chart = paths.filter(path -> path.getFileName().toString().equals("Chart.yaml"))
                        .findFirst();
                if (chart.isPresent()) {
                    return chart;
                }
            }
        }

        return Optional.empty();
    }

    private boolean containsKubernetesDeploymentKind(Path path) {
        try {
            return KUBERNETES_DEPLOYMENT_KIND.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean containsDeploymentDirectory(Path dir) {
        return DEPLOYMENT_DIRECTORIES.stream()
                .map(dir::resolve)
                .anyMatch(Files::exists);
    }

    private Optional<String> firstExistingFileName(Path dir, List<String> fileNames) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }

        try (var paths = Files.list(dir)) {
            List<String> existingFileNames = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();

            for (String fileName : fileNames) {
                Optional<String> exactMatch = existingFileNames.stream()
                        .filter(existingFileName -> existingFileName.equals(fileName))
                        .findFirst();
                if (exactMatch.isPresent()) {
                    return exactMatch;
                }
            }

            for (String fileName : fileNames) {
                Optional<String> caseInsensitiveMatch = existingFileNames.stream()
                        .filter(existingFileName -> existingFileName.equalsIgnoreCase(fileName))
                        .findFirst();
                if (caseInsensitiveMatch.isPresent()) {
                    return caseInsensitiveMatch;
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private boolean hasAnyFile(Path dir, List<String> fileNames) {
        return firstExistingFileName(dir, fileNames).isPresent();
    }

    private boolean isYamlFile(Path path) {
        String fileName = path.getFileName().toString();
        return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
    }

    private boolean isIgnoredDirectory(Path dir) {
        return Optional.ofNullable(dir.getFileName())
                .map(Path::toString)
                .filter(IGNORED_DIRECTORIES::contains)
                .isPresent();
    }

    private Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private String displayPath(Path root, Path path) {
        try {
            return root.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    private Optional<String> asNonBlankString(Object value) {
        if (value == null) {
            return Optional.empty();
        }

        String stringValue = value.toString().trim();
        if (stringValue.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(stringValue);
    }

    private record ScoredServiceRoot(ServiceBoundaryCandidate candidate, int score) {
    }

    private record JavaEvidence(
            Path springBootApplicationPath,
            Path controllerPath,
            Path requestMappingPath
    ) {
        private static JavaEvidence empty() {
            return new JavaEvidence(null, null, null);
        }

        private JavaEvidence withSpringBootApplication(Path path) {
            return new JavaEvidence(path, controllerPath, requestMappingPath);
        }

        private JavaEvidence withController(Path path) {
            return new JavaEvidence(springBootApplicationPath, path, requestMappingPath);
        }

        private JavaEvidence withRequestMapping(Path path) {
            return new JavaEvidence(springBootApplicationPath, controllerPath, path);
        }

        private boolean hasSpringBootApplication() {
            return springBootApplicationPath != null;
        }

        private boolean hasController() {
            return controllerPath != null;
        }

        private boolean hasRequestMapping() {
            return requestMappingPath != null;
        }
    }
}
