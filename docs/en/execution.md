# Execution and conformance

> 🇪🇸 [Versión en español](../es/ejecucion.md)

`bpmncode` is not a process engine: it does not decide what runs or when. Your code is still plain Java, and
`ProcessJournal` records what happens in each instance and **rejects whatever does not fit the declared model**. That
check is *runtime conformance*.

## Concepts

| Concept | Type | Meaning |
|---|---|---|
| Process instance | `ProcessInstanceRef` | one execution of a specific version of a process |
| Correlation | `CorrelationKey` | business identifier that groups related instances; there is at most one instance per process and correlation |
| Business key | `String` | pseudonymous reference to the business subject, e.g. `person:<uuid>`; never personal data |
| Token | — | the flow node the instance is at; there is only one (no parallelism) |
| Evidence | `HistoricFlowNodeInstance` | one record for each node the instance goes through, with its outcome |
| Outcome | `FlowNodeOutcome` | code (`[A-Z][A-Z0-9_]*`) and, optionally, a non-translatable detail: `RULES_VIOLATED:R-PER-001@2` |

## `ProcessJournal` API

| Method | Node | What it does |
|---|---|---|
| `startProcessInstance(ref, correlationKey, outcome)` | `@StartEvent`, `@TimerStartEvent` | creates the instance of version `ref` at its start event |
| `startProcessInstance(ref, message, outcome)` | `@MessageStartEvent` | the same, with the correlation carried by the message header |
| `startProcessInstance(ref, message)` | `@MessageStartEvent` | the same, with the outcome `MESSAGE_RECEIVED` and the received version as detail |
| `startProcessInstanceIfAbsent(ref, message, outcome)`, `startProcessInstanceIfAbsent(ref, correlationKey, outcome)` | start events | starts the instance unless one of the same process with that correlation already exists; returns `ProcessStart` (see [Transactions and idempotency](#transactions-and-idempotency)) |
| `completeActivity(instance, activityId, outcome)` | tasks, except `@SendTask` | advances to the task and returns its iteration (`loopCounter`) |
| `completeActivity(instance, sendTaskId, message)` | `@SendTask` | the same, recording the message it sends; the instance follows its flow |
| `selectSequenceFlow(instance, gatewayId, enumValue)` | `@ExclusiveGateway` | advances to the gateway, sets the outgoing flow that will be followed and returns the same constant |
| `reachEndEvent(instance, endEventId, outcome)` | `@EndEvent` | advances to the end event and completes the instance |
| `reachEndEvent(instance, endEventId, message)` | `@MessageEndEvent` | the same, recording the message that is published |
| `reachEndEvent(instance, endEventId, error)` | `@ErrorEndEvent` | the same, with the exception of the declared error as business outcome (see [Error end events](#error-end-events)) |
| `messageHeader(instance)` | — | prepares the header of the message the instance will publish |
| `resume(processId, correlationKey)` | — | retrieves an instance to continue it, if it exists |
| `require(processId, correlationKey)` | — | the same, but the instance must exist: otherwise `ProcessConformanceException` |
| `setBusinessKey(instance, businessKey)` | — | associates the instance with its business subject |

They are annotated with `@Transactional`: if the application uses Spring Transaction, they join the caller's
transaction; otherwise the annotation is ignored.

## What is checked at each step

Each call moves the token from the current node to the given node, and first checks that:

- the node exists in **the instance's version** (not the current one) and is of the type matching the method
  (`completeActivity` only accepts tasks, `reachEndEvent` only end events…), and that the overload with a message is
  used exactly when the node publishes one (message end event, send task), and the overload with an exception exactly
  when it ends with an error;
- there is a declared sequence flow from the current node to it;
- if the current node is a gateway, the target is the one of the selected flow (`when`);
- the instance is not completed;
- for a message start event, the process accepts that message and that version; for a message end event or a
  send task, the message is the version declared by the node and its header belongs to this instance.

If something does not fit, it throws `ProcessConformanceException` (an `IllegalStateException`) and records nothing.
Normally you let it roll back the transaction: it is a programming error, not a business one.

```java
journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("RECEIVED"));
journal.reachEndEvent(instance, "SHIPPED", …);
// ProcessConformanceException: No sequence flow ORDER-RECEIVED → SHIPPED is declared in ORDER
```

Steps are nodes, not flows: you do not record passing through a sequence flow.

## Outcomes

`FlowNodeOutcome` describes what happened at the node, with a stable code the user interface can translate:

```java
FlowNodeOutcome.of("PERSON_CREATED");
FlowNodeOutcome.of("RULES_VIOLATED", "R-PER-002@1, R-PER-001@2");
FlowNodeOutcome.of("RULES_VIOLATED", BusinessRuleRef.join(violatedRules));   // the same detail, without repeats
```

Some are set by the journal itself:

| Code | When |
|---|---|
| `SEQUENCE_FLOW_SELECTED` | `selectSequenceFlow`, with the constant and the node it leads to as detail, e.g. `ACCEPT → ST-WORK` |
| `MESSAGE_PUBLISHED` | `reachEndEvent` with a message, with `ID@version` as detail; `completeActivity` of a send task, with `ID@version <messageId>` |
| `MESSAGE_RECEIVED` | `startProcessInstance(ref, message)`, with `ID@version` as detail |
| `ERROR_THROWN` | `reachEndEvent` with an exception, with the `errorCode` of the error as detail |

The four codes are constants of `FlowNodeOutcome` (`FlowNodeOutcome.SEQUENCE_FLOW_SELECTED`…), and
`FlowNodeOutcome.messageReceived(messageVersionRef)` builds `MESSAGE_RECEIVED` if you want to record it yourself.

Since `selectSequenceFlow` returns the constant, the gateway method can end with it:

```java
@ExclusiveGateway(id = "CHECK-CREDIT", name = "Enough credit?", outgoing = { … })
Credit checkCredit(ProcessInstanceRef instance, Order order) {
	return journal.selectSequenceFlow(instance, "CHECK-CREDIT", credits.check(order));
}
```

Outcomes are stored in the evidence, which must not carry personal data: use codes and references, and keep the data
in your own tables.

## Error end events

An `@ErrorEndEvent` is an outcome of the process, not a technical failure: the instance ends at that event (state
`COMPLETED`) and the error is its business outcome. It is recorded with the exception of the error:

```java
@ErrorEndEvent(id = "LOAN-REJECTED", name = "Credit rejected", errorRef = CreditRejected.class)
void rejected(ProcessInstanceRef instance, CreditRejected error) {
	journal.reachEndEvent(instance, "LOAN-REJECTED", error);
}
```

- The exception must be the one of the error declared in `errorRef` or a subclass of it; it is looked up with
  `Definitions#errorOf(Class)`, which walks the class and its superclasses.
- It records `ERROR_THROWN` with the `errorCode` as detail.
- If the caller rethrows the exception within the same transaction, the transaction rolls back and the evidence with
  it: the journal does not throw the exception, it only records it.

Conformance errors specific to these events (`ProcessConformanceException`):

| Call | Message |
|---|---|
| `reachEndEvent(instance, id, outcome)` on an `@ErrorEndEvent` | `<id> ends with the error <errorId>: reach it with reachEndEvent(instance, endEventId, error)` |
| `reachEndEvent(instance, id, error)` on an event without an error | `<id> does not end with an error` |
| `reachEndEvent(instance, id, error)` with an exception without `@ProcessError` | `<class> is not a @ProcessError` |
| `reachEndEvent(instance, id, error)` with the exception of another error | `<id> ends with the error <errorId>, not <other errorId>` |

## Waiting tasks

A `@UserTask` may finish days after the instance started, in another request. The instance is retrieved with its
process and its correlation, which the application stores next to its data:

```java
@Transactional
@UserTask(id = "CONFIRM", name = "Confirm new address", outgoing = @SequenceFlow(targetRef = "CONFIRMATION"))
public Result confirm(AddressChangeId id, Verdict verdict) {
	var change = changes.findById(id).orElseThrow();
	var instance = journal.require(PROCESS_ID, change.correlationKey());
	journal.completeActivity(instance, "CONFIRM", FlowNodeOutcome.of("OPERATOR_VERDICT", verdict.name()));
	…
}
```

`require` throws `ProcessConformanceException` if there is no instance; `resume` returns an `Optional` so you decide.

To know which tasks are waiting, query the active instances: `ProcessInstanceSummary.Active.waitingFlowNodeId()` is
the node where the token waits and `nextFlowNodeIds()` the nodes conformance allows advancing to now.
`ProcessInstanceHistory.isWaitingAt(instanceId, flowNodeId)` sums it up in one line: it tells whether the instance is
active and that node is among those it accepts now, e.g. to check that it is a human task's turn without storing a
state of your own.

The journal checks the turn, not the person: it does not look at who calls `completeActivity`. The roles declared with
`potentialOwners` and `humanPerformers` (see [User task roles](annotations.md#user-task-roles)) describe who can or
must perform the task; assigning it and authorising whoever completes it is the application's code.

## Loops and cycles

With a single token there are two ways to repeat:

- **Standard loop** (`@StandardLoop` on a task): `completeActivity` on the task the token is already at records
  another iteration. It returns the `loopCounter` (1 on arrival, 2 on the second round…) and rejects the iteration
  that exceeds `loopMaximum`. A task without `@StandardLoop` cannot be completed twice in a row.
- **Cycle**: a flow from a gateway back to an earlier node. The journal treats it like any other declared flow. When
  returning to a task through a cycle, its loop starts again at 1.

## Versions and running instances

Each instance stays bound to the version it started with and always advances according to that definition, even if
a newer version is deployed. Whoever starts the process chooses the version (`ProcessDefinitionRef.of("ORDER", 2)`).
See [Versions](versions.md).

## Transactions and idempotency

- With a transactional store (JPA or JDBC, see [Storage](storage.md)), call the journal in the same transaction
  as your business changes: the evidence and the data commit or roll back together. The in-memory store is not
  transactional.
- There is at most one instance per process and correlation: a second start with the same correlation throws
  `DuplicateProcessInstanceException`. With *at-least-once* delivered messages (e.g. Spring Modulith's event
  publication registry), start with `startProcessInstanceIfAbsent` (below) or treat that exception as a duplicate.
- Instances use optimistic locking: if two concurrent advances of the same instance start from the same state, the
  second throws `ProcessInstanceConflictException` and saves nothing.

`startProcessInstanceIfAbsent` makes a start idempotent: if there is already an instance of the process with that
correlation it records nothing and returns `ProcessStart.AlreadyStarted` with the existing one (in any state, also
completed); otherwise it starts it and returns `ProcessStart.Started`. If another transaction starts it at the same
time, the store's unique constraint is still the last guarantee (`DuplicateProcessInstanceException`).

```java
switch (journal.startProcessInstanceIfAbsent(KEY, message, FlowNodeOutcome.of("REQUEST_RECEIVED"))) {
	case ProcessStart.Started started -> continueWith(started.instance());
	case ProcessStart.AlreadyStarted repeated -> log.info("Repeated delivery for {}", repeated.instance());
}
```

## Querying: `ProcessInstanceHistory`

| Method | Returns |
|---|---|
| `instances()` | all instances, active and completed |
| `instances(correlationKey)` | those of a correlation (e.g. a registration and the verification it started) |
| `instances(processDefinitionRef)` | those of a version of a process, newest first, with a single store query |
| `instance(instanceId)` | one instance |
| `flowNodeInstances(instanceId)` | its evidence, in order |
| `flowNodeInstances(processDefinitionRef)` | the evidence of every instance of a version, as a `Map` per instance and in sequence order, with a single query; instances without evidence are absent |
| `isWaitingAt(instanceId, flowNodeId)` | whether the instance is active and conformance would allow recording that node now |

For ready-made aggregates (heatmap, path of an instance, counts per version) use `ProcessExecutionStatistics`; see
[Statistics and web module](statistics-web.md).

`ProcessInstanceSummary` is a sealed interface with two cases:

- `Active`: has not reached an end event yet. It offers `waitingFlowNodeId()` and `nextFlowNodeIds()`.
- `Completed`: with an end date and, if it ended in a message end event, `publishedMessage()` (the messages of its send
  tasks do not count: they are in its evidence).

Both offer `triggeringMessage()`, the message that started the instance if it started with one.

`HistoricFlowNodeInstance` carries, for each step, the process and its version, the node and its BPMN type, the
`loopCounter`, the timestamp, the outcome, the correlation and the business key.

## Where it is stored

In memory if the application has no database, or in the tables `pac_process_instance` and `pac_flow_node_instance`
if it has one, with JPA or with JDBC. It is chosen automatically and can be changed or replaced by a custom store;
see [Storage](storage.md).
