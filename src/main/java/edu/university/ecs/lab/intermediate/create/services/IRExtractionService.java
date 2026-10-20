package edu.university.ecs.lab.intermediate.create.services;

import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;
import edu.university.ecs.lab.common.parsers.ParserRegistry;
import edu.university.ecs.lab.common.parsers.ParseResult;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.json.JSONObject;

import java.io.*;
import java.util.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import org.json.JSONArray;

/**
 * Top-level service for extracting intermediate representation from remote repositories. Methods
 * are allowed to exit the program with an error code if an error occurs.
 */
public class IRExtractionService {
    /** Explicit opt-in factory; existing Java repository extraction is unchanged. */
    public static edu.university.ecs.lab.javascript.JavaScriptExtractionService createJavaScript(Path adapterDirectory) {
        return new edu.university.ecs.lab.javascript.JavaScriptExtractionService(adapterDirectory);
    }

    /**
     * Service to handle cloning from git
     */
    private final GitService gitService;

    /**
     * Configuration object
     */
    private final Config config;

    /**
     * Per-job registry of language-specific parsers. Repository context is prepared once,
     * so JavaScript module resolution does not start a process for every source file.
     */
    private final ParserRegistry parserRegistry;

    /**
     * This constructor initializes a new IRExtractionService and instantiates a
     * GitService object for repository manipulation
     *
     * @param config the Config to use
     * @see GitService
     */
    public IRExtractionService(Config config) throws IOException, InterruptedException {
        validateRepositoryCacheIdentities(config);
        gitService = new GitService(config);
        this.config = config;
        this.parserRegistry = ParserRegistry.createDefault();
    }

    /**
     * Clone remote repositories and scan through each local repo and extract endpoints/calls.
     * Performs this for every Git repository provided.
     *
     * @return a set of services and their endpoints
     */
    public Set<Microservice> cloneAndScanMultiRepositoryServices(MicroserviceSystem microserviceSystem,
                                                                 boolean writePartialIRs)
            throws IOException, InterruptedException, GitAPIException {
        for (RepositoryConfig rc : config.getSystemRepositories()) {
            // Saved partials may have been written by another parser version or configuration.
            // Re-extract rather than silently returning stale Java-only/polyglot evidence.
            Set<Microservice> microservices = cloneAndScanServices(microserviceSystem, rc);
            normalizeRepositoryPaths(microservices, rc);
            if (writePartialIRs) {
                JsonReadWriteUtils.writeToJSON(FileUtils.getPartialIRPath(rc), new PartialMicroserviceSystemDto(microservices));
            }

            microserviceSystem.getMicroservices().addAll(microservices);
        }
        return microserviceSystem.getMicroservices();
    }

    /**
     * Clone remote repositories and scan through each local repo and extract endpoints/calls
     * Performs this for the single provided Git repository.
     *
     * @return a set of services and their endpoints
     */
    public Set<Microservice> cloneAndScanServices(MicroserviceSystem microserviceSystem, RepositoryConfig rc) throws IOException, InterruptedException, GitAPIException {
        Set<Microservice> microservices = new HashSet<>();

        // Clone the repository present in the configuration file
        gitService.cloneRemote(rc);
        gitService.resetLocal(rc, rc.commitID());

        Path repositoryRoot = Path.of(FileUtils.getRepositoryPath(rc.getRepoName())).toAbsolutePath().normalize();
        try (var paths = FileUtils.withRepositoryRoot(rc.getRepoName(), repositoryRoot)) {
            parserRegistry.prepareRepository(repositoryRoot, rc);
            ServiceBoundaryDetector detector = new ServiceBoundaryDetector();
            List<ServiceBoundaryCandidate> candidates = detector.detect(repositoryRoot.toString(), rc);
            for (ServiceBoundaryCandidate candidate : candidates) {
                Microservice microservice = recursivelyScanFiles(microserviceSystem,
                        candidate.rootPath().toString(), rc, candidate.serviceName());
                if (microservice != null) microservices.add(microservice);
            }
        }

        return microservices;
    }

    private void normalizeRepositoryPaths(Set<Microservice> microservices, RepositoryConfig rc) {
        for (Microservice microservice : microservices) {
            microservice.setPath(Path.of(FileUtils.normalizeRepositoryPath(
                    microservice.getPath().toString(),
                    rc.getRepoName()
            )));

            for (ProjectFile file : microservice.getAllFiles()) {
                file.setPath(Path.of(FileUtils.normalizeRepositoryPath(
                        file.getPath().toString(),
                        rc.getRepoName()
                )));
            }
        }
    }

    /**
     * Recursively scan the files in the given repository path and extract the endpoints and
     * dependencies for a single microservice.
     *
     * @return model of a single service containing the extracted endpoints and dependencies
     */
    public Microservice recursivelyScanFiles(MicroserviceSystem microserviceSystem,
                                             String rootMicroservicePath,
                                             RepositoryConfig rc) throws IOException {
        return recursivelyScanFiles(microserviceSystem, rootMicroservicePath, rc, null);
    }

    private Microservice recursivelyScanFiles(MicroserviceSystem microserviceSystem,
                                             String rootMicroservicePath,
                                             RepositoryConfig rc,
                                             String inferredServiceName) throws IOException {
        // Validate path exists and is a directory
        File localDir = new File(rootMicroservicePath);
        if (!localDir.exists() || !localDir.isDirectory()) {
            throw new IOException("Microservice path must exist and be directory");
        }

        String microserviceName = inferredServiceName;
        if (microserviceName == null || microserviceName.isBlank()) {
            microserviceName = FileUtils.getMicroserviceNameFromPath(rootMicroservicePath)
                    .orElse(rc.getRepoName());
        }

        Microservice model = new Microservice(microserviceSystem,
                microserviceName,
                rc.repoBranchPair().repositoryURL(),
                rc.commitID(),
                Path.of(FileUtils.localPathToGitPath(rootMicroservicePath, rc.getRepoName())));

        JSONObject metadata = new JSONObject();
        metadata.put("branch", rc.repoBranchPair().branchName());
        model.setMetadata(metadata);

        scanDirectory(localDir, model, rc);

        return model;
    }

    /**
     * Recursively scan the given directory for files and extract the endpoints and dependencies.
     *
     * @param directory the directory to scan
     */
    public void scanDirectory(
            File directory,
            Microservice microservice,
            RepositoryConfig rc) {
        if (Files.isSymbolicLink(directory.toPath())) {
            diagnostic(microservice, directory, "SYMLINK_SKIPPED", "Symbolic directory links are not followed");
            return;
        }
        File[] files = directory.listFiles();
        if (files == null) {
            diagnostic(microservice, directory, "UNREADABLE_DIRECTORY", "Source directory could not be read");
            return;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (Files.isSymbolicLink(file.toPath())) {
                diagnostic(microservice, file, "SYMLINK_SKIPPED", "Symbolic links are not followed");
            } else if (file.isDirectory()) {
                if (!FileUtils.IGNORED_DIRECTORY_NAMES.contains(file.getName())) scanDirectory(file, microservice, rc);
            } else if (parserRegistry.findParserFor(file).isPresent() || FileUtils.isValidFile(file.getPath())) {
                if (parserRegistry.findParserFor(file).isEmpty() && FileUtils.isConfigurationFile(file.getPath())) {
                    try {
                        ConfigFile configuration = SourceToObjectUtils.parseConfigurationFile(file, rc, microservice);
                        if (configuration != null) microservice.getFiles().add(configuration);
                    } catch (RuntimeException failure) {
                        diagnostic(microservice, file, "CONFIG_PARSE_FAILED", failure.getClass().getSimpleName());
                    }
                } else {
                    parserRegistry.findParserFor(file).ifPresent(parser -> {
                        ParseResult result = parser.parse(microservice, file, rc, false);
                        JSONObject metadata = metadata(microservice);
                        JSONObject capabilities = metadata.optJSONObject("parsers");
                        if (capabilities == null) { capabilities = new JSONObject(); metadata.put("parsers", capabilities); }
                        capabilities.put(parser.getCapability().getLanguage(), new JSONObject()
                                .put("extensions", parser.getCapability().getSupportedExtensions())
                                .put("limitations", parser.getCapability().getLimitations()));
                        if (result.isSuccess()) result.getClasses().forEach(microservice::addAbstractClass);
                        else diagnostic(microservice, file, "SOURCE_PARSE_FAILED", result.getFailureReason());
                    });
                }
            }
        }

    }

    private static JSONObject metadata(Microservice service) {
        if (service.getMetadata() == null) service.setMetadata(new JSONObject());
        return service.getMetadata();
    }

    private static void diagnostic(Microservice service, File file, String code, String reason) {
        JSONObject metadata = metadata(service);
        JSONArray diagnostics = metadata.optJSONArray("diagnostics");
        if (diagnostics == null) { diagnostics = new JSONArray(); metadata.put("diagnostics", diagnostics); }
        diagnostics.put(new JSONObject().put("file", file.getName()).put("code", code)
                .put("reason", reason == null ? "Parser did not return source evidence" : reason));
        metadata.put("status", "partial");
    }

    private static void annotateCoverage(MicroserviceSystem system) {
        Set<String> languages = new TreeSet<>();
        boolean partial = false;
        for (Microservice service : system.getMicroservices()) {
            JSONObject data = metadata(service);
            JSONObject parsers = data.optJSONObject("parsers");
            if (parsers != null) languages.addAll(parsers.keySet());
            partial |= "partial".equals(data.optString("status"));
            for (AbstractClass module : service.getClasses()) {
                if (module.getMetadata() != null) {
                    String language = module.getMetadata().optString("language", "");
                    if (!language.isBlank()) languages.add(language);
                }
            }
        }
        JSONObject data = system.getMetadata() == null ? new JSONObject() : system.getMetadata();
        data.put("languages", new JSONArray(languages));
        data.put("extraction", "deterministic-static-source");
        data.put("status", partial ? "partial" : "complete-for-supported-source-patterns");
        system.setMetadata(data);
    }

    private static void validateRepositoryCacheIdentities(Config config) {
        Map<String, String> urls = new HashMap<>();
        Set<String> repositories = new HashSet<>();
        for (RepositoryConfig source : config.getSystemRepositories()) {
            String url = source.repoBranchPair().repositoryURL();
            if (!repositories.add(url)) throw new IllegalArgumentException(
                    "Use one repository entry per source root; the parser registry detects its languages per file");
            String previous = urls.putIfAbsent(source.getRepoName(), url);
            if (previous != null && !previous.equals(url)) throw new IllegalArgumentException(
                    "Repositories with the same basename need isolated extraction jobs because the legacy clone cache is basename-keyed");
        }
    }

    private IRExtractionService(ParserRegistry registry) {
        this.gitService = null;
        this.config = null;
        this.parserRegistry = Objects.requireNonNull(registry);
    }

    /** Immutable, administrator-resolved source input. A name declares one application boundary;
     * a null name asks the boundary detector to discover applications from project metadata. */
    public record LocalSource(Path root, RepositoryConfig repository, String componentName) {
        public LocalSource { Objects.requireNonNull(root); Objects.requireNonNull(repository); }
    }

    /** Parse local source exports without Git, network access, target execution, or database services. */
    public static MicroserviceSystem extractLocal(String systemName, Path root,
            RepositoryConfig repository, String componentName) throws IOException {
        return extractLocal(systemName, List.of(new LocalSource(root, repository, componentName)));
    }

    public static MicroserviceSystem extractLocal(String systemName, List<LocalSource> sources) throws IOException {
        return extractLocal(systemName, sources, ParserRegistry.createDefault());
    }

    /** Registry injection is the extension point for additional languages and trusted parser profiles. */
    public static MicroserviceSystem extractLocal(String systemName, List<LocalSource> sources,
            ParserRegistry registry) throws IOException {
        if (systemName == null || systemName.isBlank() || sources == null || sources.isEmpty())
            throw new IllegalArgumentException("A system name and at least one local source are required");
        IRExtractionService extraction = new IRExtractionService(registry);
        MicroserviceSystem system = new MicroserviceSystem(systemName, new LinkedHashSet<>(), new LinkedHashSet<>());
        Set<String> identities = new HashSet<>();
        for (LocalSource source : sources) {
            source.repository().validateConfig();
            Path root = source.root().toAbsolutePath().normalize();
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Source root must be an existing directory without a symbolic root link");
            String identity = source.repository().repoBranchPair().repositoryURL();
            if (!identities.add(identity)) throw new IllegalArgumentException(
                    "Use one source root per repository; languages are discovered per file and service boundaries within that root");
            try (var paths = FileUtils.withRepositoryRoot(source.repository().getRepoName(), root)) {
                registry.prepareRepository(root, source.repository());
                if (source.componentName() != null && !source.componentName().isBlank()) {
                    system.getMicroservices().add(extraction.recursivelyScanFiles(system, root.toString(),
                            source.repository(), source.componentName()));
                } else {
                    for (ServiceBoundaryCandidate candidate : new ServiceBoundaryDetector().detect(root.toString(), source.repository())) {
                        system.getMicroservices().add(extraction.recursivelyScanFiles(system,
                                candidate.rootPath().toString(), source.repository(), candidate.serviceName()));
                    }
                }
            }
        }
        MicroserviceSystem.setParentReferencesRecursively(system, null);
        annotateCoverage(system);
        return system;
    }

    /**
     * Creates a MicroserviceSystem that can be written to a file as an intermediate representation. Generates the system at the latest available commit ID.
     * @param config The configuration to use (see {@link Config})
     * @return The {@link MicroserviceSystem} derived from the codebase in the specified configuration
     */
    public static MicroserviceSystem create(Config config) throws GitAPIException, IOException, InterruptedException {
        IRExtractionService extractionService = new IRExtractionService(config);
        MicroserviceSystem microserviceSystem = new MicroserviceSystem(extractionService.config.getSystemName(),
                new HashSet<>(), new HashSet<>());

        extractionService.cloneAndScanMultiRepositoryServices(microserviceSystem, false);

        annotateCoverage(microserviceSystem);
        return microserviceSystem;
    }

    /**
     * Creates a MicroserviceSystem that can be written to a file as an intermediate representation.
     * Additionally, it writes the MicroserviceSystem to a file.
     * @param config The configuration to use (see {@link Config})
     * @param irName The file name for the outputted IR
     * @return The {@link MicroserviceSystem} derived from the codebase in the specified configuration
     */
    public static MicroserviceSystem createAndWrite(Config config, String irName)
            throws IOException, InterruptedException, GitAPIException {
        IRExtractionService extractionService = new IRExtractionService(config);
        MicroserviceSystem microserviceSystem = new MicroserviceSystem(extractionService.config.getSystemName(),
                new HashSet<>(), new HashSet<>());

        extractionService.cloneAndScanMultiRepositoryServices(microserviceSystem, true);

        annotateCoverage(microserviceSystem);
        JsonReadWriteUtils.writeToJSON(Path.of(FileUtils.getOutputPath() + File.separator + irName), microserviceSystem);
        return microserviceSystem;
    }

    /**
     * Reads a MicroserviceSystem from the given intermediate representation.
     * @param inputPath The path to the intermediate representation
     * @return A {@link MicroserviceSystem} derived from the IR
     */
    public static MicroserviceSystem read(Path inputPath) throws IOException {
        MicroserviceSystem microserviceSystem = JsonReadWriteUtils.readFromJSON(inputPath, MicroserviceSystem.class);
        MicroserviceSystem.setParentReferencesRecursively(microserviceSystem, null);
        return microserviceSystem;
    }

    public static PartialMicroserviceSystemDto readPartial(Path inputPath) throws IOException {
        return JsonReadWriteUtils.readFromJSON(inputPath, PartialMicroserviceSystemDto.class);
    }
}
