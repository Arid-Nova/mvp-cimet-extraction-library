Fixture for `ServiceBoundaryDetectorTest.skipsIgnoredGeneratedDirectories`.

The repository root deliberately has no boundary markers (no build file,
Dockerfile, application config, `src/main/java`, `Chart.yaml`, or deployment
directory). All service-shaped evidence is placed inside generated/ignored
directories (`build/`, `node_modules/`) which the detector's
`IGNORED_DIRECTORIES` set must skip. The expected detection result is an
empty candidate list.
