package edu.university.ecs.lab.javascript;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.models.ir.MicroserviceSystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Opt-in static JavaScript extraction. Only the trusted bundled analyzer is executed. */
public final class JavaScriptExtractionService {
    public static final int DEFAULT_OUTPUT_LIMIT = 32 * 1024 * 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final Path adapterDirectory;
    private final String nodeExecutable;
    private final Duration timeout;
    private final int outputLimit;
    private final int heapMiB;

    public JavaScriptExtractionService(Path adapterDirectory) {
        this(adapterDirectory, "node");
    }

    public JavaScriptExtractionService(Path adapterDirectory, String nodeExecutable) {
        this(adapterDirectory, nodeExecutable, DEFAULT_TIMEOUT, DEFAULT_OUTPUT_LIMIT);
    }

    public JavaScriptExtractionService(Path adapterDirectory, String nodeExecutable,
                                       Duration timeout, int outputLimit) {
        this(adapterDirectory, nodeExecutable, timeout, outputLimit, 384);
    }

    /** The V8 heap cap is not a bound on total process RSS. */
    public JavaScriptExtractionService(Path adapterDirectory, String nodeExecutable,
                                       Duration timeout, int outputLimit, int heapMiB) {
        this.adapterDirectory = Objects.requireNonNull(adapterDirectory).toAbsolutePath().normalize();
        this.nodeExecutable = Objects.requireNonNull(nodeExecutable);
        this.timeout = Objects.requireNonNull(timeout);
        this.outputLimit = outputLimit;
        this.heapMiB = heapMiB;
        if (nodeExecutable.isBlank() || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0 || outputLimit < 1024
                || outputLimit > 128 * 1024 * 1024 || heapMiB < 64 || heapMiB > 2048) {
            throw new IllegalArgumentException("Invalid JavaScript analyzer process limits");
        }
    }

    public ObjectNode extract(Path root, String kind, String name) throws IOException, InterruptedException {
        return extract(root, kind, name, null);
    }

    /** The optional configuration is data for the analyzer, never an executable target configuration. */
    public ObjectNode extract(Path root, String kind, String name, Path config)
            throws IOException, InterruptedException {
        validateComponent(kind, name);
        Path source = Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) throw new IOException("JavaScript source directory does not exist");
        Path analyzer = adapterDirectory.resolve("analyzer.cjs");
        if (!Files.isRegularFile(analyzer)) throw new IOException("JavaScript analyzer.cjs is missing");
        List<String> command = new ArrayList<>(List.of(nodeExecutable, "--max-old-space-size=" + heapMiB, analyzer.toString(),
                "--root", source.toString(), "--kind", kind, "--name", name));
        if (config != null) {
            Path absoluteConfig = config.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absoluteConfig)) throw new IOException("Analyzer configuration is missing");
            command.addAll(List.of("--config", absoluteConfig.toString()));
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(adapterDirectory.toFile());
        // Do not allow inherited Node preload hooks to execute analyzed project code.
        builder.environment().remove("NODE_OPTIONS");
        builder.environment().remove("NODE_PATH");
        Process process = builder.start();
        process.getOutputStream().close();
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<byte[]> stdout = readers.submit(() -> readBounded(process.getInputStream(), outputLimit, process));
            Future<byte[]> stderr = readers.submit(() -> readBounded(process.getErrorStream(), 64 * 1024, process));
            try {
                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminate(process);
                    throw new IOException("JavaScript analyzer exceeded its time limit");
                }
                byte[] output = result(stdout);
                result(stderr); // Drain concurrently but never echo source text or credentials in diagnostics.
                if (process.exitValue() != 0) {
                    throw new IOException("JavaScript analyzer failed with exit code " + process.exitValue());
                }
                JsonNode snapshot;
                try {
                    snapshot = JSON.readTree(output);
                } catch (IOException invalidJson) {
                    throw new IOException("JavaScript analyzer returned invalid JSON");
                }
                validateSnapshot(snapshot);
                if (!kind.equals(snapshot.path("component").path("kind").asText())
                        || !name.equals(snapshot.path("component").path("name").asText())) {
                    throw new IOException("JavaScript analyzer returned a different component");
                }
                return (ObjectNode) snapshot;
            } finally {
                terminate(process);
                process.getInputStream().close();
                process.getErrorStream().close();
            }
        } finally { readers.shutdownNow(); }
    }

    private static byte[] result(Future<byte[]> future) throws IOException, InterruptedException {
        try { return future.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException ex) { throw new IOException("JavaScript analyzer output stream did not close"); }
        catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException io) throw io;
            throw new IOException("Could not read JavaScript analyzer output");
        }
    }

    private static byte[] readBounded(InputStream input, int maximum, Process process) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (count > maximum - output.size()) {
                    terminate(process);
                    throw new IOException("JavaScript analyzer exceeded its output limit");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void terminate(Process process) {
        process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
    }

    static void validateComponent(String kind, String name) {
        if (kind == null || !Set.of("backend", "frontend").contains(kind) || name == null || name.isBlank()
                || name.length() > 256 || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Component kind must be backend or frontend and name must be nonblank");
        }
    }

    /** Validate the public snapshot boundary before accepting analyzer or saved snapshot output. */
    public static void validateSnapshot(JsonNode snapshot) throws IOException {
        if (snapshot == null || !snapshot.isObject() || !snapshot.path("schemaVersion").isIntegralNumber()
                || snapshot.path("schemaVersion").asInt() != 1 || !snapshot.path("component").isObject()) {
            throw new IOException("Unsupported JavaScript snapshot schema");
        }
        try {
            validateComponent(snapshot.path("component").path("kind").asText(),
                    snapshot.path("component").path("name").asText());
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid JavaScript snapshot component"); }
        for (String group : List.of("endpoints", "calls", "diagnostics")) {
            if (!snapshot.path(group).isArray()) throw new IOException("Snapshot requires array: " + group);
        }
        for (String group : List.of("endpoints", "calls")) {
            Set<String> ids = new HashSet<>();
            for (JsonNode item : snapshot.path(group)) {
                if (!item.isObject() || !item.path("id").isTextual() || item.path("id").asText().isBlank()
                        || !ids.add(item.path("id").asText()) || !item.path("file").isTextual()
                        || !item.path("method").isTextual()) throw new IOException("Invalid or duplicate snapshot record");
                String file = item.path("file").asText().replace('\\', '/');
                if (file.isBlank() || file.startsWith("/") || file.contains(":")
                        || List.of(file.split("/")).contains("..")) throw new IOException("Snapshot source paths must be relative");
                for (String field : List.of("path", "normalizedPath")) {
                    JsonNode value = item.path(field);
                    if (!(value.isNull() || value.isMissingNode() || value.isTextual())
                            || value.isTextual() && value.asText().length() > 16384) {
                        throw new IOException("Invalid snapshot path");
                    }
                }
            }
        }
        if (snapshot.has("architecture")) JavaScriptArchitectureValidator.validate(snapshot);
    }

    public MicroserviceSystem toIR(JsonNode snapshot, String repositoryURL, String commit) throws IOException {
        validateSnapshot(snapshot);
        ObjectNode copy = snapshot.deepCopy();
        ObjectNode component = copy.withObject("component");
        component.put("repositoryURL", repositoryURL == null ? "" : repositoryURL);
        component.put("commit", commit == null ? "" : commit);
        return JavaScriptIRConverter.toIR(List.of(copy), component.path("name").asText());
    }

    public MicroserviceSystem toIR(List<? extends JsonNode> snapshots, String systemName) throws IOException {
        return JavaScriptIRConverter.toIR(snapshots, systemName);
    }
}
