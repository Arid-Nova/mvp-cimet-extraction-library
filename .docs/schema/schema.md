Schema Documentation
====================

Location: `/.docs/schema/`

Files
- `MicroserviceSystemSchema.json` — JSON Schema describing the IR (MicroserviceSystem).
- `SystemChangeSchema.json` — JSON Schema describing SystemChange (delta) objects.

Regeneration
------------
Run the Java generator: `JsonSchemaService.writeSchemas()` (writes to `./.docs/schema/` by default).

Validation Example
------------------
Using ajv-cli (npm):

```
ajv validate -s .docs/schema/MicroserviceSystemSchema.json -d output/TestIR.json
```

Semantics & Invariants
----------------------
These notes provide a concise, human-readable summary of assumptions captured by the JSON Schemas:

- MicroserviceSystem.name is a short identifier for the scanned system (no path separators).
- Each Microservice records its repository `commitID`; MicroserviceSystem has no single system-wide revision field.
- Every Microservice entry should include a `path` that is relative to the repository root used during extraction.
- Controller/Service/Repository `name` fields are file names; `packageName` is an optional JVM package identifier when available.
- SystemChange uses `oldCommits` and `newCommits` maps for repository revisions; `changes` may be empty for no-op diffs.
- Registry-backed snapshot deltas additionally carry `targetSnapshot` and `baseFingerprint`; merge validates the supplied base before applying that complete target.
- Consumers should treat the schemas as guidance: fields marked optional by the schema may be absent when information can't be resolved from source code.

If you need to publish these invariants as machine-checkable rules, add a small JSON Schema fragment alongside the generated schemas under `./.docs/schema/` and reference it from your CI checks.
