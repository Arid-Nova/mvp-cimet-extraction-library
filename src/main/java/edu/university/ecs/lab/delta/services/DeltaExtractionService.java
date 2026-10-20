package edu.university.ecs.lab.delta.services;

import edu.university.ecs.lab.common.config.Config;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;
import edu.university.ecs.lab.common.utils.JsonReadWriteUtils;
import edu.university.ecs.lab.delta.models.SystemChange;
import edu.university.ecs.lab.intermediate.create.services.IRExtractionService;
import org.eclipse.jgit.api.errors.GitAPIException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Parser-neutral revision comparison using the same extraction path as snapshots. */
public final class DeltaExtractionService {
    private DeltaExtractionService() {}

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit
     * @param config The configuration to use
     * @param intermediateSystem The base MicroserviceSystem
     * @param newCommits The commits to compare the base system with
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange create(Config config, MicroserviceSystem intermediateSystem, Map<RepositoryConfig, String> newCommits) throws IOException, InterruptedException, GitAPIException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(intermediateSystem, "intermediateSystem");
        Objects.requireNonNull(newCommits, "newCommits");
        Set<edu.university.ecs.lab.common.config.RepositoryBranchPair> configured = new HashSet<>();
        config.getSystemRepositories().forEach(repository -> configured.add(repository.repoBranchPair()));
        if (newCommits.keySet().stream().anyMatch(repository -> !configured.contains(repository.repoBranchPair())))
            throw new IllegalArgumentException("A comparison repository was not present in the base configuration");
        List<RepositoryConfig> targets = new ArrayList<>();
        for (RepositoryConfig base : config.getSystemRepositories()) {
            List<String> revisions = newCommits.entrySet().stream()
                    .filter(entry -> entry.getKey().repoBranchPair().equals(base.repoBranchPair()))
                    .map(Map.Entry::getValue).distinct().toList();
            if (revisions.size() > 1) throw new IllegalArgumentException("One target revision per repository is required");
            targets.add(new RepositoryConfig(base.repoBranchPair(), revisions.isEmpty() ? base.commitID() : revisions.getFirst()));
        }
        try {
            // Both snapshot and delta parsing share the same registry, module context, source discovery,
            // and boundary detector. Full re-extraction favors correctness over an incomplete per-file
            // shortcut (a changed router/import/manifest can affect files Git did not mark modified).
            MicroserviceSystem target = IRExtractionService.create(new Config(config.getSystemName(), targets));
            return SnapshotDeltaService.compare(intermediateSystem, target);
        } catch (IOException | InterruptedException | GitAPIException | RuntimeException failure) {
            throw failure;
        } catch (Exception invalidConfiguration) {
            throw new IllegalArgumentException("Invalid target repository configuration", invalidConfiguration);
        }
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * @param config The configuration to use
     * @param baseIRPath The path to the base IR to compare with
     * @param newCommits The commits to compare the base system with
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange create(Config config, Path baseIRPath, Map<RepositoryConfig, String> newCommits)
            throws IOException, InterruptedException, GitAPIException {
        return create(config, IRExtractionService.read(baseIRPath), newCommits);
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * Additionally, it writes the Delta to a file.
     * @param config The configuration to use
     * @param intermediateSystem The base MicroserviceSystem
     * @param newCommits The commits to compare the base system with
     * @param outputPath The path to output the Delta to
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange createAndWrite(Config config,
                                              MicroserviceSystem intermediateSystem,
                                              Map<RepositoryConfig, String> newCommits,
                                              Path outputPath)
            throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(config, intermediateSystem, newCommits);
        JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
        return systemChange;
    }

    /**
     * Creates a Delta incrementally comparing the base MicroserviceSystem with a newer commit.
     * Additionally, it writes the Delta to a file.
     * @param config The configuration to use
     * @param baseIRPath The path to the base IR to compare with
     * @param newCommits The commits to compare the base system with
     * @param outputPath The path to output the Delta to
     * @return A {@link SystemChange} containing all changes made from the base system to the new commit ID
     */
    public static SystemChange createAndWrite(Config config,
                                              Path baseIRPath,
                                              Map<RepositoryConfig, String> newCommits,
                                              Path outputPath)
            throws GitAPIException, IOException, InterruptedException {
        SystemChange systemChange = DeltaExtractionService.create(config, baseIRPath, newCommits);
        JsonReadWriteUtils.writeToJSON(outputPath, systemChange);
        return systemChange;
    }

    /**
     * Reads a Delta from a file.
     * @param path The path to the Delta
     * @return A {@link SystemChange} containing all the changes made in a system from one commit to a newer commit
     */
    public static SystemChange read(Path path) throws IOException {
        return JsonReadWriteUtils.readFromJSON(path, SystemChange.class);
    }
}
