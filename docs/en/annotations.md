# Annotations

> 🇪🇸 [Versión en español](../es/anotaciones.md)

Package `io.github.eldiegos.bpmncode.annotation`. Each annotation is named after the BPMN 2.0.2 element it produces,
and its attributes after the corresponding BPMN attributes (`id`, `name`, `documentation`, `outgoing`, `targetRef`,
`messageRef`, `errorRef`, `resourceRef`, `conditionExpression`). Whatever BPMN does not define is exported as a `pac:` extension (see
[BPMN](bpmn-traceability-i18n.md#pac-extensions)).

## Summary

| Annotation | Where | BPMN element |
|---|---|---|
| `@Process` | class | `bpmn:process` |
| `@ProcessPart` | class | — (splits the nodes of a process across several classes) |
| `@StartEvent` | method | `bpmn:startEvent` without definition: started by an application call |
| `@MessageStartEvent` | Spring listener method | `bpmn:startEvent` + `bpmn:messageEventDefinition` |
| `@TimerStartEvent` | method with `@Scheduled` | `bpmn:startEvent` + `bpmn:timerEventDefinition` |
| `@ServiceTask` | method | `bpmn:serviceTask` |
| `@UserTask` | method | `bpmn:userTask` |
| `@BusinessRuleTask` | method | `bpmn:businessRuleTask` |
| `@SendTask` | method | `bpmn:sendTask`: publishes a message and the process carries on |
| `@StandardLoop` | `loop` attribute of a task | `bpmn:standardLoopCharacteristics` |
| `@PotentialOwner` | `potentialOwners` attribute of a `@UserTask` | `bpmn:potentialOwner` |
| `@HumanPerformer` | `humanPerformers` attribute of a `@UserTask` | `bpmn:humanPerformer` |
| `@ExclusiveGateway` | method returning an `enum` | `bpmn:exclusiveGateway` |
| `@SequenceFlow` | `outgoing` attribute of a node | `bpmn:sequenceFlow` |
| `@EndEvent` | method | `bpmn:endEvent` without definition |
| `@MessageEndEvent` | method | `bpmn:endEvent` + `bpmn:messageEventDefinition`: publishes a message when it ends |
| `@ErrorEndEvent` | method | `bpmn:endEvent` + `bpmn:errorEventDefinition`: ends with a business error |
| `@Message` | `sealed interface` or record | `bpmn:message` + `bpmn:itemDefinition` |
| `@MessageVersion` | record | `bpmn:itemDefinition` of one version of the message |
| `@ProcessError` | exception (subclass of `Throwable`) | `bpmn:error` + `bpmn:itemDefinition` |
| `@ProcessResource` | Java type (usually a record) | `bpmn:resource` (a business role) + `bpmn:resourceParameter` |
| `@BusinessRule` | class or method | — (identifies the code of a business rule) |

## Identifiers and names

- Every `id` must be an **NCName** (starts with a letter or `_`, no spaces or `:`), because they are XML ids in the
  exported BPMN. Valid examples: `ORDER`, `REG-CHECK-CREDIT`, `Task_1`.
- Node and flow ids are unique across the whole application, not just within their process: the collaboration
  diagram puts several processes in the same document. A per-process prefix (`REG-`, `VER-`) avoids clashes. The
  exception is the versions of the same process, which may (and usually do) repeat their node ids.
- `name` is mandatory and cannot be empty. It is the canonical text (English is recommended); the user interface
  translates it with keys derived from the id (see [i18n](bpmn-traceability-i18n.md#internationalization)).
- `documentation` is optional and only carries business text; it ends up in `bpmn:documentation`.
- It is a good idea to declare ids as constants (`static final String`) so you can also use them in journal calls.

## `@Process`

```java
@Process(id = "ORDER", name = "Order handling", version = 1, documentation = "…")
public class OrderHandling { … }
```

| Attribute | Mandatory | Meaning |
|---|---|---|
| `id` | yes | stable id of the process, shared by all its versions |
| `name` | yes | canonical name |
| `version` | yes | version, from 1; each version is a different class (see [Versions](versions.md)) |
| `documentation` | no | business description |

There is exactly one class per id and version pair.

## `@ProcessPart`

To split the nodes of a large process across several classes:

```java
@ProcessPart(processRef = "ORDER", version = 1)
class OrderShipping {

	@ServiceTask(id = "SHIP", name = "Ship order", outgoing = @SequenceFlow(targetRef = "SHIPPED"))
	void ship(…) { … }
}
```

`version` may be omitted (value 0) only if the process has a single version. A class cannot carry both `@Process` and
`@ProcessPart`.

## Start events

Each version of a process has exactly one start event, of one of these three types, with exactly one unconditional
outgoing flow.

### `@StartEvent`: started by a call

```java
@StartEvent(id = "ORDER-RECEIVED", name = "Order received", outgoing = @SequenceFlow(targetRef = "CHECK-CREDIT"))
public void receive(Order order) {
	var instance = journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("RECEIVED"));
	…
}
```

### `@MessageStartEvent`: started by a message

The method must be a Spring listener (`@EventListener`, `@TransactionalEventListener`, or an annotation carrying
them, such as Spring Modulith's `@ApplicationModuleListener`) whose parameter is the message.

```java
@ApplicationModuleListener
@MessageStartEvent(id = "ORDER-PAID", name = "Payment received", messageRef = PaymentReceived.class,
		outgoing = @SequenceFlow(targetRef = "PREPARE"))
public void on(PaymentReceived message) {
	var instance = journal.startProcessInstance(KEY, message);
	…
}
```

`messageRef` is the `@Message` family (accepts all its versions) or a specific version (`@MessageVersion`). The
instance inherits the correlation from the message header. See [Messages](messages.md).

### `@TimerStartEvent`: started by a timer

The timer is Spring's `@Scheduled` on the same method; `bpmncode` has no scheduler of its own.

```java
@Scheduled(cron = "${shop.reminders.cron:0 0 8 * * *}", zone = "Europe/Madrid")
@TimerStartEvent(id = "EVERY-MORNING", name = "Every morning", outgoing = @SequenceFlow(targetRef = "REMIND"))
void everyMorning() {
	var instance = journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("TIMER_FIRED"));
	…
}
```

- It requires exactly one active `@Scheduled` (direct, inside `@Schedules` or in a custom annotation carrying it)
  with a single trigger: `cron`, `fixedRate` or `fixedDelay`. `cron = "-"` (disabled) is not allowed.
- `${…}` placeholders are resolved with the application's configuration, so the BPMN shows the real schedule.
- It is exported as `timeCycle` (the cron, or `R/PT5M` for an interval) plus `pac:schedule`, `pac:zone` and
  `pac:initialDelay`.
- Remember to enable scheduling in the application (`@EnableScheduling`).

## Tasks: `@ServiceTask`, `@UserTask`, `@BusinessRuleTask`

```java
@BusinessRuleTask(id = "EVALUATE", name = "Evaluate rules", businessRuleRefs = { "R-PER-002", "R-PER-001@2" },
		outgoing = @SequenceFlow(targetRef = "ELIGIBILITY"))
Assessment evaluate(…) { … }
```

| Attribute | Mandatory | Meaning |
|---|---|---|
| `id`, `name`, `documentation` | `id` and `name` | as for the other nodes |
| `outgoing` | yes | exactly one `@SequenceFlow`, without `when` or `conditionExpression` |
| `businessRuleRefs` | yes on `@BusinessRuleTask` (at least one); optional on the others | rules it applies: `ID` or `ID@version` |
| `loop` | no | at most one `@StandardLoop` |
| `potentialOwners`, `humanPerformers` | no (`@UserTask` only) | who can or must perform it (see [User task roles](#user-task-roles)) |

All three behave the same at runtime; the difference is semantic and shows in the diagram. A `@UserTask` is usually a
method the user interface calls when the person finishes the task (see
[Waiting tasks](execution.md#waiting-tasks)).

### User task roles

A `@UserTask` may declare who can claim and complete it (`potentialOwners`, `bpmn:potentialOwner` elements) and who
performs it (`humanPerformers`, `bpmn:humanPerformer` elements). By default, none.

> **The library describes; the application assigns.** Roles document the model: they appear in the BPMN and in the web
> viewer, but `bpmncode` does not assign the task to anyone, does not manage task inboxes and does not check who calls
> `completeActivity`. Deciding who the task is assigned to and who may complete it is your application's code
> (security, queries to your user directory…).

```java
@ProcessResource(id = "RES-REVIEWER", name = "Reviewer", documentation = "Senior member of the department")
public record Reviewer(String department, Optional<String> language) { }

@UserTask(id = "REV-REVIEW", name = "Review document",
		potentialOwners = { @PotentialOwner(resourceRef = Reviewer.class),
				@PotentialOwner(expression = "team lead of the author") },
		humanPerformers = @HumanPerformer(expression = "the reviewer who claimed the task"),
		outgoing = @SequenceFlow(targetRef = "REV-DONE"))
void review(…) { … }
```

Each `@PotentialOwner` and each `@HumanPerformer` declares **exactly one** of these attributes:

| Attribute | Meaning | Exported as |
|---|---|---|
| `resourceRef` | a type annotated with [`@ProcessResource`](#processresource) | `bpmn:resourceRef` with `Resource_<id>` |
| `expression` | readable text identifying who it falls to (e.g. `manager of the employee`) | `bpmn:resourceAssignmentExpression` with `bpmn:formalExpression language="text/plain"` |

Roles are exported inside the `bpmn:userTask`, after `bpmn:outgoing` and before `bpmn:standardLoopCharacteristics`,
with ids `<taskId>_PotentialOwner_<n>` and `<taskId>_HumanPerformer_<n>` (`n` starts at 1, in declaration order). The
expression is not evaluated: it is text for whoever reads the model.

### `@ProcessResource`

Declares a `bpmn:resource`, a business role (e.g. "Expense approver"), on a Java type. It is not called `@Resource` so
as not to clash with `jakarta.annotation.Resource`.

| Attribute | Mandatory | Meaning |
|---|---|---|
| `id` | yes | stable id of the resource (NCName, unique in the application) |
| `name` | yes | canonical name |
| `documentation` | no | business description |

- If the type is a record, each component is a `bpmn:resourceParameter` (id `Resource_<id>_<component>`) whose `type`
  is a `bpmn:itemDefinition` `ItemDefinition_<id>_<component>` with the Java type of the component (the inner one if it
  is `Optional<T>`). An `Optional` component has `isRequired="false"`; the rest, `isRequired="true"`. If the type is
  not a record, the resource has no parameters.
- It is exported as a `bpmn:resource` (id `Resource_<id>`, `name`, `bpmn:documentation`) with the
  `pac:definedBy class="…"` extension (the Java type), only if some user task in the diagram references it.
- `bpmn:resourceParameterBinding` is not supported yet: a role cannot give values to the resource parameters (e.g. the
  specific department). The parameters describe what the role depends on; resolving them is up to the application.

### `@SendTask`

A send task publishes a message in the middle of the process and the instance carries on without waiting for a reply.
It has the same attributes as the other tasks (`id`, `name`, `documentation`, `outgoing` with exactly one flow,
optional `businessRuleRefs`, `loop`) plus `messageRef`, which is mandatory:

```java
@SendTask(id = "REQUEST-INVOICE", name = "Request invoice", messageRef = InvoiceRequested.class,
		outgoing = @SequenceFlow(targetRef = "ACTIVATE"))
void requestInvoice(ProcessInstanceRef instance, Subscription subscription) {
	var message = new InvoiceRequested(journal.messageHeader(instance), subscription.id());
	journal.completeActivity(instance, "REQUEST-INVOICE", message);   // records MESSAGE_PUBLISHED
	events.publishEvent(message);                                      // after recording it
}
```

- `messageRef` is a specific version, as in `@MessageEndEvent`: a `@MessageVersion` record, or the record of a single-version
  message; never the family.
- It is exported as a `bpmn:sendTask` with the `messageRef` attribute and `pac:version`, and it is the source of a
  `bpmn:messageFlow` to every `@MessageStartEvent` that consumes the message.
- It is completed with `completeActivity(instance, sendTaskId, message)`, not with an outcome (see
  [Messages](messages.md#3-message-header)). With `loop`, each iteration sends a message.

### `@StandardLoop`

Repeats the task in a row while a condition holds (`testBefore="false"`: it runs first, then checks). The diagram
shows the ↻ marker.

```java
@UserTask(id = "SUBMIT", name = "Submit new address", outgoing = @SequenceFlow(targetRef = "VALIDITY"),
		loop = @StandardLoop(loopCondition = "The address does not meet R-ADR-001", loopMaximum = 3))
public Result submit(…) {
	var attempt = journal.completeActivity(instance, "SUBMIT", outcome);   // 1, 2, 3…
	…
}
```

- `loopCondition` is readable text and mandatory. `loopMaximum` is the maximum number of iterations; 0 means no
  limit.
- `completeActivity` returns the iteration number (`loopCounter`) and rejects the one that exceeds `loopMaximum`.
- To check the condition before running, or to repeat several nodes, model a cycle with an `@ExclusiveGateway` whose
  flow goes back.

## `@ExclusiveGateway`

The method returns an `enum`, and each constant is an outgoing flow (`when`):

```java
enum Credit { ENOUGH, NOT_ENOUGH }

@ExclusiveGateway(id = "CHECK-CREDIT", name = "Enough credit?", businessRuleRefs = "R-CREDIT-001",
		outgoing = {
				@SequenceFlow(when = "ENOUGH", targetRef = "SHIP", name = "Yes", conditionExpression = "Credit ≥ total"),
				@SequenceFlow(when = "NOT_ENOUGH", targetRef = "REJECTED", name = "No") })
Credit checkCredit(…) {
	var credit = …;
	journal.selectSequenceFlow(instance, "CHECK-CREDIT", credit);
	return credit;
}
```

- At least two flows, and the `when` constants are **exactly** those of the enum: none missing and none extra.
- `conditionExpression` is readable text (by default, the value of `when`) and is exported as
  `bpmn:conditionExpression`.
- A flow going back to an earlier node forms a cycle; it is valid if an end event can be reached from every node.

## `@SequenceFlow`

It only exists inside `outgoing`.

| Attribute | Meaning |
|---|---|
| `targetRef` | id of the target node (mandatory); it may be in another class of the same process |
| `id` | flow id; by default `Flow_<source>_<target>` |
| `name` | flow label in the diagram |
| `when` | gateways only: the enum constant that selects this branch (`pac:when`) |
| `conditionExpression` | gateways only: readable condition |

## End events

Each process has at least one end event, of any of these three types, and every node must be able to reach one. An
end event has no outgoing flows. All three have `id`, `name` and `documentation`; the message one adds `messageRef`
and the error one, `errorRef`, both mandatory.

### `@EndEvent`: plain end

```java
@EndEvent(id = "SHIPPED", name = "Order shipped")
void shipped() { }
```

It is reached with `reachEndEvent(instance, endEventId, outcome)`.

### `@MessageEndEvent`: end publishing a message

```java
@MessageEndEvent(id = "COMPLETED", name = "Registration completed", messageRef = PersonRegisteredV2.class)
Result complete(…) { … }
```

It publishes that specific version (a `@MessageVersion` record, or the record of a single-version message; never the
family) and the instance ends; to publish without ending, use a [`@SendTask`](#sendtask). It is the source of a
`bpmn:messageFlow` to every `@MessageStartEvent` that consumes the message. It is reached with
`reachEndEvent(instance, endEventId, message)` (see [Messages](messages.md#3-message-header)).

### `@ErrorEndEvent`: end with an error

```java
@ProcessError(id = "ERR-CREDIT-REJECTED", name = "Credit rejected", errorCode = "CREDIT_REJECTED")
public class CreditRejected extends RuntimeException { … }

@ErrorEndEvent(id = "LOAN-REJECTED", name = "Credit rejected", errorRef = CreditRejected.class)
void rejected(ProcessInstanceRef instance, CreditRejected error) {
	journal.reachEndEvent(instance, "LOAN-REJECTED", error);   // records ERROR_THROWN with the errorCode
}
```

- `errorRef` is an exception annotated with [`@ProcessError`](#processerror).
- The instance ends at this event (state `COMPLETED`): the error is its business outcome, not a technical failure.
- It is reached with `reachEndEvent(instance, endEventId, error)`, which accepts the exception of the declared error or
  a subclass of it (see [Execution](execution.md#error-end-events)).
- It is exported as a `bpmn:endEvent` with `bpmn:errorEventDefinition errorRef="Error_<id>"`.

### `@ProcessError`

Declares a `bpmn:error` on a class that extends `Throwable`:

| Attribute | Mandatory | Meaning |
|---|---|---|
| `id` | yes | stable id of the error (NCName, unique in the application) |
| `name` | yes | canonical name |
| `errorCode` | yes | stable code other systems identify it by; it cannot be blank |
| `documentation` | no | business description |

It is exported as a `bpmn:error` (id `Error_<id>`, `name`, `errorCode` and `structureRef`) plus a `bpmn:itemDefinition`
(`ItemDefinition_<id>`) whose `structureRef` is the Java class. A subclass of the exception that does not carry its own
`@ProcessError` counts as the same error.

## `@Message` and `@MessageVersion`

They declare the messages one process publishes (when it ends or with a send task) and another receives. They are explained in
[Messages between processes](messages.md).

```java
@Message(id = "EV-PERSON-REGISTERED", name = "Person registration completed")
public sealed interface PersonRegistered extends ProcessMessage permits PersonRegisteredV1, PersonRegisteredV2 { … }

@MessageVersion(1)
public record PersonRegisteredV1(MessageHeader header, PersonId personId, Instant registeredAt)
		implements PersonRegistered { }
```

## `@BusinessRule`

Identifies the code of a business rule, on a class or a method:

```java
@BusinessRule(id = "R-PER-001", version = 2, name = "Minimum age of 18",
		documentation = "A person younger than 18 on the reference date cannot be registered.")
public static Optional<Violation> checkAdult(BirthDate birthDate, LocalDate referenceDate) { … }
```

- `version` starts at 1 (the default). Several versions of the same rule coexist, each in its own type or method.
- Nodes reference it with `businessRuleRefs`: `"R-PER-001"` if it only has one version, or `"R-PER-001@2"`
  (`BusinessRuleRef.SEPARATOR` is `@`). See [Versions](versions.md#business-rule-versions).
- BPMN does not model rules: the reference is exported as `pac:businessRuleRef`.

## Visibility and calls

- Annotated methods may be private. `bpmncode` reads them through reflection, without instantiating the classes or
  intercepting the calls.
- Annotations execute nothing: the actual order is set by your code, and the journal checks that it follows the
  declared flows.
