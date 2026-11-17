package edu.university.ecs.lab.intermediate.create.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Locale;
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

    // Java's own build markers, plus one exact-filename manifest per non-Java language we now parse
    // (#16). C#'s project file (*.csproj) is named after the project, not a fixed name, so it doesn't
    // fit this exact-filename list; it's matched separately by extension (see firstFileWithExtension).
    private static final List<String> BUILD_FILES = List.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "package.json", "go.mod", "requirements.txt", "pyproject.toml"
    );
    private static final List<String> JAVA_BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");
    // Fallback evidence a bare requirements.txt/pyproject.toml directory is a runnable app, not just a
    // test harness, for the rare case it has no Dockerfile of its own.
    private static final List<String> PYTHON_ENTRY_POINT_FILES = List.of(
            "app.py", "main.py", "manage.py", "wsgi.py", "asgi.py", "run.py", "server.py"
    );
    // A Dockerized acceptance/integration-test harness (e.g. a polling script hitting well-known
    // endpoints to wait for startup, geoserver-cloud's acceptance_tests/) has its own requirements.txt
    // AND its own Dockerfile - the same two signals a real Python service has - so the Dockerfile
    // fallback above doesn't catch it (#16 follow-up: a RestCall was wrongly attributed to this
    // directory as if it were a real outbound call from a real service). Directory name is the only
    // remaining signal: these conventional test-harness names are vanishingly unlikely to also be a
    // real deployable service's directory name.
    private static final Set<String> PYTHON_TEST_HARNESS_DIRECTORY_NAMES = Set.of(
            "test", "tests", "acceptance_tests", "acceptance-tests", "e2e", "e2e_tests", "e2e-tests",
            "integration_tests", "integration-tests"
    );
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
    // A flat multi-module Gradle build (one root build.gradle/settings.gradle, no build.gradle in any
    // individual module directory - e.g. pravega) declares its modules only in settings.gradle's
    // include(...) call, as colon-separated paths ('shared:rest' -> shared/rest). Matches any quoted,
    // dot-free, slash-free identifier path; harmless over-matching (e.g. a plugin id string elsewhere in
    // the file) is inert since it's only ever checked against paths that independently already exist.
    private static final Pattern GRADLE_INCLUDED_MODULE = Pattern.compile(
            "['\"](:?[A-Za-z0-9_][A-Za-z0-9_-]*(?::[A-Za-z0-9_][A-Za-z0-9_-]*)*)['\"]"
    );
    private static final List<String> SETTINGS_GRADLE_FILES = List.of("settings.gradle", "settings.gradle.kts");

    public List<ServiceBoundaryCandidate> detect(String repositoryPath, RepositoryConfig rc) throws IOException {
        Path repositoryRoot = Path.of(repositoryPath);

        if (!Files.exists(repositoryRoot) || !Files.isDirectory(repositoryRoot)) {
            throw new IOException("Repository path must exist and be directory");
        }

        RepositoryScanIndex scanIndex = scanRepository(repositoryRoot);
        List<ScoredServiceRoot> scoredRoots = new ArrayList<>();
        Set<Path> weaklyQualifiedRoots = new HashSet<>();
        Set<Path> imageBuildRoots = new HashSet<>();
        for (Path possibleRoot : scanIndex.possibleServiceRoots()) {
            ScoredServiceRoot scoredRoot = score(possibleRoot, rc, scanIndex);
            if (isNestedImageBuild(possibleRoot, repositoryRoot, scanIndex)) {
                scoredRoots.add(scoredRoot);
                imageBuildRoots.add(normalized(possibleRoot));
                continue;
            }
            Qualification qualification = qualification(possibleRoot, scanIndex, scoredRoot);
            if (qualification.qualifies()) {
                scoredRoots.add(scoredRoot);
                if (qualification.weak()) {
                    weaklyQualifiedRoots.add(normalized(possibleRoot));
                }
            }
        }

        return removeParentCandidates(scoredRoots, weaklyQualifiedRoots).stream()
                .filter(candidate -> !isInsideAnother(normalized(candidate.rootPath()), imageBuildRoots))
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
        scanIndex.recordGradleIncludedModules(parseGradleIncludedModulePaths(repositoryRoot));
        return scanIndex;
    }

    /**
     * Parses the repository root's settings.gradle(.kts), if any, for every module path given to
     * {@code include(...)} (e.g. {@code include 'shared:rest', 'controller'}), converting each
     * colon-separated Gradle path into the filesystem path it designates ('shared:rest' -> shared/rest).
     */
    private Set<Path> parseGradleIncludedModulePaths(Path repositoryRoot) {
        Set<Path> modulePaths = new HashSet<>();
        for (String fileName : SETTINGS_GRADLE_FILES) {
            Path settingsFile = repositoryRoot.resolve(fileName);
            if (!Files.isRegularFile(settingsFile)) {
                continue;
            }
            try {
                String content = Files.readString(settingsFile);
                Matcher matcher = GRADLE_INCLUDED_MODULE.matcher(content);
                while (matcher.find()) {
                    String modulePath = matcher.group(1);
                    if (modulePath.startsWith(":")) {
                        modulePath = modulePath.substring(1);
                    }
                    Path resolved = repositoryRoot;
                    for (String segment : modulePath.split(":")) {
                        resolved = resolved.resolve(segment);
                    }
                    modulePaths.add(resolved.normalize());
                }
            } catch (IOException e) {
                // Best effort - an unreadable settings.gradle just contributes no extra module paths.
            }
        }
        return modulePaths;
    }

    /**
     * A monorepo product directory that is its own Gradle build (its own settings.gradle) and builds its
     * own container image (any Dockerfile variant - spinnaker ships Dockerfile.slim/.ubuntu/.compile) is
     * one deployable service; the modules its settings.gradle includes (gate-web, gate-core, ...) are
     * its internals, not separate services. The repository root is excluded: a root build is already
     * judged by the ordinary rules, which handle both single-service repos and multi-module aggregators.
     */
    private boolean isNestedImageBuild(Path possibleRoot, Path repositoryRoot, RepositoryScanIndex scanIndex) {
        if (normalized(possibleRoot).equals(normalized(repositoryRoot))) {
            return false;
        }
        return (scanIndex.hasOwnFile(possibleRoot, "settings.gradle") || scanIndex.hasOwnFile(possibleRoot, "settings.gradle.kts"))
                && scanIndex.hasOwnFileStartingWith(possibleRoot, "dockerfile");
    }

    private static boolean isInsideAnother(Path root, Set<Path> containers) {
        for (Path container : containers) {
            if (!root.equals(container) && root.startsWith(container)) {
                return true;
            }
        }
        return false;
    }

    private ScoredServiceRoot score(Path possibleRoot, RepositoryConfig rc, RepositoryScanIndex scanIndex) {
        int score = 0;
        Set<String> evidence = new LinkedHashSet<>();

        Optional<String> buildFile = scanIndex.firstExistingFileName(possibleRoot, BUILD_FILES)
                .or(() -> scanIndex.firstFileWithExtension(possibleRoot, ".csproj"));
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
        if (javaEvidence.hasJaxRsResource()) {
            score += CONTROLLER_SCORE;
            evidence.add("JAX-RS @Path resource: " + displayPath(possibleRoot, javaEvidence.jaxRsPath()));
        }
        if (javaEvidence.hasProgrammaticRoute()) {
            score += CONTROLLER_SCORE;
            evidence.add("programmatic route mapping: " + displayPath(possibleRoot, javaEvidence.programmaticRoutePath()));
        }

        String serviceName = inferServiceName(possibleRoot, rc);
        return new ScoredServiceRoot(new ServiceBoundaryCandidate(possibleRoot, serviceName, score, evidence), score);
    }

    /**
     * Decides whether a candidate root is actually a service boundary, using explicit per-ecosystem
     * rules instead of an additive score crossing an arbitrary threshold (#16 follow-up). A manifest
     * file is, by each language's own build tooling, already the definition of one buildable/deployable
     * unit in Go, Python, C#, and (with an entry point) Node - no further corroboration is needed there.
     * Java/Maven/Gradle is the one ecosystem with a real false-positive pattern this must still guard
     * against: multi-module repos routinely split ONE service into several submodules (e.g. a shared
     * utility library with its own pom.xml - see {@code ignoresSharedLibrariesWithoutServiceEvidence}),
     * so a Java build file alone isn't sufficient; it still requires some independent-deployability
     * signal. The score itself is left untouched (still reported on the candidate) since it's informative,
     * but it no longer gates acceptance except for the rare candidate with no recognized build file at
     * all, where the original scored behavior is kept unchanged.
     */
    private boolean qualifiesAsService(Path possibleRoot, RepositoryScanIndex scanIndex, ScoredServiceRoot scoredRoot) {
        return qualification(possibleRoot, scanIndex, scoredRoot).qualifies();
    }

    /**
     * Same acceptance rules as before, but also reports whether the acceptance was on WEAK evidence -
     * a Java root whose only sign of being its own deployable unit is a stray application.yml/.properties
     * nearby (no Dockerfile, no @SpringBootApplication, no controller of its own), or a root accepted
     * purely via the generic score fallback. Weak roots are exactly the "this is probably just an
     * aggregator/parent POM, not a real service" case {@link #removeParentCandidates} should still
     * suppress in favor of a qualifying child; a root with STRONG direct evidence (its own Dockerfile,
     * @SpringBootApplication, controller, docker-compose/k8s/helm, or any of the other ecosystems' own
     * entry-point rules) is independently deployable regardless of what's nested under it (#16 follow-up -
     * see the openlmis-referencedata bug this distinction fixes).
     */
    private Qualification qualification(Path possibleRoot, RepositoryScanIndex scanIndex, ScoredServiceRoot scoredRoot) {
        // A directory can legitimately carry more than one manifest at once - e.g. an ASP.NET Core app
        // with a Vite/webpack frontend bundler has both its own .csproj AND its own package.json in the
        // same folder, and only one of them is the "real" backend entry point. Each manifest type found
        // here is judged on its own ecosystem's rule; the directory qualifies if ANY of them does -
        // checking only whichever manifest happens to be first in a fixed lookup order (the original,
        // buggy version of this method) wrongly rejects that kind of directory.
        boolean hasAnyBuildFile = false;

        // A module declared in the repo root's settings.gradle include(...) list is just as much "one
        // buildable unit" as a directory with its own build.gradle/pom.xml - the flat multi-module Gradle
        // convention (one root build.gradle, no per-module manifest - e.g. pravega) just defines modules
        // differently. Still requires the same strong-evidence corroboration below, same as any other
        // Java root, to keep rejecting plain shared libraries that happen to be registered modules too.
        if (scanIndex.firstExistingFileName(possibleRoot, JAVA_BUILD_FILES).isPresent()
                || scanIndex.isDeclaredGradleModule(possibleRoot)) {
            hasAnyBuildFile = true;
            JavaEvidence javaEvidence = scanIndex.javaEvidence(possibleRoot);
            boolean hasOwnCodeEvidence = javaEvidence.hasSpringBootApplication()
                    || javaEvidence.hasController()
                    || javaEvidence.hasJaxRsResource()
                    || javaEvidence.hasProgrammaticRoute();
            // Infra evidence (Dockerfile/docker-compose/k8s/helm) alone is strong ONLY when this root
            // also has its own src/main/java - otherwise this is routinely just a multi-module
            // aggregator's root docker-compose.yml bringing up every submodule for local dev (xs2a,
            // ccpay-payment-app: a bare pom-packaging/no-source root whose own docker-compose.yml lists
            // every submodule's service). Without this guard such a root both qualifies AND, since its
            // file walk covers the whole repo, produces a near-duplicate of every submodule's endpoints
            // under the wrong (repo-root) service name - a real double-counting bug, not a different
            // deployable unit. A root that legitimately pairs infra evidence with no Java code of its
            // own (a pure Docker/k8s wrapper with a Dockerfile but no src/main/java) still correctly
            // fails to qualify here - it has nothing to extract anyway.
            boolean hasOwnSourceTree = scanIndex.hasDirectory(
                    possibleRoot.resolve("src").resolve("main").resolve("java"));
            boolean hasInfraEvidence = hasOwnSourceTree && (
                    scanIndex.firstExistingFileName(possibleRoot, DOCKER_FILES).isPresent()
                    || scanIndex.dockerComposeWithServices(possibleRoot).isPresent()
                    || scanIndex.findKubernetesDeployment(possibleRoot).isPresent()
                    || scanIndex.findHelmChart(possibleRoot).isPresent());
            if (hasOwnCodeEvidence || hasInfraEvidence) {
                return Qualification.strongMatch();
            }
            if (scanIndex.firstApplicationConfig(possibleRoot).isPresent()) {
                return Qualification.weakMatch();
            }
        }

        if (scanIndex.hasOwnFile(possibleRoot, "package.json")) {
            hasAnyBuildFile = true;
            // package.json is also used for pure config/workspace-root/tooling packages (or, as here, a
            // frontend asset bundler sitting next to a real backend) that are never run on their own -
            // require an actual entry point before treating it as its own deployable service.
            boolean hasDeploymentEvidence = scanIndex.firstExistingFileName(possibleRoot, DOCKER_FILES).isPresent()
                    || scanIndex.dockerComposeWithServices(possibleRoot).isPresent()
                    || scanIndex.findKubernetesDeployment(possibleRoot).isPresent()
                    || scanIndex.findHelmChart(possibleRoot).isPresent();
            if (hasNodeEntryPoint(possibleRoot.resolve("package.json"), hasDeploymentEvidence)) {
                return Qualification.strongMatch();
            }
        }

        Optional<String> csproj = scanIndex.firstFileWithExtension(possibleRoot, ".csproj");
        if (csproj.isPresent()) {
            hasAnyBuildFile = true;
            // Unlike pom.xml/build.gradle, a .csproj is already one buildable unit on its own - except a
            // test project (xUnit/NUnit/MSTest) or a plain shared-code library (e.g. a "TestUtils" or
            // "Infrastructure.Messaging" project referenced by other projects), neither of which is
            // independently deployable. Both share one observable trait a real service (web API or
            // console/worker) always has and these never do: an actual Program.cs entry point in the
            // project's own directory.
            if (scanIndex.hasOwnFile(possibleRoot, "Program.cs")) {
                return Qualification.strongMatch();
            }
        }

        if (scanIndex.hasOwnFile(possibleRoot, "go.mod")) {
            // A Go module is already, definitionally, one buildable/deployable unit - no multi-module
            // ambiguity to guard against the way Java's convention requires.
            return Qualification.strongMatch();
        }

        if (scanIndex.firstFileWithExtension(possibleRoot, ".go").isPresent()) {
            hasAnyBuildFile = true;
            // Pre-Go-modules repos (GOPATH era, predating go.mod - e.g. sock-shop's catalogue/payment/user,
            // all from 2017) have no reliable manifest file at all: some vendor a glide.yaml/Gopkg.toml,
            // some have neither. The one thing every real instance actually observed has in common is its
            // own Dockerfile (directly, or via a docker-compose service pointing at one) - the same
            // fallback signal Python already uses for its own "no go.mod equivalent" case (#16 follow-up).
            if (scanIndex.firstExistingFileName(possibleRoot, DOCKER_FILES).isPresent()
                    || scanIndex.dockerComposeWithServices(possibleRoot).isPresent()) {
                return Qualification.strongMatch();
            }
        }

        if (scanIndex.hasOwnFile(possibleRoot, "requirements.txt") || scanIndex.hasOwnFile(possibleRoot, "pyproject.toml")) {
            hasAnyBuildFile = true;
            // Unlike go.mod/.csproj, a bare requirements.txt/pyproject.toml is NOT a reliable sign of a
            // runnable service by itself - a plain test-harness directory (pytest + its own
            // requirements.txt, no application code at all) looks identical by that measure alone. Every
            // real Python service actually observed across our ground-truth repos has its own Dockerfile;
            // fall back to a recognizable entry-point filename for the rarer undockerized case.
            boolean isTestHarnessDirectory = Optional.ofNullable(possibleRoot.getFileName())
                    .map(Path::toString)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .filter(PYTHON_TEST_HARNESS_DIRECTORY_NAMES::contains)
                    .isPresent();
            if (!isTestHarnessDirectory
                    && (scanIndex.firstExistingFileName(possibleRoot, DOCKER_FILES).isPresent()
                        || scanIndex.firstExistingFileName(possibleRoot, PYTHON_ENTRY_POINT_FILES).isPresent())) {
                return Qualification.strongMatch();
            }
        }

        if (hasAnyBuildFile) {
            // Every manifest actually present was checked above and none qualified (e.g. only a Java
            // shared-library pom.xml, or only a Node tooling-only package.json).
            return Qualification.noMatch();
        }

        // No recognized manifest at all - e.g. a Java module directory inferred purely from its
        // src/main/java tree, a deployment-directory-only root, etc. Keep the original scored behavior
        // for these less common, already-tested paths rather than re-deriving new rules for cases this
        // refactor wasn't specifically built to fix.
        return scoredRoot.score() >= MINIMUM_SERVICE_SCORE ? Qualification.weakMatch() : Qualification.noMatch();
    }

    private record Qualification(boolean qualifies, boolean weak) {
        private static Qualification strongMatch() {
            return new Qualification(true, false);
        }

        private static Qualification weakMatch() {
            return new Qualification(true, true);
        }

        private static Qualification noMatch() {
            return new Qualification(false, false);
        }
    }

    /**
     * A "bin" entry or an npm-convention "start"/"server" script is strong evidence on its own - both
     * are an explicit "this is meant to be run" declaration. A bare "main" field is NOT - every Node
     * package has one, libraries and one-shot CLI helpers included (e.g. OpenLMIS's `consul/`
     * registration script, which has only "main": "registration.js" and no scripts/Dockerfile at all -
     * the false-positive this guard fixes, #16 follow-up). "main" alone only counts when corroborated
     * by independent deployability evidence (a Dockerfile, docker-compose service, or k8s/helm chart) -
     * exactly the real services observed in the ground-truth repos that rely on bare "main" (robot-shop's
     * cart/user, sock-shop's front-end) all have one of these.
     */
    private boolean hasNodeEntryPoint(Path packageJsonPath, boolean hasDeploymentEvidence) {
        try (InputStream inputStream = Files.newInputStream(packageJsonPath)) {
            JsonNode root = new ObjectMapper().readTree(inputStream);
            if (hasAnyBin(root.get("bin"))) {
                return true;
            }
            JsonNode scripts = root.get("scripts");
            if (scripts != null && (isNonBlankText(scripts.get("start")) || isNonBlankText(scripts.get("server")))) {
                return true;
            }
            return isNonBlankText(root.get("main")) && hasDeploymentEvidence;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isNonBlankText(JsonNode node) {
        return node != null && node.isTextual() && !node.asText().isBlank();
    }

    private boolean hasAnyBin(JsonNode bin) {
        if (bin == null) {
            return false;
        }
        if (bin.isTextual()) {
            return !bin.asText().isBlank();
        }
        return bin.isObject() && bin.size() > 0;
    }

    /**
     * Suppresses a parent candidate in favor of its children, but ONLY when the parent's own
     * qualification was WEAK (see {@link #qualification} - a stray application.yml/.properties or
     * the generic score fallback, no Dockerfile/@SpringBootApplication/controller of its own): the
     * real "aggregator/parent-POM root with no app code of its own, real services are nested under
     * it" case. A STRONGLY-qualified root (its own Dockerfile, @SpringBootApplication, controller,
     * etc.) is independently deployable regardless of what else happens to be nested under it - e.g.
     * a `consul/` helper script sitting next to a Gradle service's own build.gradle+Dockerfile in
     * openlmis-referencedata is an unrelated sibling folder, not a submodule, and suppressing the
     * real service there because that sibling also qualified was a bug (#16 follow-up).
     */
    private List<ServiceBoundaryCandidate> removeParentCandidates(List<ScoredServiceRoot> scoredRoots, Set<Path> weaklyQualifiedRoots) {
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
                if (weaklyQualifiedRoots.contains(parent)) {
                    rootsWithAcceptedChildren.add(parent);
                }
                parent = parent.getParent();
            }
        }

        return filtered;
    }

    private String inferServiceName(Path possibleRoot, RepositoryConfig rc) {
        return readSpringApplicationName(possibleRoot)
                .or(() -> readMavenArtifactId(possibleRoot.resolve("pom.xml")))
                .or(() -> readGradleRootProjectName(possibleRoot))
                .or(() -> readCsprojServiceName(possibleRoot))
                .or(() -> readNodeEntryPointDirectoryName(possibleRoot))
                .or(() -> readPythonPackageDirectoryName(possibleRoot))
                .or(() -> Optional.ofNullable(possibleRoot.getFileName()).map(Path::toString))
                .filter(name -> !name.isBlank())
                .orElse(rc.getRepoName());
    }

    /**
     * Same idea as the Node entry-point heuristic above, for Python: a repo's pyproject.toml/setup.py
     * "name" (usually the PyPI distribution name, e.g. "rasa-sdk") is routinely different from the
     * importable package directory the real code actually lives in and runs as (e.g. "rasa_sdk", run via
     * `python -m rasa_sdk`) - the latter is what an external ground truth called this service, not the
     * repo name (#16 follow-up). __main__.py is the standard, unambiguous Python signal for "this
     * directory is a runnable package" - only acts when exactly one direct subdirectory has one, since
     * more than one makes the "real" one ambiguous and not worth guessing at.
     */
    private Optional<String> readPythonPackageDirectoryName(Path possibleRoot) {
        try (var entries = Files.list(possibleRoot)) {
            List<Path> runnablePackages = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("__main__.py")))
                    .toList();
            return runnablePackages.size() == 1
                    ? Optional.ofNullable(runnablePackages.get(0).getFileName()).map(Path::toString)
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * A Node service's package.json (and the Dockerfile/README next to it) often sit at the repo root
     * while the actual Express app one level down in a subdirectory the start script names - naming the
     * service after that subdirectory better reflects where the real code lives than the directory the
     * manifest happens to sit in, and matches an external ground truth's own choice of name for exactly
     * this layout (node-js-restful-api-example: root package.json, app in "server/", "scripts.start":
     * "node server/server.js" - labelled "server", not the repo name; #16 follow-up). package.json's own
     * "name" field isn't a safe substitute here - it's the npm package name, which is routinely unrelated
     * to either the repo or the subdirectory (this repo's is "node-js-rest-api", neither of the other two).
     */
    private Optional<String> readNodeEntryPointDirectoryName(Path possibleRoot) {
        Path packageJsonPath = possibleRoot.resolve("package.json");
        if (!Files.isRegularFile(packageJsonPath)) {
            return Optional.empty();
        }
        try (InputStream inputStream = Files.newInputStream(packageJsonPath)) {
            JsonNode root = new ObjectMapper().readTree(inputStream);
            JsonNode scripts = root.get("scripts");
            String entry = firstNonBlank(
                    scripts == null ? null : textOrNull(scripts.get("start")),
                    scripts == null ? null : textOrNull(scripts.get("server")),
                    textOrNull(root.get("main")));
            if (entry == null) {
                return Optional.empty();
            }
            String pathPart = entry.replaceFirst("^(node|nodemon|ts-node)\\s+", "").trim();
            int slash = pathPart.indexOf('/');
            return slash > 0 ? Optional.of(pathPart.substring(0, slash)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    /**
     * A C# project's .csproj file is conventionally named after the project (e.g. cartservice.csproj),
     * which is usually a far better service name than the directory it sits in - .csproj files often
     * live in a nested "src" folder (see #16), which would otherwise become the inferred name.
     */
    private Optional<String> readCsprojServiceName(Path possibleRoot) {
        try (var files = Files.list(possibleRoot)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".csproj"))
                    .map(name -> name.substring(0, name.length() - ".csproj".length()))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
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
        return edu.university.ecs.lab.common.utils.FileUtils.isIgnoredDirectory(dir);
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
                || fileName.equals("Chart.yaml")
                || fileName.endsWith(".csproj"); // C#'s project file; named after the project, not a fixed name
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
        private Set<Path> gradleIncludedModules = Set.of();

        private void recordGradleIncludedModules(Set<Path> modulePaths) {
            this.gradleIncludedModules = modulePaths;
        }

        private boolean isDeclaredGradleModule(Path possibleRoot) {
            return gradleIncludedModules.contains(key(possibleRoot));
        }

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
            // @Path is also Retrofit's path-parameter annotation on outbound client interfaces; it only
            // means a JAX-RS resource when the file imports JAX-RS.
            boolean importsJaxRs = false;
            try (var reader = Files.newBufferedReader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import javax.ws.rs") || trimmed.startsWith("import jakarta.ws.rs")) {
                        importsJaxRs = true;
                    }
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
                    if (!currentEvidence.hasJaxRsResource() && importsJaxRs && line.contains("@Path(")) {
                        currentEvidence = currentEvidence.withJaxRsResource(file);
                    }
                    if (!currentEvidence.hasProgrammaticRoute() && line.contains("new SimpleUrlHandlerMapping(")) {
                        currentEvidence = currentEvidence.withProgrammaticRoute(file);
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

        /** Like {@link #firstExistingFileName}, but for a filename ending in a given extension rather
         * than an exact name - needed for C#'s project file (*.csproj), which is named after the
         * project, not a fixed name. */
        private Optional<String> firstFileWithExtension(Path dir, String extension) {
            return fileNamesByDirectory.getOrDefault(key(dir), List.of()).stream()
                    .filter(fileName -> fileName.endsWith(extension))
                    .findFirst();
        }

        private boolean isRegularFile(Path path) {
            return regularFiles.contains(key(path));
        }

        /** Whether {@code dir} itself contains a file whose name starts with {@code prefix}, ignoring case. */
        private boolean hasOwnFileStartingWith(Path dir, String prefix) {
            return fileNamesByDirectory.getOrDefault(key(dir), List.of()).stream()
                    .anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix));
        }

        /** Whether {@code dir} itself (not a subdirectory) contains a file named exactly {@code fileName}. */
        private boolean hasOwnFile(Path dir, String fileName) {
            return fileNamesByDirectory.getOrDefault(key(dir), List.of()).contains(fileName);
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
            Path requestMappingPath,
            Path jaxRsPath,
            Path programmaticRoutePath
    ) {
        private static JavaEvidence empty() {
            return new JavaEvidence(null, null, null, null, null);
        }

        private JavaEvidence withSpringBootApplication(Path path) {
            return new JavaEvidence(path, controllerPath, requestMappingPath, jaxRsPath, programmaticRoutePath);
        }

        private JavaEvidence withController(Path path) {
            return new JavaEvidence(springBootApplicationPath, path, requestMappingPath, jaxRsPath, programmaticRoutePath);
        }

        private JavaEvidence withRequestMapping(Path path) {
            return new JavaEvidence(springBootApplicationPath, controllerPath, path, jaxRsPath, programmaticRoutePath);
        }

        private JavaEvidence withJaxRsResource(Path path) {
            return new JavaEvidence(springBootApplicationPath, controllerPath, requestMappingPath, path, programmaticRoutePath);
        }

        private JavaEvidence withProgrammaticRoute(Path path) {
            return new JavaEvidence(springBootApplicationPath, controllerPath, requestMappingPath, jaxRsPath, path);
        }

        private boolean hasProgrammaticRoute() {
            return programmaticRoutePath != null;
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

        private boolean hasJaxRsResource() {
            return jaxRsPath != null;
        }

        private boolean isComplete() {
            return hasSpringBootApplication() && hasController() && hasRequestMapping() && hasJaxRsResource()
                    && hasProgrammaticRoute();
        }
    }
}
