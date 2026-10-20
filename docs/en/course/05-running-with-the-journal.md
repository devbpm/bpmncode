# Lesson 5. Running with the journal

> 🇪🇸 [Versión en español](../../es/curso/05-ejecutar-con-el-journal.md)

**Goal:** have every claim leave a record of the steps it goes through, and have the library reject any step that does
not follow the diagram.

## What you will learn

- What a process instance, its correlation and its business key are.
- Which `ProcessJournal` method corresponds to each node type.
- How to record an outcome with `FlowNodeOutcome`.
- What runtime conformance is and which messages it gives when your code skips the diagram.
- How to query instances and their evidence with `ProcessInstanceHistory`.

## Starting point

The code from [lesson 3](03-decisions-with-gateways.md) (lesson 4 did not change it): `ExpenseClaimProcess` with 8
nodes whose methods call each other, but without leaving a trace, and the `ProcessModelTests` test.

## Steps

### 1. Describing is not recording

The annotations **describe** the process: they are used to validate it and draw it. But they say nothing about what
happens to each claim. That is what `ProcessJournal`, a library bean, is for. Your code calls it at each step and the
journal:

1. stores the **evidence**: one record per node, with its outcome and its time;
2. checks **conformance**: that the step follows a declared flow from the current node. If not, it throws
   `ProcessConformanceException` and stores nothing.

`bpmncode` is not an engine: it does not decide what runs or when. Your code stays in charge; the journal only watches
that it matches the diagram.

### 2. Instance, correlation and business key

- An **instance** (`ProcessInstanceRef`) is one execution of a version of the process: one per claim.
- The **correlation** (`CorrelationKey`) groups related instances and lets you find them later. There is at most **one
  instance per process and correlation**. Since every claim already has a `UUID`, use it:
  `CorrelationKey.of(claim.id())`.
- The **business key** is a readable reference to the subject, e.g. `claim:<uuid>`. It goes into the evidence, so
  **never put personal data in it** (neither the employee's name nor their email).

`ProcessDefinitionRef.of("EXP-CLAIM", 1)` identifies the version you are going to start. Lesson 9 explains why whoever
starts the instance chooses it.

### 3. One journal method per node type

| Node | Method | Returns |
|---|---|---|
| `@StartEvent` | `startProcessInstance(ref, correlationKey, outcome)` | the new `ProcessInstanceRef` |
| tasks | `completeActivity(instance, activityId, outcome)` | the iteration number (1 if there is no loop) |
| `@ExclusiveGateway` | `selectSequenceFlow(instance, gatewayId, constant)` | the same constant |
| `@EndEvent` | `reachEndEvent(instance, endEventId, outcome)` | nothing; the instance is completed |

Message end events (`@MessageEndEvent`, lesson 7) and error end events (`@ErrorEndEvent`, lesson 3) have their own
overload: `reachEndEvent(instance, endEventId, message)` and `reachEndEvent(instance, endEventId, error)`, with the
exception of the error; the latter records `ERROR_THROWN` with the `errorCode` as detail.

The steps are the nodes: you do not record the flows. And since `selectSequenceFlow` returns the constant, the gateway
method can end with a `return journal.selectSequenceFlow(…)`.

### 4. Outcomes

`FlowNodeOutcome` says what happened at the node with a **stable code** (uppercase letters, digits and `_`, starting
with a letter) that a user interface can translate, and optionally a non-translatable detail:

```java
FlowNodeOutcome.of("COMPLIANT");
FlowNodeOutcome.of("RULES_VIOLATED", "R-EXP-001@1");   // which rule, with its version
FlowNodeOutcome.of("DECIDED", decision.name());
```

On gateways you do not set an outcome: the journal records `SEQUENCE_FLOW_SELECTED` with the constant and the target.

### 5. The process, recording each step

Changes:

- The constructor receives `ProcessJournal`.
- `submit` starts the instance and returns it. The other methods receive the `ProcessInstanceRef`.
- Each method calls the journal with **its own** node id.
- `approve` receives the instance and the claim. It will change in lesson 6, because the approval arrives days later.

`src/main/java/com/example/expenses/claim/ExpenseClaimProcess.java`:

```java
package com.example.expenses.claim;

import org.springframework.stereotype.Service;

import io.github.eldiegos.bpmncode.annotation.BusinessRuleTask;
import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.annotation.UserTask;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.FlowNodeOutcome;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceRef;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;

@Service
@Process(id = ExpenseClaimProcess.PROCESS_ID, name = "Expense claim", version = 1,
		documentation = "An employee claims an expense; large amounts need the approval of a manager before payment.")
public class ExpenseClaimProcess {

	public static final String PROCESS_ID = "EXP-CLAIM";
	public static final String SUBMITTED = "EXP-SUBMITTED";
	public static final String CHECK = "EXP-CHECK";
	public static final String ROUTE = "EXP-ROUTE";
	public static final String APPROVE = "EXP-APPROVE";
	public static final String DECISION = "EXP-DECISION";
	public static final String PAY = "EXP-PAY";
	public static final String PAYMENT_ORDERED = "EXP-PAYMENT-ORDERED";
	public static final String REJECTED = "EXP-REJECTED";

	static final ProcessDefinitionRef KEY = ProcessDefinitionRef.of(PROCESS_ID, 1);

	public enum Route { NON_COMPLIANT, NEEDS_APPROVAL, AUTO_APPROVED }

	public enum Decision { APPROVED, REJECTED }

	private final ProcessJournal journal;

	ExpenseClaimProcess(ProcessJournal journal) {
		this.journal = journal;
	}

	@StartEvent(id = SUBMITTED, name = "Claim submitted", outgoing = @SequenceFlow(targetRef = CHECK))
	public ProcessInstanceRef submit(ExpenseClaim claim) {
		var instance = journal.startProcessInstance(KEY, CorrelationKey.of(claim.id()), FlowNodeOutcome.of("SUBMITTED"));
		journal.setBusinessKey(instance, "claim:" + claim.id());
		var compliant = check(instance, claim);
		switch (route(instance, claim, compliant)) {
			case NON_COMPLIANT -> reject(instance, "NON_COMPLIANT");
			case AUTO_APPROVED -> pay(instance, claim);
			case NEEDS_APPROVAL -> {
				// The instance waits at EXP-APPROVE until the manager decides.
			}
		}
		return instance;
	}

	@BusinessRuleTask(id = CHECK, name = "Check claim against policy", businessRuleRefs = ExpensePolicyRule.ID,
			outgoing = @SequenceFlow(targetRef = ROUTE))
	boolean check(ProcessInstanceRef instance, ExpenseClaim claim) {
		var compliant = ExpensePolicyRule.complies(claim);
		journal.completeActivity(instance, CHECK, compliant ? FlowNodeOutcome.of("COMPLIANT")
				: FlowNodeOutcome.of("RULES_VIOLATED", "R-EXP-001@1"));
		return compliant;
	}

	@ExclusiveGateway(id = ROUTE, name = "Needs approval?", businessRuleRefs = ApprovalThresholdRule.ID,
			outgoing = {
					@SequenceFlow(when = "NON_COMPLIANT", targetRef = REJECTED, name = "Not compliant"),
					@SequenceFlow(when = "NEEDS_APPROVAL", targetRef = APPROVE, name = "Above threshold",
							conditionExpression = "Amount > 1,000 EUR"),
					@SequenceFlow(when = "AUTO_APPROVED", targetRef = PAY, name = "Within threshold") })
	Route route(ProcessInstanceRef instance, ExpenseClaim claim, boolean compliant) {
		Route route;
		if (!compliant) {
			route = Route.NON_COMPLIANT;
		}
		else if (ApprovalThresholdRule.needsApproval(claim.amount())) {
			route = Route.NEEDS_APPROVAL;
		}
		else {
			route = Route.AUTO_APPROVED;
		}
		return journal.selectSequenceFlow(instance, ROUTE, route);
	}

	@UserTask(id = APPROVE, name = "Approve claim", outgoing = @SequenceFlow(targetRef = DECISION))
	public void approve(ProcessInstanceRef instance, ExpenseClaim claim, Decision decision) {
		journal.completeActivity(instance, APPROVE, FlowNodeOutcome.of("DECIDED", decision.name()));
		switch (decide(instance, decision)) {
			case APPROVED -> pay(instance, claim);
			case REJECTED -> reject(instance, "REJECTED_BY_MANAGER");
		}
	}

	@ExclusiveGateway(id = DECISION, name = "Approved?",
			outgoing = {
					@SequenceFlow(when = "APPROVED", targetRef = PAY, name = "Yes"),
					@SequenceFlow(when = "REJECTED", targetRef = REJECTED, name = "No") })
	Decision decide(ProcessInstanceRef instance, Decision decision) {
		return journal.selectSequenceFlow(instance, DECISION, decision);
	}

	@ServiceTask(id = PAY, name = "Order payment", outgoing = @SequenceFlow(targetRef = PAYMENT_ORDERED))
	void pay(ProcessInstanceRef instance, ExpenseClaim claim) {
		journal.completeActivity(instance, PAY, FlowNodeOutcome.of("PAYMENT_ORDERED"));
		paymentOrdered(instance, claim);
	}

	@EndEvent(id = PAYMENT_ORDERED, name = "Payment ordered")
	void paymentOrdered(ProcessInstanceRef instance, ExpenseClaim claim) {
		journal.reachEndEvent(instance, PAYMENT_ORDERED, FlowNodeOutcome.of("COMPLETED"));
	}

	@EndEvent(id = REJECTED, name = "Claim rejected")
	void reject(ProcessInstanceRef instance, String reason) {
		journal.reachEndEvent(instance, REJECTED, FlowNodeOutcome.of(reason));
	}
}
```

If a claim needs approval, `submit` ends with the instance **active**: the token is at `EXP-ROUTE`, which has already
chosen the branch towards `EXP-APPROVE`. It stays there until someone calls `approve`.

### 6. Query what has happened

`ProcessInstanceHistory` is another library bean, read-only:

| Method | Returns |
|---|---|
| `instance(instanceId)` | an `Optional<ProcessInstanceSummary>`: `Active` (running) or `Completed` (finished) |
| `instances(correlationKey)` | the instances of a correlation |
| `flowNodeInstances(instanceId)` | the evidence, in order: one `HistoricFlowNodeInstance` per step |

Write an end-to-end test. `@SpringBootTest` starts the application (and validates the model); without a database, the
instances go to memory.

`src/test/java/com/example/expenses/ExpenseClaimProcessTests.java`:

```java
package com.example.expenses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.expenses.claim.ExpenseClaim;
import com.example.expenses.claim.ExpenseClaimProcess;
import com.example.expenses.claim.ExpenseClaimProcess.Decision;

import io.github.eldiegos.bpmncode.runtime.HistoricFlowNodeInstance;
import io.github.eldiegos.bpmncode.runtime.ProcessConformanceException;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceSummary;

@SpringBootTest
class ExpenseClaimProcessTests {

	@Autowired
	ExpenseClaimProcess process;

	@Autowired
	ProcessInstanceHistory history;

	@Test
	void aSmallClaimIsPaidStraightAway() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("120.50"), true);

		var instance = process.submit(claim);

		assertThat(history.instance(instance.instanceId())).get().isInstanceOf(ProcessInstanceSummary.Completed.class);
		assertThat(history.flowNodeInstances(instance.instanceId()))
				.extracting(HistoricFlowNodeInstance::flowNodeId)
				.containsExactly("EXP-SUBMITTED", "EXP-CHECK", "EXP-ROUTE", "EXP-PAY", "EXP-PAYMENT-ORDERED");
		assertThat(history.flowNodeInstances(instance.instanceId()))
				.extracting(step -> step.outcome().toString())
				.containsExactly("SUBMITTED", "COMPLIANT", "SEQUENCE_FLOW_SELECTED:AUTO_APPROVED → EXP-PAY",
						"PAYMENT_ORDERED", "COMPLETED");
	}

	@Test
	void aLargeClaimIsApprovedByAManager() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("1800"), true);

		var instance = process.submit(claim);
		assertThat(history.instance(instance.instanceId())).get().isInstanceOf(ProcessInstanceSummary.Active.class);

		process.approve(instance, claim, Decision.APPROVED);
		assertThat(history.flowNodeInstances(instance.instanceId()))
				.extracting(HistoricFlowNodeInstance::flowNodeId)
				.containsExactly("EXP-SUBMITTED", "EXP-CHECK", "EXP-ROUTE", "EXP-APPROVE", "EXP-DECISION", "EXP-PAY",
						"EXP-PAYMENT-ORDERED");
	}

	@Test
	void aSmallClaimCannotBeApproved() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("80"), true);
		var instance = process.submit(claim);

		assertThatThrownBy(() -> process.approve(instance, claim, Decision.APPROVED))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessage("Process instance " + instance.instanceId() + " is already completed");
	}
}
```

The third test is conformance in action: a small claim is already paid, so approving it is a programming error and the
journal prevents it.

## Check that it works

```bash
./mvnw test
```

The three tests pass. In the test log you will see the start and the end of each instance:

```
i.g.e.bpmncode.runtime.ProcessJournal    : Process instance 5aeb8652-… of EXP-CLAIM v1 started (correlation 7812269c-…)
i.g.e.bpmncode.runtime.ProcessJournal    : Process instance 5aeb8652-… of EXP-CLAIM completed at EXP-PAYMENT-ORDERED
```

Look at the gateway's outcome: `SEQUENCE_FLOW_SELECTED:AUTO_APPROVED → EXP-PAY`. The code and the detail are separated
by `:` when the outcome is written as text.

## Common mistakes

Conformance turns slips into clear exceptions. They are all `ProcessConformanceException` (an
`IllegalStateException`): they are programming errors, not business errors.

- **Skipping a node** (completing `EXP-PAY` right after starting):

  ```
  No sequence flow EXP-SUBMITTED → EXP-PAY is declared in EXP-CLAIM
  ```

- **Going down a branch other than the chosen one** (the gateway chose `NEEDS_APPROVAL` and you complete `EXP-PAY`):

  ```
  Gateway EXP-ROUTE selected Flow_EXP-ROUTE_EXP-APPROVE, not the sequence flow to EXP-PAY
  ```

- **Using the wrong method** (`reachEndEvent` on a task):

  ```
  EXP-APPROVE is not an end event but a userTask
  ```

- **Starting twice with the same correlation** throws `DuplicateProcessInstanceException` (a
  `ProcessConformanceException`):

  ```
  There is already an instance of EXP-CLAIM with correlation 0633e1d2-…
  ```

- **A lowercase outcome code** (`FlowNodeOutcome.of("approved")`): `IllegalArgumentException` with
  `Invalid outcome code: approved`.
- **An annotated method that does not call the journal**: it gives no error, but that step is missing from the evidence
  and the next step will fail on conformance. Every node you execute has to be recorded.

## Exercise

Write a test that submits a 50 EUR claim **without receipts** and checks that it ends at `EXP-REJECTED` and that the
outcome of `EXP-CHECK` says which rule was broken.

<details><summary>Solution</summary>

```java
@Test
void aClaimWithoutReceiptsIsRejected() {
	var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("50"), false);

	var instance = process.submit(claim);

	var steps = history.flowNodeInstances(instance.instanceId());
	assertThat(steps).extracting(HistoricFlowNodeInstance::flowNodeId)
			.containsExactly("EXP-SUBMITTED", "EXP-CHECK", "EXP-ROUTE", "EXP-REJECTED");
	assertThat(steps.get(1).outcome().toString()).isEqualTo("RULES_VIOLATED:R-EXP-001@1");
	assertThat(steps.get(3).outcome().code()).isEqualTo("NON_COMPLIANT");
}
```

`outcome()` is a `FlowNodeOutcome` with `code()` and `detail()` (an `Optional<String>`).

</details>

## Learn more

- [Execution and conformance](../execution.md): [concepts](../execution.md#concepts),
  [`ProcessJournal` API](../execution.md#processjournal-api),
  [what is checked at each step](../execution.md#what-is-checked-at-each-step) and
  [outcomes](../execution.md#outcomes).
- [Querying: `ProcessInstanceHistory`](../execution.md#querying-processinstancehistory).

---

[← Previous: Validation at compile time and at startup](04-validation.md) | [Index](README.md) | [Next: Waiting tasks →](06-waiting-tasks.md)
