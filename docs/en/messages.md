# Messages between processes

> 🇪🇸 [Versión en español](../es/mensajes.md)

A process can start another by publishing a message: when it ends, in a `@MessageEndEvent(messageRef = …)`, or in
the middle of the process, in a `@SendTask(messageRef = …)` after which it carries on without waiting for a reply; the
other one begins in a `@MessageStartEvent` that receives it. The message is a **contract** between two processes that may evolve
separately, so `bpmncode` protects it in three layers.

`bpmncode` does not transport the messages: they are published with Spring's `ApplicationEventPublisher` and received
with a listener. For reliable, asynchronous delivery, Spring Modulith (`@ApplicationModuleListener` and its event
publication registry) fits naturally.

## 1. Versions as sealed types

`@Message` goes on a `sealed interface` (the family) and each version is a record with `@MessageVersion` that
implements `ProcessMessage`, that is, it carries a `MessageHeader header` component:

```java
@Message(id = "EV-PERSON-REGISTERED", name = "Person registration completed")
public sealed interface PersonRegistered extends ProcessMessage permits PersonRegisteredV1, PersonRegisteredV2 {
	PersonId personId();
	Instant registeredAt();
}

@MessageVersion(1)
public record PersonRegisteredV1(MessageHeader header, PersonId personId, Instant registeredAt)
		implements PersonRegistered { }

@MessageVersion(2)
public record PersonRegisteredV2(MessageHeader header, PersonId personId, Instant registeredAt,
		IdentityDocumentType identityDocumentType) implements PersonRegistered { }
```

If a message has a single version, `@Message` can go directly on the record.

- **The publisher chooses a specific version**: `@MessageEndEvent(messageRef = PersonRegisteredV2.class)` or
  `@SendTask(messageRef = PersonRegisteredV2.class)`.
- **The consumer declares what it accepts**: the family (`messageRef = PersonRegistered.class`, all versions) or one
  version. If a version is published that no consumer accepts, the application does not start
  (`MESSAGE_VERSION_NOT_ACCEPTED`). Accepting versions that nobody publishes any more is allowed: there may be
  messages in flight.
- **The consumer handles the versions with a `switch` without `default`**. When a version is added to the sealed
  family, the consumer stops compiling until it decides what to do with it.

```java
@ApplicationModuleListener
@MessageStartEvent(id = "REGISTRATION-RECEIVED", name = "Completed registration received",
		messageRef = PersonRegistered.class, outgoing = @SequenceFlow(targetRef = "OPEN"))
public void on(PersonRegistered message) {
	var instance = journal.startProcessInstance(KEY, message);
	var document = switch (message) {
		case PersonRegisteredV1 v1 -> Optional.<IdentityDocumentType>empty();
		case PersonRegisteredV2 v2 -> Optional.of(v2.identityDocumentType());
	};
	…
}
```

## 2. Frozen schema

At startup, `bpmncode` generates a deterministic **JSON Schema 2020-12** for each version and computes its SHA-256
fingerprint:

- the record components, in declaration order, without the header (which is common to all);
- `Optional` is not required; UUID, `java.time`, enums, nested records, lists and maps, with their format;
- two equal schemas have the same fingerprint, and any change of field, type or requiredness changes it.

The fingerprints are kept in `src/main/resources/pac/message-contracts.lock`, which is reviewed in version control like
any other contract:

```
# Contratos congelados de los mensajes entre procesos.
EV-PERSON-REGISTERED@1 = sha256:458641d6132d26886dace3de7990529bb26052dd37854b76bd5520d78f85cfe6
EV-PERSON-REGISTERED@2 = sha256:1427690b…
```

| Situation | Result at startup |
|---|---|
| A field of an already published version is changed | `MESSAGE_CONTRACT_CHANGED`, with the frozen and the current fingerprint: a new version must be created |
| A version is added | `MESSAGE_CONTRACT_NOT_LOCKED`, with the exact line to add to the file |
| A version still in the file is deleted | `MESSAGE_CONTRACT_REMOVED`: there may be messages in flight |
| There is no file | nothing is checked (useful when starting out) |

All `pac/message-contracts.lock` files on the classpath are read and combined, so each jar can bring those of its own
messages. `Definitions` exposes each schema (`MessageVersionDefinition.schema()`), in case the application wants to
show or publish them.

## 3. Message header

Each version carries a `MessageHeader`:

| Component | Meaning |
|---|---|
| `messageId` | unique identifier of the delivery (`MessageId`) |
| `correlationKey` | correlation of the publishing instance; inherited by the instances it starts |
| `occurredAt` | publication time |
| `source` | process and version that publishes it |
| `sourceInstanceId` | instance that publishes it |

It is not built by hand: the journal prepares it from the publishing instance.

```java
// Publish: message end event
var registered = new PersonRegisteredV1(journal.messageHeader(instance), person.id(), person.createdAt());
journal.reachEndEvent(instance, "REG-COMPLETED", registered);   // evidence MESSAGE_PUBLISHED EV-PERSON-REGISTERED@1
events.publishEvent(registered);                                 // after recording it

// Publish: send task; the instance carries on at the next node
var requested = new InvoiceRequested(journal.messageHeader(instance), subscription.id());
journal.completeActivity(instance, "REQUEST-INVOICE", requested);   // evidence MESSAGE_PUBLISHED EV-INVOICE-REQUESTED@1 <messageId>
events.publishEvent(requested);

// Receive: message start event; the correlation comes from the header
journal.startProcessInstance(KEY, message);   // evidence MESSAGE_RECEIVED EV-PERSON-REGISTERED@1

// Receive idempotently: a repeated delivery returns ProcessStart.AlreadyStarted
journal.startProcessInstanceIfAbsent(KEY, message,
		FlowNodeOutcome.messageReceived(new MessageVersionRef("EV-PERSON-REGISTERED", 1)));
```

Record **before** publishing: if conformance rejects the step (for example, a repeated request on an instance that has
already completed), `reachEndEvent` or `completeActivity` throws and the message never goes out. In the reverse order,
the listeners would already have received it.

The journal checks both ends:

- `reachEndEvent` requires the message to be the version declared by the `@MessageEndEvent` and its header to belong to that
  instance. A message end event cannot be reached without publishing the message.
- `completeActivity(instance, sendTaskId, message)` requires the same for a send task and returns its iteration
  (`loopCounter`). A send task cannot be completed with an outcome
  (`… sends EV-INVOICE-REQUESTED@1: complete it with completeActivity(instance, sendTaskId, message)`), nor another
  task with a message (`… sends no message: complete it with completeActivity(instance, activityId, outcome)`).
- `startProcessInstance` requires the start event to accept that message and that version. A process that starts with
  a message cannot be started without it.
- Each instance stores which delivery started it and which one it published when it ended
  (`ProcessInstanceSummary.triggeringMessage()` and `Completed.publishedMessage()`). What its send tasks send stays in
  the evidence of each task (`MESSAGE_PUBLISHED` with the version and the `messageId`).

## Changing a contract

1. Add a record with `@MessageVersion(n + 1)` to the family.
2. Publish it from the new version of the emitting process (`@MessageEndEvent(messageRef = …V(n+1).class)` or
   `@SendTask(messageRef = …V(n+1).class)`).
3. Handle it in the `switch` of each consumer; the compiler will tell you where.
4. Start the application and add to the contract file the line reported by `MESSAGE_CONTRACT_NOT_LOCKED`.

Existing versions are never touched.

## Contract catalog

`MessageContractCatalog` (package `model`) is a bean that gathers, for a governance screen or a tool, what there is
to know about each contract without walking the model. It is built from the model and from the classpath's
`pac/message-contracts.lock`.

| Method or record | Content |
|---|---|
| `contracts()`, `contract(messageId)` | one `Contract` per message: the `Message`, its versions and its consumers |
| `lockPresent()` | whether the application has a contract file |
| `Version` | `ref` (`ID@version`), `definition` (record and schema), `fields`, `lockStatus`, `lockLine` (the exact line that freezes it) and `publishers` |
| `Field` | a component of the record: `name`, `type` (simple name; the wrapped one if it is `Optional`), `optional`, `header` (the common `MessageHeader`) and `enumConstants` |
| `LockStatus` | `FROZEN` (frozen with its current fingerprint), `NOT_FROZEN` (the file does not freeze it with that fingerprint) or `NO_LOCK_FILE` |
| `Publisher` | process version (`process`) and node that publishes the version (`flowNode`): a message `EndEvent` or a `SendTask` |
| `Consumer` | process version, `StartEvent` that accepts the message and accepted versions |

Everything is in model order and without translated texts. With a contract file the application does not start if a
version is not frozen, so `NOT_FROZEN` only appears if the catalog is built outside startup (e.g. in a test).

## Diagrams

Message flows appear in the BPMN: in each process diagram, the process it communicates with is a collapsed pool, and
the collaboration diagram (`COLLABORATION`) draws all the communicating processes together. The published version is
exported as `pac:version` and the accepted versions as `pac:acceptedVersions`. The source of a message flow is the end
event or send task that publishes; its id is `MessageFlow_<source>_<target>`.

## Message persistence

If you use Spring Modulith's JPA event publication registry, it stores the message serialized as JSON. With header
and payload it rarely fits in the 255 characters Hibernate gives that column by default: widen it (e.g. with a
`META-INF/orm.xml` in the application) or use a long text type in your schema script.
