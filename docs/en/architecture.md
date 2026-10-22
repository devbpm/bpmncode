# Internal architecture

> 🇪🇸 [Versión en español](../es/arquitectura.md)

## Packages

Everything lives under `io.github.eldiegos.bpmncode`. The packages marked as public form the API and each one is a
named interface of the Spring Modulith module `bpmncode`; the internal ones may change between `0.x` versions.

| Package | API | Content |
|---|---|---|
| `annotation` | public | the annotations: `@Process`, `@ProcessPart`, `@StartEvent`, `@MessageStartEvent`, `@TimerStartEvent`, `@ServiceTask`, `@UserTask`, `@BusinessRuleTask`, `@SendTask`, `@StandardLoop`, `@PotentialOwner`, `@HumanPerformer`, `@ExclusiveGateway`, `@SequenceFlow`, `@EndEvent`, `@MessageEndEvent`, `@ErrorEndEvent`, `@Message`, `@MessageVersion`, `@ProcessError`, `@ProcessResource`, `@BusinessRule` |
| `model` | public | validated, immutable semantic model: `Definitions` (≈ `bpmn:definitions`), `ProcessDefinition`, `ProcessDefinitionRef`, `FlowNode` (`Event` → `StartEvent`/`EndEvent`, `Activity` → `Task` → `ServiceTask`/`UserTask`/`BusinessRuleTask`/`SendTask`, `Gateway` → `ExclusiveGateway`), `SequenceFlow`, `StandardLoopCharacteristics`, `EventDefinition` (`MessageEventDefinition`, `TimerEventDefinition`, `ErrorEventDefinition`), `MessageFlow`, `Message`, `MessageVersionDefinition`, `MessageSchema`, `MessageContractLock`, `MessageContractCatalog`, `ErrorDefinition`, `ResourceDefinition`, `ResourceRole` (`Reference`, `Expression`), `BusinessRuleDefinition`, `BusinessRuleRef`, `BusinessRuleUsage`, `ModelViolation`, `ViolationKind`, `ProcessDefinitionRegistry` |
| `model.reader` | internal | `AnnotationProcessModelReader` and its phases |
| `runtime` | public | `ProcessJournal`, `ProcessInstanceHistory`, `ProcessInstanceRef`, `CorrelationKey`, `ProcessMessage`, `MessageHeader`, `MessageId`, `ExchangedMessage`, `FlowNodeOutcome`, `HistoricFlowNodeInstance`, `ProcessInstanceSummary`, `ProcessStart`; the store: `ProcessInstanceStore`, `ProcessInstanceRecord`, `FlowNodeInstanceRecord`, `InMemoryProcessInstanceStore`, `JdbcProcessInstanceStore`, `ProcessStoreProperties`, `StoredReferences`; exceptions `ProcessConformanceException`, `DuplicateProcessInstanceException`, `ProcessInstanceConflictException` |
| `runtime.jpa` | internal | `JpaProcessInstanceStore` and its two entities; only loaded if the application has JPA |
| `statistics` | public | `ProcessExecutionStatistics`, `ExecutionHeatmap`, `InstancePath`, `InstanceCounts`, `ProcessStatisticsAutoConfiguration` |
| `trace` | public | `TraceabilityReport`, `TraceabilityEntry` |
| `i18n` | public | `LabelResolver`, `MessageSourceLabelResolver` |
| `bpmn` | public | `BpmnDiagramService`, `BpmnDiagram`, `DiagramElement`, `BpmnXmlExporter`, `BpmnProperties`; internal writers and layout |
| `web` | internal | optional web module: `ProcessWebAutoConfiguration`, `ProcessWebProperties`, the viewer and API controllers and the viewer itself (`viewer.html`, `static/`); only loaded if the application is a web application with Spring MVC and enables it |
| `processor` | used by `javac` | `ProcessAnnotationProcessor` and its rules |

Dependencies between packages:

- `annotation` depends on nothing;
- `model.reader` reads `annotation` and produces `model`;
- `runtime`, `trace`, `i18n` and `bpmn` use `model`, and `bpmn` also uses `i18n`;
- `runtime.jpa` implements the `runtime` store;
- `statistics` aggregates the evidence of `runtime` over `model`;
- `web` only uses the public API: `model`, `runtime`, `statistics`, `trace`, `i18n` and `bpmn`;
- the model auto-configuration (`ProcessModelAutoConfiguration`) ties `model.reader` and `trace` together;
- `processor` depends on none of them: it recognises the annotations by name.

## From code to model

`ProcessModelAutoConfiguration` creates `ProcessDefinitionRegistry` with `AnnotationProcessModelReader.scan`, which:

1. **Finds the classes** (`AnnotatedClassScanner`): loads, without initialising, every type in the application's
   packages and keeps those with metamodel annotations, also on private methods and in classes that forgot their
   `@Process`, so it can report them.
2. **Reads the declarations**, each with its own component: messages and their versions, with the JSON schema and
   the contract file check (`DeclaredMessages`, `MessageSchemaWriter`); errors (`DeclaredErrors`); resources and their
   parameters (`DeclaredResources`); rules (`DeclaredBusinessRules`); processes and parts (`DeclaredProcesses`,
   `ProcessMembership`).
3. **Turns each annotated method into a node** (`FlowNodeParser`), following *parse, don't validate*: it either
   produces a valid `FlowNode` or reports the problem.
4. **Builds the graph** of each version of each process (`ProcessGraphBuilder`): one start event, existing targets,
   reachability and a path to an end event.
5. **Links the published messages** to the processes they start (`MessageFlowLinker`).
6. If `ModelDiagnostics` has collected any problem, it throws `ProcessModelException` with all of them. Otherwise it
   returns `Definitions`.

Classes are processed sorted by name and every list in the model is sorted, so the model, the BPMN and the
fingerprints are deterministic.

## From model to BPMN

`BpmnXmlExporter` computes the diagram scope (`DiagramScope`: which processes and versions, which collapsed pools),
writes the semantic model (`SemanticModelWriter`) and then the diagram interchange (`DiagramInterchangeWriter`). The
layout assigns layers from left to right (`LayerAssignment`), computes rectangles with the BPMN size of each node type
(`ProcessLayout`) and routes the flows with orthogonal segments (`EdgeRouting`).

## Execution

`ProcessJournal` loads the instance from the store (`ProcessInstanceRecord`) into an internal `ProcessInstance`,
which holds the token and the state and checks each advance (`ProcessInstance.checkAdvance`) against the
`ProcessDefinition` of its version. If the advance is valid, the journal saves the new state with optimistic locking
and appends a `FlowNodeInstanceRecord`; otherwise it saves nothing. Reading always goes through
`ProcessInstanceHistory`, which sorts and returns immutable records; an instance whose version is no longer deployed
can still be read, without nodes to advance to. At startup, `UndeployedVersionsCheck` compares
`ProcessInstanceStore#activeVersions()` with the definition registry according to
`pac.process.store.undeployed-versions`.

The store (`ProcessInstanceStore`) knows nothing about conformance: it only saves and reads, with the guarantees of its
contract (one instance per process and correlation, optimistic locking and each position of the history only once).
That way the same logic
works in memory, with JDBC, with JPA or with an application store. The auto-configuration only loads the JDBC classes
if `spring-jdbc` is on the classpath, and the JPA ones if JPA and `spring-orm` are. `JpaEntityPackageRegistrar` adds
the `runtime.jpa` package to the auto-configuration packages, without loading any JPA class, so that the
application's persistence unit manages the store's entities.

## Design decisions

- **No engine**: control flow is Java; the library records and checks. There are no parallel tokens, intermediate
  events or timers of its own.
- **Code is the single source**: nothing is declared twice. The BPMN, the traceability and the documentation are
  derived from the annotations.
- **Fail early**: better in `javac` than at startup, and better at startup than at runtime.
- **BPMN vocabulary**: the names of annotations, attributes and model types are those of BPMN 2.0.2; whatever BPMN
  does not define carries the `pac:` prefix.
- **Minimal dependencies**: only `spring-boot-autoconfigure` and `slf4j-api`. `spring-jdbc`,
  `jakarta.persistence-api`, `spring-orm`, `spring-tx`, `spring-webmvc` and `jakarta.servlet-api` are optional: they
  are used if the application already has them. `spring-modulith-api` is optional too: it only provides the annotations Modulith uses to verify the library's
  boundaries. Neither JPA nor a database is required.

## Library tests

The tests use their own sample processes (`src/test/java/fixture/process`): `ValidProcesses`, `BrokenProcesses`
(deliberate errors, which is why the library compiles its sources with `<proc>none</proc>`) and `VersionedMessages`.
The exported BPMN is validated against the OMG XSDs (`src/test/resources/bpmn/xsd`), and the annotation processor is
tested by compiling sources with the `javax.tools` API. `ProcessInstanceStoreContract` gathers the journal tests that
every store must pass; `InMemoryProcessInstanceStoreTests` and `JdbcProcessInstanceStoreTests` (on H2) extend it, and
it is published in the test jar (classifier `tests`) for testing custom stores. `JpaProcessInstanceStoreTests` tests
the JPA store with Hibernate and H2 in a Spring context, and `ProcessRuntimeAutoConfigurationTests` checks which store
is chosen with and without a `DataSource`, without `spring-jdbc`, with a custom store and with
`pac.process.store.type`. `ProcessJournalRobustnessTests` tests retired versions with stored instances, the startup
check and two concurrent steps without a transaction. `ProcessExecutionStatisticsTests` tests the statistics and
`ProcessWebAutoConfigurationTests` when the web module is enabled and its routes. The test jar also carries
`ProcessAssertions` and the OMG XSDs, for the applications' tests (see [Configuration](configuration.md#processassertions)).
Coverage with JaCoCo and the SonarQube analysis (`SonarQubeAnalysisIT`, `sonar` profile, outside the test jar) are
described in [Code quality](quality.md).
