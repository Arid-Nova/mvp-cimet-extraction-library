# Extensible deterministic source extraction

The default parser registry combines the per-file parser contract from
`16-polyglot-contract` with the repository-aware JavaScript/TypeScript compiler
adapter. A repository may contain several supported languages. Several local or
remote repositories can contribute to one `MicroserviceSystem`. Those statements
describe source extraction, not resolution of arbitrary cross-language calls.

## Parsers and evidence

| Language | Parser | Supported architectural evidence and boundaries |
| --- | --- | --- |
| Java | Existing JavaParser and symbol solver | Existing Spring roles, fields, methods, imports, endpoints, and supported remote calls; unresolved external symbols remain possible. |
| JavaScript / TypeScript | Pinned TypeScript 5.9.3 compiler API | Express registration provenance, imported routers and mount prefixes, chained routes, supported HTTP clients, module dependencies, local function calls, handler bindings, bounded contract/schema and data-access evidence. Dynamic dispatch and arbitrary frameworks remain incomplete. |
| Python | Tree-sitter Python | Heuristic roles, imports, fields/functions/parameters, Flask/FastAPI/Django endpoint patterns, supported HTTP/gRPC observations. No general type resolution. |
| Go | Tree-sitter Go | Types, functions, imports, fields/parameters, recognized net/http/router patterns and HTTP/gRPC observations. No general type resolution. |
| C# | Tree-sitter C# | Heuristic roles, types, imports, fields/methods/parameters, recognized ASP.NET attribute/minimal routes and HTTP/gRPC observations. No Roslyn semantic model. |

JavaScript source discovery includes `.js`, `.jsx`, `.mjs`, `.cjs`, `.ts`, `.tsx`,
`.mts`, and `.cts`. Declaration/test files and dependency/build directories are
excluded. Malformed JavaScript/TypeScript and tree-sitter syntax errors produce
explicit parse failures rather than silently accepting a recovery tree as a
successful extraction. Adapter diagnostics retain excluded-source evidence.

Module-level routes are assigned to native controller containers so native
`Microservice.getEndpoints()` consumers see them. Additional declared types use
portable synthetic identities; every resulting container records its physical
`metadata.sourcePath`. Multiple classes from one physical source file are
returned together in `ParseResult.getClasses()` without implicit service mutation.

JavaScript native `JSModule` metadata records `architectureEvidence`: module
dependencies, functions, resolved local calls, endpoint-handler links, operation
contracts, and supported semantic evidence. Completeness flags and limitations
are preserved. These observations do not classify every JavaScript module as a
Java-style service, repository, or entity. Repository inspection flags are labeled
with their repository scope. Endpoint and HTTP call metadata preserve source line,
handler reference, and resolution evidence; unresolved URLs are ordinary methods
or calls rather than invented concrete endpoints/targets.

## Runtime and local API

Java 21+, Maven, and the tree-sitter runtime are used by the Java library.
JavaScript/TypeScript extraction also requires Node 22+ and the trusted adapter
directory with its pinned dependencies installed:

```sh
npm ci --prefix adapter/javascript --ignore-scripts
npm test --prefix adapter/javascript
mvn verify
```

Java-only scans do not start Node or require an installed JavaScript adapter.
When the library runs outside its checkout, configure the adapter location using
`-Dcimet.javascript.adapter=/trusted/adapter/javascript` or
`CIMET_JAVASCRIPT_ADAPTER`. `-Dcimet.node.executable=/trusted/node` selects the
Node executable. Missing dependencies or process limits produce reported
JavaScript failures; extraction must not be reported as complete after failures.

`mvn verify` runs the local suite without tests that clone live repositories or
depend on external repository revisions. Run `mvn -Pnetwork-integration verify`
to include those explicitly tagged network integration tests. Both modes require
build dependencies to be available from Maven's configured repositories or local
cache; a fresh build may still download its dependencies.

Java and tree-sitter source reads enforce the same per-file 5 MiB bound as the
JavaScript adapter. Oversized files produce explicit failures rather than
successful empty results. The bound covers the read itself as well as the file
size check, including files that grow during analysis.

```java
var repo = new RepositoryConfig(
    new RepositoryBranchPair("https://github.com/example/mixed.git", "main"),
    "PINNED_COMMIT");
var registry = ParserRegistry.createDefault(Path.of("adapter/javascript"), "node");
var input = new IRExtractionService.LocalSource(Path.of("/source/export"), repo, "api");
var system = IRExtractionService.extractLocal("example", List.of(input), registry);
```

`List<LocalSource>` can include several repositories, each with its own pinned
revision and explicit component boundary. One mixed-language checkout needs one
entry; it does not need separate Java and JavaScript entries. Use one input per
repository URL, placing its language subtrees beneath the shared checkout root.
Pass a null component name to use boundary detection for several applications in
that repository. Distinct remote repositories with identical folder basenames
are rejected while the inherited clone cache uses basename directories.

The input is an already exported checkout. No target dependency installation,
build, module loading, or database connection is performed. `api` is an explicit
application boundary; it is not inferred independently for each source module.
For an explicitly identified frontend, the separate JavaScript snapshot API
retains frontend modules as client orphans rather than microservices. Do not
declare frontend roots as backend application inputs merely to obtain a larger
service count.

The integrated JavaScript parser uses a neutral wrapper/origin profile.
Project-specific wrappers and backend environment names require a trusted JSON
profile selected with `-Dcimet.javascript.config=/trusted/profile.json`. The
environment variable `CIMET_JAVASCRIPT_CONFIG` is also supported. Embedded callers
can pass `ParserRegistry.createDefault(adapterDirectory, nodeExecutable, profile)`
or construct `JavaScriptLanguageParser(adapterDirectory, nodeExecutable, profile)`.
The explicit profile takes precedence and does not change global properties. The
standalone study adapter retains its documented historical default profile; its
assumptions are not silently applied by the integrated parser.

## Parser-neutral snapshots and deltas

`DeltaExtractionService.create` re-extracts target revisions through the same
registry and boundary detection used for snapshots. A changed import, mounted
router, or manifest can affect source files that Git did not mark as modified,
so a per-file Java parsing shortcut is insufficient for polyglot deltas.

For already exported snapshots:

```java
var changes = SnapshotDeltaService.compare(before, after);
var merged = MergeService.create(SnapshotDeltaService.copy(before), changes);
```

The delta includes the existing added/deleted/modified source-change records and
an optional complete `targetSnapshot`, plus a canonical `baseFingerprint`. The
target snapshot preserves service-boundary changes, multiple classes from one
physical source file, language-specific metadata, and orphan modules. Merge
checks the fingerprint of the supplied base before using this target. Older
deltas without `targetSnapshot` retain the existing component-merge path. A
rename is represented by a deletion and an addition. Complete snapshots increase
delta payload size; this is a correctness tradeoff, not an incremental performance
claim. Partial snapshots must be repaired before authoritative delta comparison;
an extraction failure does not establish that source was deleted.

## Adding a language

1. Implement `LanguageParser`: extension selection, immutable capability and
   limitation data, and `parse` returning every extracted container in a
   `ParseResult`. Use explicit failure results for unsupported or invalid input.
2. For cross-file analysis, implement `prepareRepository(Path, RepositoryConfig)`.
   Cache analysis within that registry instance and invalidate it on every
   preparation, including checkout changes. Never use an unbounded global cache
   keyed only by repository name. JavaScript starts one bounded Node process per
   prepared checkout, on demand, rather than once per source file.
3. Register the parser in a caller-supplied `ParserRegistry`, or add it to
   `ParserRegistry.createDefault()` when it is supported by the distribution.
   The scanner dispatches per source file, so another language can coexist in the
   same application root. The built-in `FileUtils` lists document built-in source
   discovery and ignored directories.
4. Preserve repository-relative physical paths in `metadata.sourcePath`, stable
   node identities, parent references, source evidence, and limitations. Use
   portable path identifiers, including on Windows.
5. Add parser fixtures and native IR integration tests: positive and lookalike
   negatives, malformed syntax, several declarations per file, mixed-language
   repositories, and repeated snapshot/delta behavior. Deterministic output and
   passing fixtures do not establish measured extraction precision or recall.

Explicit local-root path scopes are restored after each scan, including nested
scopes. They let the existing Java symbol solver and file parsers operate on local
source exports while preserving historical `clone/<repo>` behavior for existing
callers.

## Validation included

`PolyglotRegistryIntegrationTest` exercises per-file language dispatch, native
Express mounts/chained routes, false router lookalikes, aliased Axios and POST
fetch, handler/line provenance, one-process repository caching and invalidation,
syntax/declaration exclusions, portable multi-class identities, Python
module-level endpoint visibility, nested root restoration, and Java-only setup
without Node. Adapter tests also cover `.mts`/`.cts` imports, conservative
provenance, source exclusions, static architecture/contract/data-access evidence,
and deterministic repeatability.

These are synthetic regression checks. They are not an independently labeled
accuracy study or proof of complete framework, runtime, or cross-language
behavior.
