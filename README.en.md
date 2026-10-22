# bpmncode

> 🇪🇸 [Versión en español](README.md)

**BPMN 2.0 processes written in Java: the annotated code is the single source of truth.**

`bpmncode` is a library for Spring Boot applications that turns annotated Java methods into BPMN 2.0 processes. There
are no hand-maintained `.bpmn` files and no process engine: the diagram is derived from the code, the model is
validated at compile time and at startup, and at runtime it checks that the code follows the declared flow and stores
the evidence of each step.

```java
@Process(id = "PER-REGISTRATION", name = "Person registration", version = 1)
public class PersonRegistration {

	@StartEvent(id = "REQUEST-RECEIVED", name = "Request received",
			outgoing = @SequenceFlow(targetRef = "ELIGIBILITY"))
	public Result register(Request request) { … }

	@ExclusiveGateway(id = "ELIGIBILITY", name = "Meets the rules?", businessRuleRefs = "R-PER-001",
			outgoing = { @SequenceFlow(when = "ELIGIBLE", targetRef = "CREATE-PERSON", name = "Yes"),
					@SequenceFlow(when = "NOT_ELIGIBLE", targetRef = "REJECTED", name = "No") })
	Eligibility assess(…) { … }

	@ServiceTask(id = "CREATE-PERSON", name = "Register person", outgoing = @SequenceFlow(targetRef = "COMPLETED"))
	Person create(…) { … }

	@EndEvent(id = "COMPLETED", name = "Registration completed")
	Result complete(…) { … }

	@EndEvent(id = "REJECTED", name = "Registration rejected")
	Result reject(…) { … }
}
```

## What it offers

- **Annotations with the BPMN 2.0.2 vocabulary**: `@Process`, `@StartEvent`, `@MessageStartEvent`,
  `@TimerStartEvent`, `@ServiceTask`, `@UserTask`, `@BusinessRuleTask`, `@SendTask`, `@ExclusiveGateway`,
  `@SequenceFlow`, `@EndEvent`, `@MessageEndEvent`, `@ErrorEndEvent`, `@Message`, `@ProcessError`, `@ProcessResource`,
  `@PotentialOwner`, `@HumanPerformer`, `@BusinessRule`…
- **Validation at two moments**: an annotation processor makes `javac` fail with the errors local to each element, and
  at startup the full model is validated (reachability, cycles with no way out, references, unique ids…). An invalid
  model prevents the application from starting.
- **Execution with conformance, without an engine**: `ProcessJournal` records each step and rejects those that do not
  follow a declared sequence flow. Instances and their history are kept in memory, without a database, or in the
  application's database, with JPA or with JDBC, if it has one.
- **Versions**: of processes (several coexist; each instance follows its own), of business rules and of the messages
  between processes, with contracts frozen by the fingerprint of their JSON schema.
- **BPMN 2.0 XML export** (semantic model and BPMN DI with automatic layout), valid against the OMG XSDs, ready for
  [bpmn.io](https://bpmn.io).
- **Traceability** Process → Flow node → Rule → Code → Message, with catalogs of where each rule is used and of the
  message contracts, and **internationalization** of every text in the model.
- **Execution statistics**: heatmap of each version of a process, path followed by each instance, instances waiting
  per node and counts per version.
- **Optional web module**, off by default: bpmn-js viewer with the card of each element, the path of an instance and
  the heatmap, plus a read-only API.
- **Checks for your tests** (`ProcessAssertions`, in the test jar): BPMN valid against the OMG XSDs, every drawn
  element described and the path of an instance.

## Requirements

| | Version |
|---|---|
| Java | 21 or later (compiled for 21; also tested on 25) |
| Spring Boot | 4.1 |
| Database | optional: without one, instances are kept in memory; with JPA, or with a `DataSource` and `spring-jdbc`, in two tables |

## Installation

```xml
<dependency>
    <groupId>io.github.eldiegos</groupId>
    <artifactId>bpmncode</artifactId>
    <version>0.1.0</version>
</dependency>
```

And, for the compile-time checks, the annotation processor in the `maven-compiler-plugin`:

```xml
<annotationProcessors>
    <annotationProcessor>io.github.eldiegos.bpmncode.processor.ProcessAnnotationProcessor</annotationProcessor>
</annotationProcessors>
```

The library configures itself through Spring Boot auto-configuration: it discovers the processes in the application's
packages and provides `ProcessDefinitionRegistry`, `ProcessJournal`, `ProcessInstanceHistory`, `TraceabilityReport`,
`MessageContractCatalog`, `ProcessExecutionStatistics` and `BpmnDiagramService` as beans. It only depends on `spring-boot-autoconfigure` and `slf4j-api`: it works in an
application without a database, without JPA and without Spring Modulith. If the application already has JPA, or
`spring-jdbc` and a `DataSource`, it stores the instances in its database ([Storage](docs/en/storage.md)). If it
uses Spring Modulith, it can verify that it only uses the library's public API
([Configuration](docs/en/configuration.md#spring-modulith)).

## Documentation

Starting from scratch? Follow the [step-by-step course](docs/en/course/README.md), which builds a complete application.

| Guide | Content |
|---|---|
| [Getting started](docs/en/getting-started.md) | dependencies, annotation processor and a complete process step by step |
| [Annotations](docs/en/annotations.md) | each annotation, its BPMN element, its attributes and the rules that bind it to the code |
| [Model validation](docs/en/validation.md) | compile-time and startup checks, and every error kind |
| [Execution and conformance](docs/en/execution.md) | `ProcessJournal`, instances, evidence, loops, history, transactions and idempotency |
| [Storage](docs/en/storage.md) | in memory, in a database (JPA or JDBC) or custom; tables, schema, transactions and how to test a custom store |
| [Messages between processes](docs/en/messages.md) | sealed versions, frozen contracts and the message header |
| [Versions](docs/en/versions.md) | process and business rule versions |
| [BPMN, traceability and i18n](docs/en/bpmn-traceability-i18n.md) | BPMN 2.0 export, `pac:` extensions, traceability report and text translation |
| [Statistics and web module](docs/en/statistics-web.md) | heatmap, path of an instance and counts; optional bpmn-js viewer and read-only API |
| [Configuration and integration](docs/en/configuration.md) | beans and how to replace them, properties, optional dependencies, Spring Modulith and application tests |
| [Internal architecture](docs/en/architecture.md) | packages, public and internal API, how the model is read |
| [Publishing](docs/en/publishing.md) | how a version is published to Maven Central |
| [Code quality](docs/en/quality.md) | coverage with JaCoCo and analysis with SonarQube in a container |

## Building

```bash
./mvnw verify                              # compiles and runs the tests
./mvnw -Prelease verify -Dgpg.skip         # also sources and Javadoc jars
./mvnw -Psonar verify                      # also SonarQube analysis (needs Docker)
```

## Status

Version `0.x`: the API may change between minor versions until 1.0.

## License and attribution

Distributed under the [Apache License 2.0](LICENSE). You may use, modify and redistribute `bpmncode`, also in
commercial products, as long as you keep the copyright notice and the [`NOTICE`](NOTICE) file, which attributes the
original project. Both are also inside the jar, in `META-INF/`. If you cite it in a work, use the data in
[`CITATION.cff`](CITATION.cff) (GitHub offers it under "Cite this repository").
