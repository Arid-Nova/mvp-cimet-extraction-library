Contributing
============

Thank you for contributing. This document is a short summary; the canonical guide lives in `/.docs/development/contributing.md`.

Quick checklist before opening a PR
- Ensure all unit tests pass: `./mvnw test`
- Ensure integration tests pass where applicable: `./mvnw verify`
- Run `shellcheck` on changed shell scripts and fix issues
- Add or update documentation in `.docs/` if your change affects behavior or API

PR template expectations
- Clear title and one-line summary
- Link to an issue if applicable
- Short list of changes and rationale
- Testing notes (how to run, sample inputs/outputs)

Coding standards and commit messages
- Keep commits small and focused
- Use imperative present tense in commit messages: "Add feature X", "Fix bug Y"

See `/.docs/development/contributing.md` for the full developer workflow and PR checklist.
