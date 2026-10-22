# Code quality

> 🇪🇸 [Versión en español](../es/calidad.md)

Quality tools of the **project**, not of whoever uses the library: none of this reaches the main jar or its
dependencies.

## Coverage with JaCoCo

JaCoCo is part of the normal build: `prepare-agent` instruments the tests and `report` generates the report in the
`prepare-package` phase.

```bash
./mvnw verify
```

It leaves in `target/site/jacoco/` the HTML report (`index.html`) and `jacoco.xml`, which is the one SonarQube reads.

## Analysis with SonarQube

```bash
./mvnw -Psonar verify
```

The `sonar` profile runs with `maven-failsafe-plugin` the `SonarQubeAnalysisIT` test
(`src/test/java/io/github/eldiegos/bpmncode/quality`) after compiling, testing and generating the coverage. The test:

1. starts an ephemeral SonarQube with Testcontainers (image from the `sonarqube.image` property, by default
   `sonarqube:community`) and waits until `/api/system/status` answers `UP`;
2. changes the `admin` password and creates a token;
3. runs `sonar-maven-plugin` on the already compiled project, with `target/site/jacoco/jacoco.xml` as coverage;
4. waits for the server to process the analysis and writes the report;
5. if requested, keeps the server open for some minutes (see [Reviewing it in the browser](#reviewing-it-in-the-browser));
6. removes the container.

Requirements: Docker running and about 2-3 GB of free memory for the container. Another image:

```bash
./mvnw -Psonar verify -Dsonarqube.image=sonarqube:<tag>
```

### Report

In `target/sonar/`:

| File | Content |
|---|---|
| `issues.txt` | quality gate, metrics, number of issues and security hotspots, and one line per open issue (severity, type, rule, file:line, message) |
| `measures.json` | metrics: coverage, bugs, vulnerabilities, code smells, duplication, lines, ratings |
| `quality-gate.json` | quality gate status |
| `hotspots.json` | security hotspots |
| `scanner.log` | scanner output |
| `url.txt` | only with `sonar.keepOpenMinutes` > 0: URL of the project dashboard and the `admin` user with its password |

The first lines of `issues.txt` are also printed to the console.

### Reviewing it in the browser

```bash
./mvnw -Psonar verify -Dsonar.keepOpenMinutes=30
```

The `sonar.keepOpenMinutes` property (from `pom.xml`, `0` by default: the container is closed when it finishes) keeps
SonarQube open for N minutes after writing the report. It writes to `target/sonar/url.txt` the dashboard URL
(`http://localhost:<port>/dashboard?id=bpmncode`, with the port assigned by Testcontainers) and the `admin` user with
the password set by the test, and prints it to the log:

```
SonarQube stays open for 30 min at http://localhost:<port>/dashboard?id=bpmncode (admin / <password>)
```

After that time the container is removed as usual; meanwhile the build waits.

### When it fails

It does not fail because of the issues: they are the report. It fails if the server does not start (5-minute wait), if
the scanner ends with an error (see `scanner.log`) or if the analysis is not processed within 5 minutes or does not end
in `SUCCESS`.

### Workflow

Analyze, fix the issues in `issues.txt` and analyze again. Each run starts from a new server, with no history.

## Outside the library

Testcontainers is a `test` dependency (not transitive) and the `io.github.eldiegos.bpmncode.quality` package is
excluded from the published test jar (classifier `tests`). The `release` profile does not run the analysis (see
[Publishing](publishing.md)).
