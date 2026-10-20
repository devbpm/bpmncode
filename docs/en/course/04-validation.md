# Lesson 4. Validation at compile time and at startup

> 🇪🇸 [Versión en español](../../es/curso/04-validacion.md)

**Goal:** trigger the typical errors on purpose, recognize who detects them and read their messages to fix them
quickly.

## What you will learn

- What the annotation processor checks at compile time and why it cannot check everything.
- What startup checks and how to read a `ProcessModelException`.
- Why one error can drag others along and which one to fix first.
- How a test without Spring detects a broken model before you deploy.

## Starting point

The code from [lesson 3](03-decisions-with-gateways.md): `ExpenseClaimProcess` with 8 nodes, the rules
`ExpensePolicyRule` and `ApprovalThresholdRule`, and the `ProcessModelTests` test. In this lesson you **do not add new
code**: you break the existing code, observe and put it back as it was.

## Steps

### 1. Two moments, two scopes

`bpmncode` does not let an inconsistent model get to run. It checks it twice:

| Moment | Who | Sees… | Examples |
|---|---|---|---|
| Compilation | `ProcessAnnotationProcessor`, inside `javac` and the IDE | one annotation and the types it names | invalid id, empty name, gateway without an `enum`, branches that do not match the `enum`, task with two outgoing flows |
| Startup | the model reader, when it creates the `ProcessDefinitionRegistry` bean | the whole application | `targetRef` that does not exist, rule that does not exist, unreachable nodes, cycles without exit, duplicate ids |

The processor only looks at the annotated element, so it does not know whether the node a `targetRef` points to exists
in another class. Those global checks are done at startup. The compile-time checks are a subset of the startup ones:
even if you forget to configure the processor, nothing slips through; you just find out later.

### 2. A compile error

Delete the `AUTO_APPROVED` branch of `EXP-ROUTE` (the last `@SequenceFlow` of its `outgoing`) and compile:

```bash
./mvnw compile
```

```
[ERROR] /…/com/example/expenses/claim/ExpenseClaimProcess.java:[51,9] constants of Route without an outgoing sequence flow: [AUTO_APPROVED]
```

The error points to the gateway's annotation, and your IDE underlines it in red. Put the branch back.

### 3. A startup error

Now get a `targetRef` wrong in a way the processor cannot see. In `EXP-PAY`, write
`targetRef = "EXP-PAYMENT-ORDER"` (the `D` is missing). It compiles without problems. Start it:

```bash
./mvnw spring-boot:run
```

The application does not start. Among the causes of the failure you will see the library's exception:

```
io.github.eldiegos.bpmncode.model.ProcessModelException: Invalid process model (3 problem(s)):
  - [UNKNOWN_TARGET_REF] com.example.expenses.claim.ExpenseClaimProcess#pay(ExpenseClaim): the sequence flow targets the unknown flow node 'EXP-PAYMENT-ORDER'
  - [UNREACHABLE_FLOW_NODE] Process EXP-CLAIM v1: flow node EXP-PAYMENT-ORDERED is not reachable from the start event
  - [NO_PATH_TO_END_EVENT] Process EXP-CLAIM v1: no end event can be reached from flow node EXP-PAY: a cycle without exit
```

Each line is a `ModelViolation`: `[kind] location: message`. The location is the class and the method (or the process
and its version), so you know where to look without debugging.

### 4. Fix the cause, not the consequences

There are three problems, but a single error. The flow of `EXP-PAY` points to a node that does not exist, so the reader
discards it; without that flow, nothing reaches `EXP-PAYMENT-ORDERED` and no end can be reached from `EXP-PAY`. The last
two lines are a consequence of the first.

Rule of thumb: start with the reference errors (`UNKNOWN_…`, `AMBIGUOUS_REFERENCE`, `DUPLICATE_ID`) and start again;
the graph errors (`UNREACHABLE_FLOW_NODE`, `NO_PATH_TO_END_EVENT`) usually disappear on their own.

A more striking case: if you misspell a rule on a node (`businessRuleRefs = "R-EXP-01"` on `EXP-CHECK`), the reader
discards that whole node and many lines come out: `UNKNOWN_BUSINESS_RULE_REF` on `EXP-CHECK`, `UNKNOWN_TARGET_REF` on
`submit` (which pointed to `EXP-CHECK`) and one `UNREACHABLE_FLOW_NODE` for each later node. The cause is the first one:

```
[UNKNOWN_BUSINESS_RULE_REF] com.example.expenses.claim.ExpenseClaimProcess#check(ExpenseClaim): references the unknown rule 'R-EXP-01' (there is no @BusinessRule with that id)
```

Undo the change (`targetRef = PAYMENT_ORDERED`).

### 5. Let a test detect it

The `ProcessModelTests` test from lesson 3 calls `AnnotationProcessModelReader.scan`, which throws the same
`ProcessModelException`. Repeat the error from step 3 and run `./mvnw test`: the test fails with the same message, in
seconds and without starting Spring. In addition, any `@SpringBootTest` in the application fails too, because the model
is validated when the context is created. With that, a broken model does not get past your continuous integration.

## Check that it works

With the code back as in lesson 3:

- `./mvnw compile` finishes without errors.
- `./mvnw test` passes `ProcessModelTests`.
- At startup you see `Process definition EXP-CLAIM v1 'Expense claim': 8 flow nodes, 9 sequence flows`.

## Common mistakes

- **The compile errors do not show up; it only fails at startup.** The processor is not running. Check that it is in
  `annotationProcessors` of the `maven-compiler-plugin` (lesson 1); since JDK 23 it is mandatory. If your IDE does not
  mark them, enable annotation processing in its compiler settings or reimport the Maven project.
- **Adding the processor breaks Lombok or others.** Once you list one in `annotationProcessors`, `javac` only runs the
  listed ones: add the others too.
- **A method with two node annotations** (e.g. `@ServiceTask` and `@UserTask`): it does not compile (`the method declares
  several flow nodes: …`).
- **An annotated method in a class with neither `@Process` nor `@ProcessPart`**: it does not compile (`class … declares
  neither @Process nor @ProcessPart`).

## Exercise

Add an `EXP-ARCHIVE` task ("Archive claim") that goes to `EXP-REJECTED`, but without any flow reaching it. Before
starting the application, predict which kind of error will come out. Then fix it by deleting the task.

<details><summary>Solution</summary>

```java
@ServiceTask(id = "EXP-ARCHIVE", name = "Archive claim", outgoing = @SequenceFlow(targetRef = REJECTED))
void archive() {
}
```

It compiles: the annotation is correct on its own. At startup (or in `./mvnw test`):

```
  - [UNREACHABLE_FLOW_NODE] Process EXP-CLAIM v1: flow node EXP-ARCHIVE is not reachable from the start event
```

Every node must be reachable from the start event and must be able to reach an end event. Delete the method to go back
to the model of lesson 3.

</details>

## Learn more

- [Model validation](../validation.md): [at compile time](../validation.md#at-compile-time),
  [at startup](../validation.md#at-startup) and [all the error kinds](../validation.md#error-kinds).
- [Getting started: annotation processor](../getting-started.md#2-annotation-processor).

---

[← Previous: Decisions with gateways](03-decisions-with-gateways.md) | [Index](README.md) | [Next: Running with the journal →](05-running-with-the-journal.md)
