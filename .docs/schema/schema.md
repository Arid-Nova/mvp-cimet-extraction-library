Schema Documentation
====================

Location: `/.docs/schema/`

Files
- `MicroserviceSystemSchema.json` — JSON Schema describing the IR (MicroserviceSystem).
- `SystemChangeSchema.json` — JSON Schema describing SystemChange (delta) objects.

Current IR contract
-------------------
These files document the current **multi-repository** `MicroserviceSystem` and
`SystemChange` Java models used by AridNova's native extraction path. They are not
the original IR or the intermediate single-repository schema. Repository identity
and revision live on each microservice (`repositoryURL`, `commitID`); there is no
single root repository/commit. Deltas use repository-indexed `oldCommits` and
`newCommits` maps.

The root `version` field is an optional application/system version, not a schema
generation number. This documentation does not reintroduce either retired format
as a current output target. Compatibility readers/tests for historical saved IRs
support migration; they do not designate an old schema as the current contract.

AridNova can add root fields such as `javascriptExtraction` and capability/source
metadata around the native model. Those extension contracts are documented in
the pipeline and JavaScript guides; this generated model schema alone does not
validate the entire enriched pipeline response.

Regeneration
------------
Run the Java generator: `JsonSchemaService.writeSchemas()` (writes to `./.docs/schema/` by default).

Contract regression
-------------------
The generated artifacts are model documentation, rather than a complete strict
validator for every pipeline extension. Check current generation and multi-repo
serialization with:

```sh
mvn -Dtest=CurrentMultiRepositorySchemaTest test
```

The test compares both checked-in schemas with fresh generation and round-trips
two repositories with different commits through the actual IR writer/reader.

Semantics & Invariants
----------------------
These notes provide a concise, human-readable summary of assumptions captured by the JSON Schemas:

- MicroserviceSystem.name is a short identifier for the scanned system (no path separators).
- Each Microservice records its repository `commitID`; MicroserviceSystem has no single system-wide revision field.
- Every Microservice entry should include a `path` that is relative to the repository root used during extraction.
- Controller/Service/Repository `name` fields identify declarations; `metadata.sourcePath` retains physical source when a declaration has a synthetic path. `packageName` is an optional JVM package identifier when available.
- SystemChange uses `oldCommits` and `newCommits` maps for repository revisions; `changes` may be empty for no-op diffs.
- Registry-backed snapshot deltas additionally carry `targetSnapshot` and `baseFingerprint`; merge validates the supplied base before applying that complete target.
- Consumers should treat the schemas as guidance: fields marked optional by the schema may be absent when information can't be resolved from source code.

If you need to publish these invariants as machine-checkable rules, add a small JSON Schema fragment alongside the generated schemas under `./.docs/schema/` and reference it from your CI checks.
