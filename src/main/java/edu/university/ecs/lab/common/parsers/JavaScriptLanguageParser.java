package edu.university.ecs.lab.common.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.university.ecs.lab.common.config.RepositoryConfig;
import edu.university.ecs.lab.common.models.enums.ClassRole;
import edu.university.ecs.lab.common.models.ir.JSModule;
import edu.university.ecs.lab.common.models.ir.Microservice;
import edu.university.ecs.lab.javascript.JavaScriptExtractionService;
import edu.university.ecs.lab.javascript.JavaScriptIRConverter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/** Repository-aware TypeScript compiler adapter. Target project modules and scripts are never executed. */
public final class JavaScriptLanguageParser implements LanguageParser {
    public static final Set<String> EXTENSIONS = Set.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs", ".mts", ".cts");
    private final JavaScriptExtractionService extraction;
    private final Path analyzerConfig;
    private Path repositoryRoot;
    private RepositoryConfig config;
    private ObjectNode snapshot;
    private String extractionFailure;

    public JavaScriptLanguageParser(Path adapterDirectory, String nodeExecutable) {
        this(adapterDirectory, nodeExecutable, null);
    }

    /** A trusted caller-selected profile overrides process defaults without mutating global properties. */
    public JavaScriptLanguageParser(Path adapterDirectory, String nodeExecutable, Path analyzerConfig) {
        this.extraction = new JavaScriptExtractionService(adapterDirectory, nodeExecutable);
        this.analyzerConfig = analyzerConfig == null ? null : analyzerConfig.toAbsolutePath().normalize();
    }

    @Override public boolean canParse(File sourceFile) {
        return sourceFile != null && EXTENSIONS.stream().anyMatch(sourceFile.getName()::endsWith);
    }

    @Override public ParserCapability getCapability() {
        return new ParserCapability("javascript-typescript", EXTENSIONS, Set.of(
                "Requires Node >=22 and npm ci in the trusted adapter directory; Java-only scans do not invoke Node.",
                "Express routes and supported HTTP client patterns are static observations, not complete framework or runtime coverage.",
                "Imports, local calls, handler bindings and supported schema/SQL evidence remain qualified architecture evidence.",
                "Backend scope is supplied by the caller; source modules are not inferred deployment boundaries.",
                "Tests, declarations, dependency trees, symlinks, malformed syntax and oversized files are excluded with diagnostics."));
    }

    @Override public void prepareRepository(Path root, RepositoryConfig config) {
        this.repositoryRoot = root.toAbsolutePath().normalize();
        this.config = config;
        this.snapshot = null;
        this.extractionFailure = null;
    }

    /** Returns a defensive copy of the complete repository evidence after the first source parse. */
    public ObjectNode getSnapshot() { return snapshot == null ? null : snapshot.deepCopy(); }

    private void ensureSnapshot() throws IOException, InterruptedException {
        if (repositoryRoot == null) throw new IOException("prepareRepository must be called before JavaScript parsing");
        if (extractionFailure != null) throw new IOException(extractionFailure);
        if (snapshot == null) {
            try {
                Path configuredProfile = analyzerConfig;
                if (configuredProfile == null) {
                    String setting = System.getProperty("cimet.javascript.config");
                    if (setting == null || setting.isBlank()) setting = System.getenv("CIMET_JAVASCRIPT_CONFIG");
                    if (setting != null && !setting.isBlank()) configuredProfile = Path.of(setting);
                }
                if (configuredProfile != null) {
                    snapshot = extraction.extract(repositoryRoot, "backend", config.getRepoName(), configuredProfile);
                } else {
                    // The standalone study's historical defaults must not become assumptions about arbitrary projects.
                    Path neutral = Files.createTempFile("cimet-javascript-profile-", ".json");
                    try {
                        Files.writeString(neutral, "{\"wrappers\":[],\"backendEnv\":[],\"backendOrigins\":[]}");
                        snapshot = extraction.extract(repositoryRoot, "backend", config.getRepoName(), neutral);
                    } finally { Files.deleteIfExists(neutral); }
                }
            } catch (IOException ex) {
                extractionFailure = ex.getMessage();
                throw ex;
            }
        }
    }

    @Override public ParseResult parse(Microservice service, File file, RepositoryConfig config, boolean filterUnknown) {
        try {
            ensureSnapshot();
            Path absolute = file.toPath().toAbsolutePath().normalize();
            if (!absolute.startsWith(repositoryRoot)) return ParseResult.failure("JavaScript source is outside the prepared repository");
            String relative = repositoryRoot.relativize(absolute).toString().replace('\\', '/');
            for (JsonNode diagnostic : snapshot.path("diagnostics")) {
                String code = diagnostic.path("code").asText();
                if (relative.equals(diagnostic.path("file").asText())
                        && (code.startsWith("PARSE_") || "FILE_TOO_LARGE".equals(code))) {
                    return ParseResult.failure("JavaScript source excluded: " + relative + " (" + code + ")");
                }
            }
            // The analyzer's module inventory is authoritative for declarations/test/symlink exclusions.
            boolean included = false;
            for (JsonNode module : snapshot.path("architecture").path("modules")) {
                if (relative.equals(module.path("file").asText())) { included = true; break; }
            }
            if (!included) return ParseResult.success(List.of());
            Path gitPath = Path.of("/" + relative);
            JSModule module = JavaScriptIRConverter.projectModule(snapshot, relative, service, gitPath);
            if (filterUnknown && module.getClassRole() == ClassRole.UNKNOWN) return ParseResult.success(List.of());
            return ParseResult.success(module);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ParseResult.failure("JavaScript analysis was interrupted");
        } catch (IOException | RuntimeException ex) {
            return ParseResult.failure("JavaScript analysis failed: " + ex.getMessage());
        }
    }
}
