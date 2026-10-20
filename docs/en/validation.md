# Model validation

> 🇪🇸 [Versión en español](../es/validacion.md)

`bpmncode` does not let an inconsistent model reach execution. It checks it at two moments:

| Moment | Who | What it looks at |
|---|---|---|
| Compilation | `ProcessAnnotationProcessor` (in `javac`) | only the annotated element and the types it references |
| Startup | `AnnotationProcessModelReader` (when creating `ProcessDefinitionRegistry`) | the application's full model |

The compile-time checks are a subset of the startup ones, so nothing slips through even without the processor; it is
just discovered later.

## At compile time

The processor makes `javac` fail and the IDE underlines the error in place. It is enabled as explained in
[Getting started](getting-started.md#2-annotation-processor).

| Element | What it requires |
|---|---|
| `@TimerStartEvent` | exactly one `@Scheduled` (direct, in `@Schedules` or inside a custom annotation), not disabled (`cron = "-"`) and with a single trigger |
| `@MessageStartEvent` | being a Spring listener (`@EventListener` or an annotation carrying it, such as `@ApplicationModuleListener`) that receives a `@Message` or one of its versions |
| `@MessageEndEvent.messageRef` | a specific version (record), not the family (`an end event publishes one concrete version: use one of the @MessageVersion records of … as messageRef`) |
| `@SendTask.messageRef` | declared (`a send task sends a message: declare its messageRef`) and a specific version (record), not the family (`a send task sends one concrete version: use one of the @MessageVersion records of … as messageRef`) |
| Node annotations | a class with `@Process` or `@ProcessPart` (not both) and a single node per method |
| Start events and tasks | a single outgoing flow, with `targetRef` and without `when` or `conditionExpression` |
| `@StandardLoop` | at most one, with `loopCondition` and `loopMaximum` ≥ 0 |
| `@ExclusiveGateway` | returning an `enum` with at least two branches, which are exactly its constants |
| `@BusinessRuleTask` | at least one rule |
| `@Message` / `@MessageVersion` | a sealed family of `ProcessMessage` records, versions ≥ 1 and not repeated |
| `@ProcessError` | being on an exception (`@ProcessError goes on an exception (a subclass of Throwable)`) with an `errorCode` (`@ProcessError does not declare its errorCode`) |
| `@ErrorEndEvent.errorRef` | an exception with `@ProcessError` (`errorRef … is not an exception annotated with @ProcessError`) |
| `@PotentialOwner` / `@HumanPerformer` | exactly one of `resourceRef` or `expression` (`a potential owner declares exactly one of resourceRef or expression`, `a human performer declares exactly one of resourceRef or expression`); `resourceRef`, a type with `@ProcessResource` (`resourceRef … is not a type annotated with @ProcessResource`) |
| Identity | NCName ids, non-empty names (also on `@ProcessResource`), `@Process` and `@BusinessRule` versions from 1 |

The processor is designed to work without loading anything inside `javac`:

- it recognises the annotations by name, without loading Spring or the rest of the library;
- it does not depend on which jar the referenced types are in.

Whatever depends on the whole application (targets in another class, rules, who publishes and who consumes each
message, reachability, having an end event, unique ids, contract file, `${…}`) is checked at startup.

## At startup

The reader walks the application's packages, builds the model and collects **all** violations before failing. If
there is any, it throws `ProcessModelException` and the application does not start:

```
io.github.eldiegos.bpmncode.model.ProcessModelException: Invalid process model (2 problem(s)):
  - UNKNOWN_TARGET_REF …
  - GATEWAY_OUTGOING_MISMATCH …
```

Each violation is a `ModelViolation` with its `ViolationKind`, the location (class, method or file) and a message in
English, like the logs. `ProcessModelException.violations()` returns them for whoever wants to handle them.

### Error kinds

| `ViolationKind` | Meaning |
|---|---|
| `DUPLICATE_ID` | a node id, flow id, process and version, rule and version, message, error or resource is repeated (versions of the same process may share node ids) |
| `INVALID_ID` | an id is not an NCName |
| `UNIDENTIFIED_ELEMENT` | an element without an id or a name |
| `AMBIGUOUS_REFERENCE` | a reference allows several interpretations, e.g. `R-PER-001` when the rule has several versions, `@ProcessPart` without a version for a process with several, a process with several start events, or a method with several node annotations |
| `MISSING_PROCESS_ANNOTATION` | a method with a node annotation in a class without `@Process` or `@ProcessPart` |
| `UNKNOWN_PROCESS_REF` | `@ProcessPart` points to a process or version that does not exist |
| `UNKNOWN_TARGET_REF` | a `@SequenceFlow` points to a node that does not exist in the process |
| `UNKNOWN_BUSINESS_RULE_REF` | `businessRuleRefs` names a rule or version that does not exist |
| `UNKNOWN_MESSAGE_REF` | `messageRef` points to a type that is neither a `@Message` nor one of its versions |
| `UNKNOWN_ERROR_REF` | `errorRef` points to a class that is not an exception annotated with `@ProcessError` (`errorRef … is not an exception annotated with @ProcessError`) |
| `UNKNOWN_RESOURCE_REF` | the `resourceRef` of a `@PotentialOwner` or `@HumanPerformer` points to a type without `@ProcessResource` (`resourceRef … is not a type annotated with @ProcessResource`) |
| `INVALID_ERROR` | `@ProcessError` on a class that is not an exception, or without an `errorCode` |
| `MISSING_START_EVENT` | a process without a start event |
| `MISSING_END_EVENT` | a process without an end event of any type (`declares no end event (@EndEvent, @MessageEndEvent or @ErrorEndEvent)`) |
| `UNREACHABLE_FLOW_NODE` | a node that cannot be reached from any start event |
| `NO_PATH_TO_END_EVENT` | a node from which no end event can be reached (a cycle with no way out) |
| `INCOMPATIBLE_METADATA` | metadata that does not fit the element: versions below 1, a `@MessageStartEvent` that is not a listener, a `@TimerStartEvent` without `@Scheduled`, badly declared loops, a `@PotentialOwner` or `@HumanPerformer` that does not declare exactly one of `resourceRef` or `expression`… |
| `GATEWAY_OUTGOING_MISMATCH` | the `when` values of a gateway are not exactly the constants of the enum it returns |
| `INVALID_MESSAGE_VERSION` | badly declared message versions (not sealed, not a record, without `ProcessMessage`, repeated version…) or a `@MessageEndEvent` or `@SendTask` that publishes the family instead of a specific version |
| `MESSAGE_VERSION_NOT_ACCEPTED` | a message version is published that no consumer accepts |
| `MESSAGE_CONTRACT_CHANGED` | the schema of a published version no longer matches its fingerprint in the contract file |
| `MESSAGE_CONTRACT_NOT_LOCKED` | there is a version not yet in the contract file (the message includes the line to add) |
| `MESSAGE_CONTRACT_REMOVED` | the contract file has a version that no longer exists in the code |

The last three are explained in [Messages between processes](messages.md#2-frozen-schema).

## Testing the model

Since the model is validated when the context is created, any `@SpringBootTest` of the application fails if the model
is not valid. To test only the model, without a Spring context, read the packages directly:

```java
@Test
void theProcessModelIsValid() {
	var definitions = AnnotationProcessModelReader.scan("com.example.orders");   // throws ProcessModelException if invalid
	assertThat(definitions.process("ORDER")).isPresent();
}
```

`AnnotationProcessModelReader` lives in the `model.reader` package, which is internal: its signature may change
between `0.x` versions.

To also check that the exported diagrams are valid BPMN 2.0 according to the OMG XSDs and that every drawn element
has its model element, use `ProcessAssertions`, from the library's test jar (see
[Configuration](configuration.md#processassertions)).
