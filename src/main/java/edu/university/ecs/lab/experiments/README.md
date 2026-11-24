# Manual parser experiments

These classes are exploratory command-line probes, not production parser adapters
or JUnit tests. They compile with the library, but are not invoked by normal IR
extraction or by `mvn verify`. They require local source files/checkouts supplied
by the operator; they do not clone repositories or install/build target projects.

| Class | Purpose and inputs | Output and limits |
| --- | --- | --- |
| `TreeSitterTest` | Single Java source file supplied as its sole command-line argument. | Prints declarations, annotations, a heuristic role and approximate timing/heap delta. It is a grammar probe; it is not the production Java parser. |
| `JavaParityRunner` | Repository already under `clone/REPO_NAME`; optional repository name defaults to `train-ticket-microservices-test`. | Writes `output/experiments/java-parity-REPO_NAME.json`, comparing JavaParser facts with the exploratory tree-sitter Java port. Agreement with CIMET is not manually labeled extraction accuracy. |
| `PolyglotProbeRunner` | Repository already under `clone/REPO_NAME`; optional name defaults to `microservices-demo`. | Writes `output/experiments/polyglot-REPO_NAME.json` with tree-sitter facts and parse diagnostics. The legacy tree-sitter JS mode is separate from the production TypeScript compiler adapter. |
| `CimetFactsExtractor` | Helper used by `JavaParityRunner`; expects the configured local checkout for symbol resolution. | Flattens the existing Java IR into comparable dimensions. It has no standalone `main`. |
| `TreeSitterJavaExtractor` | Helper used by `JavaParityRunner`; accepts a Java file. | Ports Java extraction heuristics for a comparison baseline. It has no standalone `main` and does not replace JavaParser/type resolution. |

## Build and run

Use a JDK supported by the library, Maven and the platform-specific tree-sitter
native dependencies downloaded by Maven. Run from the repository root:

```sh
mvn -DskipTests package dependency:build-classpath -Dmdep.outputFile=target/experiment-classpath.txt
```

POSIX example:

```sh
experiment_cp="target/classes:$(cat target/experiment-classpath.txt)"
java -cp "$experiment_cp" edu.university.ecs.lab.experiments.TreeSitterTest path/to/Controller.java
java -cp "$experiment_cp" edu.university.ecs.lab.experiments.JavaParityRunner my-java-repo
java -cp "$experiment_cp" edu.university.ecs.lab.experiments.PolyglotProbeRunner my-polyglot-repo
```

PowerShell example:

```powershell
$experimentClasspath = 'target/classes;' + (Get-Content -LiteralPath target/experiment-classpath.txt -Raw).Trim()
java -cp $experimentClasspath edu.university.ecs.lab.experiments.TreeSitterTest path/to/Controller.java
```

Use an existing local checkout under `clone/` for repository-level probes. Inspect
the source and exclusions before interpreting a report: the experiment runners
do not have every production source-size/corpus bound. Do not use them to certify
arbitrary untrusted or very large inputs. The single-file probe's heap difference
is affected by garbage collection and excludes some setup work; it is neither
peak RSS nor evidence of production scalability. The extractors inspect source
only and do not establish deployment boundaries, runtime reachability, framework
completeness or cross-language behavioral equivalence.

Reproducible production regression coverage lives under `src/test/java`, including
the authored mixed-language/native-IR/snapshot-delta fixtures. Public-repository
tests require the separate `network-integration` profile. Experiment outputs and
agreement statistics do not replace those tests or independently labeled truth.
