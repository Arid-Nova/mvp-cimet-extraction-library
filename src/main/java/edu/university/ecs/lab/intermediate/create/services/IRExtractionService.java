package edu.university.ecs.lab.intermediate.create.services;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.ConfigUtil;
import edu.university.ecs.lab.common.models.ir.*;
import edu.university.ecs.lab.common.services.GitService;
import edu.university.ecs.lab.common.utils.FileUtils;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.common.utils.SourceToObjectUtils;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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
     * CommitID of IR Extraction
     */
    private final String commitID;

    /**
     * This constructor initializes a new IRExtractionService and instantiates a
     * GitService object for repository manipulation
     *
     * @param configPath path to configuration file
     * @param commitID optional commitID for extraction, if empty resolves to HEAD
     * @see GitService
     */
    public IRExtractionService(String configPath, Optional<String> commitID) throws IOException, InterruptedException, GitAPIException {
        gitService = new GitService(configPath);

        if(commitID.isPresent()) {
            this.commitID = commitID.get();
            gitService.resetLocal(this.commitID);
        } else {
            this.commitID = gitService.getHeadCommit();
        }

        config = ConfigUtil.readConfig(configPath);
    }

    /**
     * Intermediate extraction runner, generates IR from remote repository and writes to file.
     *
     * @param fileName name of output file for IR extraction
     */
    public void generateIR(String fileName) throws IOException, InterruptedException {
        MicroserviceSystem microserviceSystem = new MicroserviceSystem(config.getSystemName(), commitID, new HashSet<>(), new HashSet<>());

        // Clone remote repositories and scan through each cloned repo to extract endpoints
        Set<Microservice> microservices = cloneAndScanServices(microserviceSystem);

        microserviceSystem.setMicroservices(microservices);

        //  Write each service and endpoints to IR
        writeToFile(microserviceSystem, fileName);
    }

    /**
     * Clone remote repositories and scan through each local repo and extract endpoints/calls
     *
     * @return a map of services and their endpoints
     */
    public Set<Microservice> cloneAndScanServices(MicroserviceSystem microserviceSystem) throws IOException, InterruptedException {
        Set<Microservice> microservices = new HashSet<>();

        // Clone the repository present in the configuration file
        gitService.cloneRemote();

        // Start scanning from the root directory
        List<String> rootDirectories = findRootDirectories(FileUtils.getRepositoryPath(config.getRepoName()));
        List<String> rootDirectoriesCopy = List.copyOf(rootDirectories);

        // Filter more/less specific
        for(String s1 : rootDirectoriesCopy) {
            for(String s2 : rootDirectoriesCopy) {
                if(s1.equals(s2)) {
                    continue;
                } else if(s1.matches(s2.replace(FileUtils.SYS_SEPARATOR, FileUtils.SPECIAL_SEPARATOR) + FileUtils.SPECIAL_SEPARATOR + ".*")) {
                    rootDirectories.remove(s2);
                } else if(s2.matches(s1.replace(FileUtils.SYS_SEPARATOR, FileUtils.SPECIAL_SEPARATOR) + FileUtils.SPECIAL_SEPARATOR + ".*")) {
                    rootDirectories.remove(s1);
                }
            }
        }

        // Scan each root directory for microservices
        for (String rootDirectory : rootDirectories) {
            Microservice microservice = recursivelyScanFiles(microserviceSystem, rootDirectory);
            if (microservice != null) {
                microservices.add(microservice);
            }
        }

        return microservices;
    }

    /**
     * Recursively search for directories containing a microservice (pom.xml file)
     *
     * @param directory the directory to start the search from
     * @return a list of directory paths containing pom.xml
     */
    private List<String> findRootDirectories(String directory) {
        List<String> rootDirectories = new ArrayList<>();
        File root = new File(directory);
        if (root.exists() && root.isDirectory()) {
            // Check if the current directory contains a Dockerfile
            File[] files = root.listFiles();
            boolean containsPom = false;
            boolean containsGradle = false;
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().equals("pom.xml")) {
                        try {

                            // Create a DocumentBuilder
                            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

                            // Parse the XML file
                            Document document = builder.parse(file);

                            // Normalize the XML Structure
                            document.getDocumentElement().normalize();

                            // Get all elements with the specific tag name
                            NodeList nodeList = document.getElementsByTagName("modules");
                            // Check if the tag is present
                            if (nodeList.getLength() == 0) {
                                containsPom = true;
                            }
                        } catch (Exception e) {
                            throw new RuntimeException("Error parsing pom.xml");
                        }
                    } else if(file.isFile() && file.getName().equals("build.gradle")) {
                        containsGradle = true;
                    } else if (file.isDirectory()) {
                        rootDirectories.addAll(findRootDirectories(file.getPath()));
                    }
                }
            }
            if (containsPom) {
                rootDirectories.add(root.getPath());
                return rootDirectories;
            } else if (containsGradle){
                rootDirectories.add(root.getPath());
                return rootDirectories;
            }
        }
        return rootDirectories;
    }


    /**
     * Write each service and endpoints to intermediate representation
     *
     * @param microserviceSystem a MicroserviceSystem extracted from repository
     * @param fileName the name of the output file for IR
     */
    private void writeToFile(MicroserviceSystem microserviceSystem, String fileName) throws IOException {
        JsonReadWriteUtils.writeToJSON(fileName, microserviceSystem);
    }

    /**
     * Recursively scan the files in the given repository path and extract the endpoints and
     * dependencies for a single microservice.
     *
     * @return model of a single service containing the extracted endpoints and dependencies
     */
    public Microservice recursivelyScanFiles(MicroserviceSystem microserviceSystem, String rootMicroservicePath) throws IOException {
        // Validate path exists and is a directory
        File localDir = new File(rootMicroservicePath);
        if (!localDir.exists() || !localDir.isDirectory()) {
            throw new IOException("Microservice path must exist and be directory");
        }

        Microservice model = new Microservice(microserviceSystem, FileUtils.getMicroserviceNameFromPath(rootMicroservicePath),
                FileUtils.localPathToGitPath(rootMicroservicePath, config.getRepoName()));
        scanDirectory(localDir, model);

        return model;
    }

    /**
     * Recursively scan the given directory for files and extract the endpoints and dependencies.
     *
     * @param directory the directory to scan
     */
    public void scanDirectory(
            File directory,
            Microservice microservice) {
        File[] files = directory.listFiles();

        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    scanDirectory(file, microservice);
                } else if (FileUtils.isValidFile(file.getPath())) {
                    if(FileUtils.isConfigurationFile(file.getPath())) {
                        ConfigFile configFile = SourceToObjectUtils.parseConfigurationFile(file, config, microservice);
                        if(configFile != null) {
                            microservice.getFiles().add(configFile);
                        }
                    } else {
                        AbstractClass abstractClass = SourceToObjectUtils.parseClass(microservice, file, config, false);
                        if (abstractClass != null) {
                            microservice.addAbstractClass(abstractClass);
                        }
                    }
                }
            }
        }
    }

    public static MicroserviceSystem create(Path configPath) throws GitAPIException, IOException, InterruptedException {
        IRExtractionService extractionService = new IRExtractionService(configPath.toString(), Optional.empty());
        MicroserviceSystem microserviceSystem = new MicroserviceSystem(extractionService.config.getSystemName(), extractionService.commitID, new HashSet<>(), new HashSet<>());

        Set<Microservice> microservices = extractionService.cloneAndScanServices(microserviceSystem);
        microserviceSystem.setMicroservices(microservices);
        return microserviceSystem;
    }

    public static MicroserviceSystem create(Path configPath, String commitID) throws GitAPIException, IOException, InterruptedException {
        IRExtractionService extractionService = new IRExtractionService(configPath.toString(), Optional.of(commitID));
        MicroserviceSystem microserviceSystem = new MicroserviceSystem(extractionService.config.getSystemName(), extractionService.commitID, new HashSet<>(), new HashSet<>());

        Set<Microservice> microservices = extractionService.cloneAndScanServices(microserviceSystem);
        microserviceSystem.setMicroservices(microservices);
        return microserviceSystem;
    }

    public static void createAndWrite(Path configPath, Path outputPath) throws GitAPIException, IOException, InterruptedException {
        MicroserviceSystem microserviceSystem = create(configPath.toAbsolutePath());
        JsonReadWriteUtils.writeToJSON(outputPath.toString(), microserviceSystem);
    }

    public static MicroserviceSystem read(Path inputPath) throws IOException {
        MicroserviceSystem microserviceSystem = JsonReadWriteUtils.readFromJSON(inputPath.toString(), MicroserviceSystem.class);
        MicroserviceSystem.setParentReferencesRecursively(microserviceSystem, null);
        return microserviceSystem;
    }


}
