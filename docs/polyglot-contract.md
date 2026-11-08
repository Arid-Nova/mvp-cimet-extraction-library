# Polyglot Extraction Contract

This document covers the public contract for extracting an intermediate representation (IR) from
Python, Go, C#, and JavaScript repositories, alongside the library's original Java support. It is
written for contributors extending language support and for users running the pipeline against
polyglot systems.

Java's extraction (`JavaLanguageParser`, wrapping the existing JavaParser + symbol-solver pipeline) is
unchanged by this work. The other four languages are parsed with [tree-sitter](https://tree-sitter.github.io/tree-sitter/)
grammars instead of a semantic/symbol-resolving parser - see **Limitations** below for exactly what
that trades away.

## 1. Languages, extensions, and recognized frameworks

| Language | Source extensions | Parser | Frameworks observed/handled |
| --- | --- | --- | --- |
| Java | `.java` | `JavaLanguageParser` (JavaParser + symbol solver) | Spring Boot / Spring MVC |
| Python | `.py` | `PolyglotLanguageParser(PYTHON)` (tree-sitter) | Flask, Sanic, FastAPI |
| Go | `.go` | `PolyglotLanguageParser(GO)` (tree-sitter) | gin, gorilla/mux, net/http |
| C# | `.cs` | `PolyglotLanguageParser(CSHARP)` (tree-sitter) | ASP.NET Controllers |
| JavaScript | `.js`, `.jsx`, `.mjs`, `.cjs` | `PolyglotLanguageParser(JAVASCRIPT)` (tree-sitter, TSX grammar) | Express |

A file's language parser is selected via `ParserRegistry.findParserFor(file)`, which asks each
registered `LanguageParser.canParse(file)` in turn (`IRExtractionService`'s constructor registers one
`JavaLanguageParser` plus one `PolyglotLanguageParser` per non-Java language). Adding a sixth language
means implementing `LanguageParser` and registering one more instance there - no other wiring changes.

JavaScript is parsed with the **TSX** grammar, not the plain JavaScript grammar - TSX is a strict
superset (JSX + Flow/TS type syntax) and fixed real-world parsing failures the plain grammar had on
ordinary `.js` files.

## 2. Project markers (service-boundary detection)

`ServiceBoundaryDetector` decides which directories are independently-deployable microservices. A
directory qualifies if it has a recognized manifest file, judged per-ecosystem (not by an additive
score crossing a threshold - see the Oct 2026 rewrite in git history for why):

| Language | Manifest | Additional requirement to qualify |
| --- | --- | --- |
| Java | `pom.xml`, `build.gradle`, `build.gradle.kts` | One of: `@SpringBootApplication`, `@Controller`/`@RestController`, an application config file, a Dockerfile, docker-compose with services, a k8s Deployment/Helm chart - guards against Maven/Gradle multi-module shared-library submodules |
| Go | `go.mod` | None - a Go module is already one buildable unit |
| Python | `requirements.txt`, `pyproject.toml` | A Dockerfile, or a recognizable entry-point file (`app.py`, `main.py`, `manage.py`, `wsgi.py`, `asgi.py`, `run.py`, `server.py`) - guards against a bare test-harness directory with its own `requirements.txt` |
| C# | `*.csproj` | A co-located `Program.cs` - guards against test projects (xUnit/NUnit/MSTest) and shared-code libraries |
| JavaScript | `package.json` | An entry point (`main`, `bin`, `scripts.start`, or `scripts.server`) - guards against a pure config/workspace-root `package.json` |

A directory can carry more than one manifest at once (e.g. a C# web app with a Vite/webpack frontend
bundler has both its own `.csproj` and its own `package.json`) - each manifest present is judged
independently, and the directory qualifies if any one of them does.

Directories named `.git`, `.gradle`, `.idea`, `.mvn`, `build`, `node_modules`, `out`, `target`,
`wwwroot`, `static`, `assets`, `Assets`, or `public` are never scanned or treated as a service root
(`FileUtils.IGNORED_DIRECTORY_NAMES`) - these are dependency trees, build output, VCS metadata, or
vendored/bundled frontend assets, never backend service code, in every framework observed so far.

## 3. Outbound call clients recognized (service-to-service edges)

Beyond endpoints, the extractor attributes outgoing calls to the method that makes them
(`httpCalls`/`rpcServers`/`rpcClients` in the tree-sitter facts, mapped to `RestCall`/`MethodCall` in the
IR - the same shape CIMET uses for Java's own call graph):

| Pattern | Language(s) | Example |
| --- | --- | --- |
| gRPC generated client stub construction | Go, others | `pb.NewCartServiceClient(conn).GetCart(...)` |
| Typed HTTP client interface (Refit-style) | C# | `[Get("/vehicles")] Task<List<Vehicle>> GetVehicles();` |
| `httpx`/`requests` calls | Python | `httpx.AsyncClient().get(...)` (seen proxying an API gateway to 4 backend services) |
| `axios`/`fetch`/raw `http.request` | JavaScript | not yet exercised by a ground-truth repo - recognized by the same dimension, unverified in practice |
| Message-queue produce/consume (RabbitMQ) | JavaScript (Node/amqplib) | a distinct call type from REST/gRPC - a producer and its consumer are two different services, cyclic in practice |

## 4. Language-to-IR mapping

`PolyglotFactsToIrConverter` maps a tree-sitter `FileFacts` result into the **same** IR object graph
Java already produces (`AbstractClass`/`JClass`/`JInterface`, `Field`, `Method`, `Endpoint`, `Import`,
`Annotation`, `RestCall`) - no new IR types were introduced, and no existing IR schema changed.

Mapped per class/file: type + role (`classRole`, via name/annotation-pattern matching, not type
resolution), fields, methods + parameters, imports, annotations, endpoints, and outgoing calls
attributed to their owning method. One real IR class/struct/interface is produced per type a file
declares (not just the first), each separately and correctly attributed.

**Not mapped** (deliberately, see Limitations): resolved/simplified types for fields and parameters -
only the literal source text; the resolved type of an outgoing call's receiver (e.g. what a chained
method call like `station.get()` returns).

## 5. Schema compatibility

**No schema changes were made or are needed.** The polyglot work is additive at the parser-registration
layer only (`ParserRegistry`); it reuses the existing `MicroserviceSystem`/`Microservice`/`AbstractClass`
JSON shape documented in `docs/MicroserviceSystemSchema.json` and `docs/SystemChangeSchema.json`
unchanged. A consumer of the existing JSON schema needs no migration to read IR produced from a
polyglot repository - the only difference is which languages' files now populate it.

## 6. Limitations (static/pattern-based parsing)

Tree-sitter is a syntax parser, not a semantic one - it has no symbol table and cannot resolve types
across files or through a build. This is a deliberate trade-off (see §7): it works without the target
repository building or its dependencies resolving, which a symbol-solving parser generally requires for
full accuracy.

Concretely, for Python/Go/C#/JavaScript:
- **Field/parameter types are literal source text**, not resolved or simplified (e.g. a Python parameter
  annotated `list[Product]` stays exactly that string).
- **The type an outgoing call's target resolves to isn't tracked.** Java's own tree-sitter parser
  (used for the parity comparison below) only gets this right ~75% of the time itself with a full
  symbol solver available - it isn't attempted for the other four languages at all.
- **Role classification is name/annotation-pattern based** (`@RestController`, `[ApiController]`,
  `@app.route`, a class named `*Controller`), not type resolution - it will misclassify unconventionally
  named code.
- **Java specifically**: resolving the type an external (library) method call is made on can fail;
  JavaParser's symbol solver falls back to an unresolved-symbol placeholder in that case.

## 7. Why tree-sitter (not per-language semantic tools)

A semantic/symbol-resolving parser per language (e.g. a language server) needs the target repository to
actually build and its dependencies to resolve - unreliable when extracting IR from arbitrary historical
commits, which is this library's primary use case. Tree-sitter trades resolved-type depth for working
on any syntactically-valid snapshot of the code, build or no build. A proof-of-concept using a real
language server (jdtls via LSP4J) confirmed it *can* recover the one dimension tree-sitter can't (a
chained call's resolved receiver type), at a real cost (~1+ minute startup/indexing per run) - not yet
integrated; see git history for the experiment.

## 8. Benchmarks

### 8a. Reproducing a run

```
cd mvp-cimet-extraction-library
mvn -q compile -DskipTests
CP="target/classes:$(cat cp.txt)"   # regenerate cp.txt after any pom.xml change:
                                     # mvn dependency:build-classpath -Dmdep.outputFile=cp.txt

cat > config.json <<'EOF'
{
  "systemName": "<name>",
  "systemRepositories": [
    { "repoBranchPair": { "repositoryURL": "<git url>", "branchName": "<branch>" },
      "commitID": "<commit sha>" }
  ]
}
EOF

/usr/bin/time -l java -cp "$CP" edu.university.ecs.lab.Runner
# real/user/sys time and peak memory are in the time -l output;
# the IR is written to output/output-ir.json and output/PART_<repo>_<branch>_<commit>.json
```

Repos already cloned under `clone/` are reused as-is (no network clone); a repo not yet present is
cloned fresh as part of the run.

### 8b. Dataset

8 non-Java repos (2 per language: one small/simple sanity check, one larger/realistic system with 3+
microservices), plus mixed-language runs against repos containing several languages at once. Full
per-repo endpoint, call-graph, structural-count, and performance data: `ground_truth_summary.xlsx`
(not committed to this repo - a working document, see `.gitignore`).

### 8c. Java runtime/memory regression check (this epic's acceptance criterion)

Measured by isolating `IRExtractionService.recursivelyScanFiles` (the per-file scan phase only,
holding the file set constant) against `train-ticket-microservices-test` (1,300+ files, pure Java),
comparing the pre-epic baseline commit (`0537dd0`, via a `git worktree` checkout) against current, 3
runs each:

| | Baseline | First measurement | After fix |
| --- | --- | --- | --- |
| Scan phase time | ~2.6s | ~11.0s (**4.2x regression**) | ~2.4s (**within noise, ~0%**) |

The initial 4.2x regression was real and reproducible (tight across 3 runs each), root-caused via a
controlled swap (same code / different classpath, same classpath / different code) to a single cause:
adding `.js` as a recognized source extension (needed for JavaScript) also matched a Java repo's
`static/assets/js/` folder of **vendored frontend libraries** (a 1.2MB `angular.js` among several
minified bundles) - these were newly sent through tree-sitter JS parsing on every run, for no useful
IR output. Fixed by adding `static`/`assets`/`public` to the shared ignored-directories list (§2) - the
same category of fix as excluding ASP.NET's `wwwroot`, just a different framework's convention for the
same thing. Verified: the existing 24-case `ServiceBoundaryDetectorTest` suite and the full 38-case
project test suite show no regressions from this fix, and all 9 real ground-truth repos re-ran with
identical detected-microservice results before and after.

**Memory.** OS-level resident set size was not reliably measurable this way - single-run readings on
this machine varied by up to 2x for the *identical* baseline command rerun minutes apart, most likely
JIT/GC-timing noise on a short-lived (2-13s) process. Switched to a steadier metric instead: JVM heap
actually retained by the resulting IR object graph (`Runtime.totalMemory() - freeMemory()`, sampled
after a forced `System.gc()`, with fixed `-Xms512m -Xmx3g -XX:+UseSerialGC` flags to remove GC-timing
variance), 5 runs each:

| | Baseline (median of 5) | Current (median of 5) | Change |
| --- | --- | --- | --- |
| Scan phase time | 2,269 ms | 2,215 ms | **-2.4%** |
| Heap retained | 34.58 MB | 32.31 MB | **-2.0%** |

Both runs were tight across all 5 iterations (heap retained varied by well under 1% run-to-run with
this method, versus the 2x swings seen with raw OS-level RSS). Current is marginally *better* than
baseline on both counts - expected, since the static/assets fix above also means a few fewer
non-source files get scanned per run (1,315 vs 1,338 files - the vendored JS bundles no longer counted
at all).

**Status against the epic's acceptance criterion: met.** Java shows no measurable runtime or memory
regression - comfortably within the 5% budget on both counts, verified with a repeatable, low-variance
method rather than a single noisy sample.
