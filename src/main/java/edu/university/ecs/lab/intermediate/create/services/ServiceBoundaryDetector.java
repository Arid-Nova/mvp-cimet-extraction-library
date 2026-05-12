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
import java.util.HashMap;
import java.util.HashSet;
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

        RepositoryScanIndex scanIndex = scanRepository(repositoryRoot);
        List<ScoredServiceRoot> scoredRoots = new ArrayList<>();
        for (Path possibleRoot : scanIndex.possibleServiceRoots()) {
            ScoredServiceRoot scoredRoot = score(possibleRoot, rc, scanIndex);
            if (scoredRoot.score() >= MINIMUM_SERVICE_SCORE) {
                scoredRoots.add(scoredRoot);
            }
        }

        return removeParentCandidates(scoredRoots).stream()
                .sorted(Comparator.comparing(candidate -> candidate.rootPath().toString()))
                .toList();
    }

    private RepositoryScanIndex scanRepository(Path repositoryRoot) throws IOException {
        RepositoryScanIndex scanIndex = new RepositoryScanIndex();
        Files.walkFileTree(repositoryRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(repositoryRoot) && isIgnoredDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                scanIndex.recordDirectory(dir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    scanIndex.recordFile(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        scanIndex.sortEvidence();
        return scanIndex;
    }

    private ScoredServiceRoot score(Path possibleRoot, RepositoryConfig rc, RepositoryScanIndex scanIndex) {
        int score = 0;
        Set<String> evidence = new LinkedHashSet<>();

        Optional<String> buildFile = scanIndex.firstExistingFileName(possibleRoot, BUILD_FILES);
        if (buildFile.isPresent()) {
            score += BUILD_FILE_SCORE;
            evidence.add("build file: " + buildFile.get());
        }

        if (scanIndex.hasDirectory(possibleRoot.resolve("src").resolve("main").resolve("java"))) {
            evidence.add("source tree: src/main/java");
        }

        Optional<Path> applicationConfig = scanIndex.firstApplicationConfig(possibleRoot);
        if (applicationConfig.isPresent()) {
            score += APPLICATION_CONFIG_SCORE;
            evidence.add("application config: " + displayPath(possibleRoot, applicationConfig.get()));
        }

        Optional<String> dockerfile = scanIndex.firstExistingFileName(possibleRoot, DOCKER_FILES);
        if (dockerfile.isPresent()) {
            score += DOCKERFILE_SCORE;
            evidence.add("dockerfile: " + dockerfile.get());
        }

        Optional<Path> dockerCompose = scanIndex.dockerComposeWithServices(possibleRoot);
        if (dockerCompose.isPresent()) {
            score += DOCKER_COMPOSE_SERVICE_SCORE;
            evidence.add("docker-compose services: " + displayPath(possibleRoot, dockerCompose.get()));
        }

        Optional<Path> deploymentManifest = scanIndex.findKubernetesDeployment(possibleRoot);
        if (deploymentManifest.isPresent()) {
            score += KUBERNETES_DEPLOYMENT_SCORE;
            evidence.add("kubernetes deployment: " + displayPath(possibleRoot, deploymentManifest.get()));
        }

        Optional<Path> helmChart = scanIndex.findHelmChart(possibleRoot);
        if (helmChart.isPresent()) {
            score += KUBERNETES_DEPLOYMENT_SCORE;
            evidence.add("helm chart: " + displayPath(possibleRoot, helmChart.get()));
        }

        JavaEvidence javaEvidence = scanIndex.javaEvidence(possibleRoot);
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
        List<ServiceBoundaryCandidate> candidatesByDepth = scoredRoots.stream()
                .map(ScoredServiceRoot::candidate)
                .sorted(Comparator.comparingInt(candidate -> -normalized(candidate.rootPath()).getNameCount()))
                .toList();
        Set<Path> rootsWithAcceptedChildren = new HashSet<>();
        List<ServiceBoundaryCandidate> filtered = new ArrayList<>();

        for (ServiceBoundaryCandidate candidate : candidatesByDepth) {
            Path normalizedRoot = normalized(candidate.rootPath());
            if (rootsWithAcceptedChildren.contains(normalizedRoot)) {
                continue;
            }

            filtered.add(candidate);

            Path parent = normalizedRoot.getParent();
            while (parent != null) {
                rootsWithAcceptedChildren.add(parent);
                parent = parent.getParent();
            }
        }

        return filtered;
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

            try (var reader = Files.newBufferedReader(gradleFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = GRADLE_ROOT_PROJECT_NAME.matcher(line);
                    if (matcher.find()) {
                        return asNonBlankString(matcher.group(1));
                    }
                }
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        return Optional.empty();
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

    private boolean containsKubernetesDeploymentKind(Path path) {
        try (var reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (KUBERNETES_DEPLOYMENT_KIND.matcher(line).find()) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
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

    private Optional<Path> serviceRootFromJavaSourceDirectory(Path dir) {
        if (!pathEndsWith(dir, "src", "main", "java")) {
            return Optional.empty();
        }

        Path src = dir.getParent() == null ? null : dir.getParent().getParent();
        if (src == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(src.getParent());
    }

    private Optional<Path> serviceRootFromJavaFile(Path file) {
        Path dir = file.getParent();
        while (dir != null) {
            Optional<Path> serviceRoot = serviceRootFromJavaSourceDirectory(dir);
            if (serviceRoot.isPresent()) {
                return serviceRoot;
            }
            dir = dir.getParent();
        }

        return Optional.empty();
    }

    private Optional<Path> serviceRootFromApplicationConfig(Path file) {
        String fileName = file.getFileName().toString();
        if (!APPLICATION_CONFIG_FILES.contains(fileName)) {
            return Optional.empty();
        }

        Path parent = file.getParent();
        if (parent == null) {
            return Optional.empty();
        }

        if (pathEndsWith(parent, "src", "main", "resources")) {
            Path src = parent.getParent() == null ? null : parent.getParent().getParent();
            if (src != null && src.getParent() != null) {
                return Optional.of(src.getParent());
            }
        }

        return Optional.of(parent);
    }

    private boolean isCandidateMarkerFileName(String fileName) {
        return matchesAnyFileName(fileName, BUILD_FILES)
                || matchesAnyFileName(fileName, DOCKER_FILES)
                || matchesAnyFileName(fileName, DOCKER_COMPOSE_FILES)
                || fileName.equals("Chart.yaml");
    }

    private boolean matchesAnyFileName(String fileName, List<String> expectedFileNames) {
        return expectedFileNames.stream().anyMatch(expectedFileName -> expectedFileName.equalsIgnoreCase(fileName));
    }

    private boolean isDeploymentFileName(String fileName) {
        return fileName.equals("deployment.yml") || fileName.equals("deployment.yaml");
    }

    private boolean hasDeploymentDirectoryAncestor(Path path) {
        for (Path segment : path) {
            if (DEPLOYMENT_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }

        return false;
    }

    private boolean isDirectDeploymentFile(Path root, Path path) {
        Path parent = path.getParent();
        return parent != null
                && key(parent).equals(key(root))
                && isDeploymentFileName(path.getFileName().toString());
    }

    private boolean isUnderDeploymentDirectory(Path root, Path path) {
        Optional<Path> relativePath = relativePath(root, path);
        if (relativePath.isEmpty() || relativePath.get().getNameCount() < 2) {
            return false;
        }

        return DEPLOYMENT_DIRECTORIES.contains(relativePath.get().getName(0).toString());
    }

    private Optional<Path> relativePath(Path root, Path path) {
        Path normalizedRoot = key(root);
        Path normalizedPath = key(path);
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return Optional.empty();
        }

        return Optional.of(normalizedRoot.relativize(normalizedPath));
    }

    private boolean pathEndsWith(Path path, String... names) {
        if (path.getNameCount() < names.length) {
            return false;
        }

        for (int i = 0; i < names.length; i++) {
            String pathSegment = path.getName(path.getNameCount() - names.length + i).toString();
            if (!pathSegment.equals(names[i])) {
                return false;
            }
        }

        return true;
    }

    private Path key(Path path) {
        return path.normalize();
    }

    private final class RepositoryScanIndex {
        private final Set<Path> possibleServiceRoots = new LinkedHashSet<>();
        private final Set<Path> directories = new HashSet<>();
        private final Set<Path> regularFiles = new HashSet<>();
        private final Map<Path, List<String>> fileNamesByDirectory = new HashMap<>();
        private final Set<Path> dockerComposeFilesWithServices = new HashSet<>();
        private final List<Path> deploymentManifests = new ArrayList<>();
        private final List<Path> helmCharts = new ArrayList<>();
        private final Map<Path, JavaEvidence> javaEvidenceByRoot = new HashMap<>();

        private void recordDirectory(Path dir) {
            directories.add(key(dir));
            serviceRootFromJavaSourceDirectory(dir).ifPresent(this::addPossibleServiceRoot);

            Path parent = dir.getParent();
            if (parent != null && DEPLOYMENT_DIRECTORIES.contains(dir.getFileName().toString())) {
                addPossibleServiceRoot(parent);
            }
        }

        private void recordFile(Path file) throws IOException {
            Path parent = file.getParent();
            String fileName = file.getFileName().toString();

            regularFiles.add(key(file));
            if (parent != null) {
                fileNamesByDirectory.computeIfAbsent(key(parent), ignored -> new ArrayList<>()).add(fileName);

                if (isCandidateMarkerFileName(fileName)) {
                    addPossibleServiceRoot(parent);
                }
            }

            serviceRootFromApplicationConfig(file).ifPresent(this::addPossibleServiceRoot);

            if (DOCKER_COMPOSE_FILES.contains(fileName) && yamlHasTopLevelServices(file)) {
                dockerComposeFilesWithServices.add(key(file));
            }
            if (isYamlFile(file)
                    && (isDeploymentFileName(fileName) || hasDeploymentDirectoryAncestor(file))
                    && containsKubernetesDeploymentKind(file)) {
                deploymentManifests.add(file);
            }
            if (fileName.equals("Chart.yaml")) {
                helmCharts.add(file);
            }
            if (fileName.endsWith(".java")) {
                recordJavaEvidence(file);
            }
        }

        private void recordJavaEvidence(Path file) {
            Optional<Path> serviceRoot = serviceRootFromJavaFile(file);
            if (serviceRoot.isEmpty()) {
                return;
            }

            addPossibleServiceRoot(serviceRoot.get());
            Path serviceRootKey = key(serviceRoot.get());
            JavaEvidence evidence = javaEvidenceByRoot.getOrDefault(serviceRootKey, JavaEvidence.empty());
            if (evidence.isComplete()) {
                return;
            }

            javaEvidenceByRoot.put(serviceRootKey, scanJavaFile(file, evidence));
        }

        private JavaEvidence scanJavaFile(Path file, JavaEvidence evidence) {
            JavaEvidence currentEvidence = evidence;
            try (var reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!currentEvidence.hasSpringBootApplication() && line.contains("@SpringBootApplication")) {
                        currentEvidence = currentEvidence.withSpringBootApplication(file);
                    }
                    if (!currentEvidence.hasController()
                            && (line.contains("@RestController") || line.contains("@Controller"))) {
                        currentEvidence = currentEvidence.withController(file);
                    }
                    if (!currentEvidence.hasRequestMapping() && line.contains("@RequestMapping")) {
                        currentEvidence = currentEvidence.withRequestMapping(file);
                    }

                    if (currentEvidence.isComplete()) {
                        break;
                    }
                }
            } catch (IOException e) {
                return evidence;
            }

            return currentEvidence;
        }

        private void sortEvidence() {
            deploymentManifests.sort(Comparator.comparing(Path::toString));
            helmCharts.sort(Comparator.comparing(Path::toString));
        }

        private List<Path> possibleServiceRoots() {
            return new ArrayList<>(possibleServiceRoots);
        }

        private boolean hasDirectory(Path dir) {
            return directories.contains(key(dir));
        }

        private Optional<Path> firstApplicationConfig(Path possibleRoot) {
            return orderedApplicationConfigPaths(possibleRoot).stream()
                    .filter(this::isRegularFile)
                    .findFirst();
        }

        private Optional<Path> dockerComposeWithServices(Path possibleRoot) {
            for (String composeFileName : DOCKER_COMPOSE_FILES) {
                Path composeFile = possibleRoot.resolve(composeFileName);
                if (dockerComposeFilesWithServices.contains(key(composeFile))) {
                    return Optional.of(composeFile);
                }
            }

            return Optional.empty();
        }

        private Optional<Path> findKubernetesDeployment(Path possibleRoot) {
            return deploymentManifests.stream()
                    .filter(path -> isDirectDeploymentFile(possibleRoot, path) || isUnderDeploymentDirectory(possibleRoot, path))
                    .findFirst();
        }

        private Optional<Path> findHelmChart(Path possibleRoot) {
            return helmCharts.stream()
                    .filter(path -> {
                        Path parent = path.getParent();
                        return (parent != null && key(parent).equals(key(possibleRoot)))
                                || isUnderDeploymentDirectory(possibleRoot, path);
                    })
                    .findFirst();
        }

        private JavaEvidence javaEvidence(Path possibleRoot) {
            return javaEvidenceByRoot.getOrDefault(key(possibleRoot), JavaEvidence.empty());
        }

        private Optional<String> firstExistingFileName(Path dir, List<String> fileNames) {
            List<String> existingFileNames = fileNamesByDirectory.getOrDefault(key(dir), List.of());

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

            return Optional.empty();
        }

        private boolean isRegularFile(Path path) {
            return regularFiles.contains(key(path));
        }

        private void addPossibleServiceRoot(Path path) {
            possibleServiceRoots.add(path.normalize());
        }
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

        private boolean isComplete() {
            return hasSpringBootApplication() && hasController() && hasRequestMapping();
        }
    }
}
