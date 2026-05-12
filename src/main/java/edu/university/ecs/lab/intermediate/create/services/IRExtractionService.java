package edu.university.ecs.lab.intermediate.create.services;

import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import edu.university.ecs.lab.common.models.dto.PartialMicroserviceSystemDto;

import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.*;
import java.util.*;
import java.nio.file.Path;

/**
 * Top-level service for extracting intermediate representation from remote repositories. Methods
 * are allowed to exit the program with an error code if an error occurs.
 */
public class IRExtractionService {
    /**
     * Service to handle cloning from git
     */
    private final GitService gitService;

    /**
     * Configuration object
     */
    private final Config config;

    /**
     * This constructor initializes a new IRExtractionService and instantiates a
     * GitService object for repository manipulation
     *
     * @param config the Config to use
     * @see GitService
     */
    public IRExtractionService(Config config) throws IOException, InterruptedException {
        gitService = new GitService(config);
        this.config = config;
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
            Set<Microservice> microservices;
            File partialIR = new File(FileUtils.getPartialIRPath(rc).toString());
            if (partialIR.exists() && !partialIR.isDirectory()) {
                microservices = readPartial(FileUtils.getPartialIRPath(rc)).getMicroservices();
            }
            else {
                microservices = cloneAndScanServices(microserviceSystem, rc);
                if (writePartialIRs) {
                    JsonReadWriteUtils.writeToJSON(FileUtils.getPartialIRPath(rc), new PartialMicroserviceSystemDto(microservices));
                }
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

        ServiceBoundaryDetector detector = new ServiceBoundaryDetector();
        List<ServiceBoundaryCandidate> candidates = detector.detect(FileUtils.getRepositoryPath(rc.getRepoName()), rc);

        // Scan each root directory for microservices
        for (ServiceBoundaryCandidate candidate : candidates) {
            Microservice microservice = recursivelyScanFiles(
                    microserviceSystem,
                    candidate.rootPath().toString(),
                    rc,
                    candidate.serviceName()
            );
            if (microservice != null) {
                microservices.add(microservice);
            }
        }

        return microservices;
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
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    scanDirectory(file, microservice, rc);
                } else if (FileUtils.isValidFile(file.getPath())) {
                    if(FileUtils.isConfigurationFile(file.getPath())) {
                        ConfigFile configFile = SourceToObjectUtils.parseConfigurationFile(file, rc, microservice);
                        if(configFile != null) {
                            microservice.getFiles().add(configFile);
                        }
                    } else {
                        AbstractClass abstractClass = SourceToObjectUtils.parseClass(microservice, file, rc, false);
                        if (abstractClass != null) {
                            microservice.addAbstractClass(abstractClass);
                        }
                    }
                }
            }
        }
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
