Testing
=======

Unit tests
----------
- Use JUnit Jupiter (5.x). Run: `./mvnw test`.

Integration tests
-----------------
- Integration tests use maven-failsafe-plugin and are executed in the `verify` phase. Run: `./mvnw verify`.

Schema generation
-----------------
- The `JsonSchemaService.writeSchemas()` method writes JSON Schema files to `./.docs/schema/`.

CI
--
- CI should run `./mvnw -B -DskipTests=false verify` to run unit and integration tests and generate reports.
