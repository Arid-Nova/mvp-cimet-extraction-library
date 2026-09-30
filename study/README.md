# Local Nutrosal case-study runner

This runner exports pinned Git source objects, invokes CIMET's packaged JVM
JavaScript integration, and prepares evidence for a maintainer-reviewed study.
The checked-in configuration contains repository locations and revision IDs;
it does not contain Nutrosal source or generated study results.

Nutrosal is represented as one backend application and one frontend. Endpoint
counts do not increase its independently deployed backend service count. This
case supports endpoint extraction, frontend/backend consumer linking, and API
evolution research; it does not by itself validate microservice boundary recovery.

## Requirements and invocation

Use Node.js 22+, Java 21+, Git, and the built adapter. From the CIMET checkout:

```sh
npm --prefix adapter/javascript ci --ignore-scripts
mvn -Pjavascript-cli -DskipTests package
node --test study/*.test.mjs
node study/run.cjs --config study/nutrosal.config.json --output ../../artifacts/nutrosal-study/run-2026-09-30
```

The commands are single lines and work in PowerShell as written. npm installs
only CIMET parser dependencies. The runner never installs or executes the target
applications, their package scripts, or their database integrations.

The default sibling checkout layout is:

```text
.research/
  cimet/                 # this repository
  nutrosal-backend/       # existing authorized local clone
  nutrosal_v2/            # existing authorized local clone
```

Repository paths in the JSON configuration are resolved relative to that file.
`--output`, `--jar` and `--adapter` paths are resolved relative to the current
working directory. Output must be a **new directory outside CIMET and the input
repositories**. A directory reached through a symlink is checked using its real
location. Choose a new run directory for each invocation; the runner never
deletes or overwrites an earlier run.

Defaults are `target/cimet-javascript.jar`, `adapter/javascript`, `java`, and
the current Node executable. Override these with `--jar`, `--adapter`, `--java`,
or `--node`. An adapter directory or its `analyzer.cjs` file is accepted.
The JVM defaults to `-Xmx256m`; `--java-heap-mib 512` changes that heap ceiling.
This ceiling does not bound JVM native memory or the Node parser's memory.

## Pinned cohorts

The provided configuration selects these current source snapshots:

| Component | Immutable revision |
| --- | --- |
| Backend | `4273a7e40b77b3df6e9c36db54b23854c8460825` |
| Frontend | `1a1db117564c7fe08c583ae84ad58e4f1f1d579d` |

These revisions were selected from refreshed repository references. The runner
reads the selected Git objects without switching either checkout or including
its uncommitted changes.

The historical discount-code registration cohort uses:

| Component | Before | After |
| --- | --- | --- |
| Backend | `f4cd26b7ed5ca62072e384d000d2fee721bdda73` | `8e2976a02a74ed8319ba4511b8ed5f9ed888295a` |
| Frontend | First parent of the after revision, resolved and recorded by the runner | `d39501130c10d8c3ffdc283894059c8563d6a08a` |

These associations are **candidate source cohorts, not verified deployment or
release pairs**. A maintainer must validate whether these changes belong
together and whether the reported caller/provider relationship is real.

The runner requires full 40-character commit SHAs. The only derived revision
syntax is `{ "parentOf": "FULL_SHA", "parent": 1 }`. Mutable branch names,
empty references, and short hashes are rejected. It disables Git lazy fetching
and interactive prompts; commits and their blobs must already be available
locally. Hydrate an authorized partial clone separately before running it. Adding another
cohort means adding an entry to `historicalCohorts`, with existing component
names and explicit before/after revisions.

An optional per-component `adapterConfig` path passes a parser configuration to
the JVM snapshot command. See the [adapter documentation](../adapter/javascript/README.md)
for its supported settings. The manifest records its hash, not its contents.
The optional `repositoryURL` is passed as native IR provenance. Only plain
HTTP(S) URLs without credentials, query strings or fragments, or symbolic
labels such as `local:nutrition-api`, are accepted. Git remote configuration
is not read. Nutrosal's configuration uses its known GitHub repository URLs.

## Export and execution policy

The exporter uses `git ls-tree` and one `git cat-file --batch` invocation per
snapshot. It reads committed regular blobs only, so a dirty working directory,
staged changes, or a different current branch does not affect the source.
Included extensions are `.js`, `.jsx`, `.mjs`, `.cjs`, `.ts`, `.tsx`, and
`package.json`.

It omits `.env*`, Git symlinks/submodules, logs, uploads, dependency directories,
and build outputs. Unsafe paths are skipped. Files above 4 MiB are omitted;
selected source above 128 MiB causes the run to stop. Manifests report omission
counts and exported file/blob IDs. No Git checkout, reset, clean, hooks,
submodule initialization, or target package execution is used.

Private source snapshots are materialized under the chosen local output's
`source/` directory. Keep the entire output directory local; the source files
can themselves contain sensitive values even though environment/data files
are excluded. The runner prints only run metadata/counts and suppresses child
stdout/stderr in errors. Derived results contain source paths, locations and
fingerprints, not full source payloads. Remove a completed output directory
manually when it is no longer needed; there is no automatic cleanup command.

## Outputs

```text
manifest.json                              # revisions, tool hashes, timings, limits
source/<component>-<commit>/               # local committed source snapshots
results/*.source-manifest.json             # relative paths and Git blob IDs
results/*.snapshot.json                    # JVM-produced parser snapshots
results/*.native-ir.json                   # JVM-produced combined CIMET IR
results/*.delta.json                       # JVM-produced historical deltas
results/*.candidate-links.json             # method/path candidate links
results/*.candidate-change-links.json      # changed endpoint/caller candidates
results/maintainer-validation.csv           # judgments initially blank
```

The runner invokes `snapshot` for each unique component/revision, combines
snapshots using the JVM `ir` command, and compares historical snapshots using
the JVM `delta` command. It does not substitute the independent inventory
script for the packaged integration. Each command and export has a measured
wall time. Parser `stats`, including parser-only peak RSS when available, are
retained in the manifest; combined JVM/Node peak RSS is explicitly unmeasured.
Tool SHA-256 hashes and whether the runner checkout was dirty are also recorded.
The runner captures those hashes before extraction and verifies them at the end;
a tool/configuration or runner revision changed during execution invalidates the
run rather than producing a completed manifest with mixed tool versions.

Candidate classifications are deliberately limited:

| Classification | Meaning |
| --- | --- |
| `exact` | One endpoint candidate shares a literal method/path. |
| `template` | One candidate matches after symbolic path-segment comparison or an `ALL` method. |
| `ambiguous` | More than one endpoint candidate matches, including static/parameter route overlap. |
| `unresolved` | No candidate, unknown/external target, unresolved method/path, or a wholly dynamic route. |

External/unknown targets remain visible with a reason. Query parameters are
excluded from path matching; placeholders such as `{?}` are preserved as
symbolic segments. Optional routes, wildcards spanning multiple segments,
computed methods, and complex URL construction can produce missing or
unresolved links. Candidate matching does not model Express route precedence,
middleware, request/response schemas, runtime reachability, or deployment
routing. Delta classifications do not prove a breaking change or regression.

## Maintainer validation

The CSV contains one row per caller/endpoint candidate, plus unmatched callers.
The four maintainer judgment/evidence fields are intentionally blank. Suggested
workflow:

1. Confirm the source cohorts and relevant deployment/release relationship.
2. Validate candidate links against code and intended behavior; retain rejected
   and uncertain candidates with reasons.
3. Validate the historical endpoint and caller changes, including whether their
   relationship is relevant to the same feature.
4. Independently sample source routes/callers that the extractor missed, rather
   than validating only its reported records.
5. Report precision, recall, unresolved coverage and disagreement after that
   review. Raw endpoint/link counts and agreement with another script do not
   measure accuracy or practical usefulness.

The synthetic tests cover immutable exports, preservation of dirty/staged
working files, exclusion of symlinks/submodules/data directories, output path
checks, symbolic route matching, ambiguity, and blank validation judgments.
