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
