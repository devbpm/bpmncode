# Lesson 6. Waiting tasks

> 🇪🇸 [Versión en español](../../es/curso/06-tareas-que-esperan.md)

**Goal:** let the manager's approval arrive whenever it arrives, in another HTTP request, and have the process pick up
where it left off.

## What you will learn

- Why a human task cannot depend on having the `ProcessInstanceRef` at hand.
- How to recover an instance with `journal.require` and `journal.resume` from its correlation.
- How to know at which node an instance is waiting (`waitingFlowNodeId`, `nextFlowNodeIds`, `isWaitingAt`).
- How to expose the process with a REST controller that responds properly when it is not the task's turn.
- How to describe who approves with BPMN roles (`@ProcessResource`, `@PotentialOwner`) and why assigning the task is
  still up to your application.

## Starting point

The code from [lesson 5](05-running-with-the-journal.md): `ExpenseClaimProcess` records each step with the journal,
and `approve(ProcessInstanceRef, ExpenseClaim, Decision)` receives the instance and the claim. The
`ExpenseClaimProcessTests` and `ProcessModelTests` tests pass.

## Steps

### 1. The problem

In lesson 5, the test called `approve` right after `submit`, with the instance in hand. In real life, the manager
approves days later, from another screen. All that arrives is the claim id and the decision.

The solution lies in the correlation: since you used `CorrelationKey.of(claim.id())` and there is at most one instance
per process and correlation, the claim id is enough to find its instance. And you store the claim itself, like any
other business data.

### 2. Store the claims

In memory, for now. In [lesson 8](08-persistence.md) they will go to the database.

`src/main/java/com/example/expenses/claim/ExpenseClaims.java`:

```java
package com.example.expenses.claim;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
class ExpenseClaims {

	private final Map<UUID, ExpenseClaim> claims = new ConcurrentHashMap<>();

	void save(ExpenseClaim claim) {
		claims.put(claim.id(), claim);
	}

	Optional<ExpenseClaim> find(UUID id) {
		return Optional.ofNullable(claims.get(id));
	}
}
```

### 3. Resume the instance in the task

`journal.require(processId, correlationKey)` returns the `ProcessInstanceRef` of the instance of that process with that
correlation, or throws `ProcessConformanceException` if it does not exist. `journal.resume` does the same but returns
an `Optional`, for when you want to decide yourself what to do.

In `ExpenseClaimProcess`:

1. Add the `java.util.UUID` import.
2. Inject `ExpenseClaims` in the constructor and store the claim at the beginning of `submit`.
3. Change `approve` so that it receives only the id and the decision.

```java
	private final ProcessJournal journal;
	private final ExpenseClaims claims;

	ExpenseClaimProcess(ProcessJournal journal, ExpenseClaims claims) {
		this.journal = journal;
		this.claims = claims;
	}

	@StartEvent(id = SUBMITTED, name = "Claim submitted", outgoing = @SequenceFlow(targetRef = CHECK))
	public ProcessInstanceRef submit(ExpenseClaim claim) {
		claims.save(claim);
		var instance = journal.startProcessInstance(KEY, CorrelationKey.of(claim.id()), FlowNodeOutcome.of("SUBMITTED"));
		// … the rest of submit does not change …
	}

	@UserTask(id = APPROVE, name = "Approve claim", outgoing = @SequenceFlow(targetRef = DECISION))
	public void approve(UUID claimId, Decision decision) {
		var claim = claims.find(claimId).orElseThrow();
		var instance = journal.require(PROCESS_ID, CorrelationKey.of(claimId));
		journal.completeActivity(instance, APPROVE, FlowNodeOutcome.of("DECIDED", decision.name()));
		switch (decide(instance, decision)) {
			case APPROVED -> pay(instance, claim);
			case REJECTED -> reject(instance, "REJECTED_BY_MANAGER");
		}
	}
```

The rest of the class stays the same. Note that conformance still protects you: if the instance is not waiting at
`EXP-APPROVE`, `completeActivity` throws the exception and nothing is recorded.

### 4. At which node is it waiting?

While it waits for the approval, the instance is **active** and its token is still at `EXP-ROUTE`, the gateway that
chose the branch. `ProcessInstanceSummary.Active` tells it like this:

| Method | Value | Meaning |
|---|---|---|
| `flowNodeId()` | `EXP-ROUTE` | the last recorded node |
| `nextFlowNodeIds()` | `[EXP-APPROVE]` | the nodes conformance allows it to advance to now |
| `waitingFlowNodeId()` | `EXP-APPROVE` | the node it is waiting at: the next one, if there is only one |

For the most common question, "is it this task's turn?", use `history.isWaitingAt(instanceId, flowNodeId)`: it is
`true` if the instance is active and that node is among the ones it accepts now. You do not need to keep a state of
your own.

### 5. The REST controller

The controller checks the turn before calling the task and responds `409 Conflict` if it is not its turn. That way a
double click or an already paid claim does not reach conformance, which remains the last safety net.

`src/main/java/com/example/expenses/claim/ClaimController.java`:

```java
package com.example.expenses.claim;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.expenses.claim.ExpenseClaimProcess.Decision;

import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceSummary;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;

@RestController
@RequestMapping("/claims")
class ClaimController {

	record NewClaim(String employee, BigDecimal amount, boolean receiptsAttached) {
	}

	record Submitted(UUID claimId, UUID instanceId) {
	}

	record Approval(Decision decision) {
	}

	private final ExpenseClaimProcess process;
	private final ProcessJournal journal;
	private final ProcessInstanceHistory history;

	ClaimController(ExpenseClaimProcess process, ProcessJournal journal, ProcessInstanceHistory history) {
		this.process = process;
		this.journal = journal;
		this.history = history;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Submitted submit(@RequestBody NewClaim body) {
		var claim = new ExpenseClaim(UUID.randomUUID(), body.employee(), body.amount(), body.receiptsAttached());
		var instance = process.submit(claim);
		return new Submitted(claim.id(), instance.instanceId());
	}

	@PostMapping("/{claimId}/approval")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void approve(@PathVariable UUID claimId, @RequestBody Approval approval) {
		var waiting = journal.resume(ExpenseClaimProcess.PROCESS_ID, CorrelationKey.of(claimId))
				.map(instance -> history.isWaitingAt(instance.instanceId(), ExpenseClaimProcess.APPROVE))
				.orElse(false);
		if (!waiting) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim " + claimId + " is not waiting for approval");
		}
		process.approve(claimId, approval.decision());
	}

	@GetMapping("/{claimId}/instances")
	List<ProcessInstanceSummary> instances(@PathVariable UUID claimId) {
		return history.instances(CorrelationKey.of(claimId));
	}
}
```

### 6. Adjust the tests

In `ExpenseClaimProcessTests`, `approve` no longer receives the instance. Replace the two tests that used it:

```java
	@Test
	void aLargeClaimIsApprovedByAManager() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("1800"), true);

		var instance = process.submit(claim);
		assertThat(history.isWaitingAt(instance.instanceId(), "EXP-APPROVE")).isTrue();

		process.approve(claim.id(), Decision.APPROVED);
		assertThat(history.flowNodeInstances(instance.instanceId()))
				.extracting(HistoricFlowNodeInstance::flowNodeId)
				.containsExactly("EXP-SUBMITTED", "EXP-CHECK", "EXP-ROUTE", "EXP-APPROVE", "EXP-DECISION", "EXP-PAY",
						"EXP-PAYMENT-ORDERED");
	}

	@Test
	void aSmallClaimCannotBeApproved() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("80"), true);
		var instance = process.submit(claim);

		assertThat(history.isWaitingAt(instance.instanceId(), "EXP-APPROVE")).isFalse();
		assertThatThrownBy(() -> process.approve(claim.id(), Decision.APPROVED))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessage("Process instance " + instance.instanceId() + " is already completed");
	}
```

### 7. Who approves: describing it is not assigning it (reference)

The controller decides **when** the claim can be approved (the turn), but not **who**: anyone calling
`POST /claims/{claimId}/approval` approves. The journal does not look at it either: it checks the turn, not the person.

BPMN has vocabulary to describe who performs a user task, and `bpmncode` exports it: a **resource**
(`@ProcessResource`, a business role) and the roles of the task, `@PotentialOwner` (who can claim and complete it) and
`@HumanPerformer` (who performs it). Each role points to a resource with `resourceRef` or states it with a text
`expression`, never both. This is how the approval task would look:

`src/main/java/com/example/expenses/claim/ExpenseApprover.java`:

```java
package com.example.expenses.claim;

import io.github.eldiegos.bpmncode.annotation.ProcessResource;

@ProcessResource(id = "RES-EXPENSE-APPROVER", name = "Expense approver",
		documentation = "Manager who approves large expense claims")
record ExpenseApprover(String department) {
}
```

```java
	@UserTask(id = APPROVE, name = "Approve claim", outgoing = @SequenceFlow(targetRef = DECISION),
			potentialOwners = @PotentialOwner(resourceRef = ExpenseApprover.class),
			humanPerformers = @HumanPerformer(expression = "the manager of the employee"))
	public void approve(UUID claimId, Decision decision) {
		// … same as in step 3 …
	}
```

New imports in `ExpenseClaimProcess`: `io.github.eldiegos.bpmncode.annotation.PotentialOwner` and
`io.github.eldiegos.bpmncode.annotation.HumanPerformer`.

This **only describes** the model: it appears in the exported BPMN (lesson 10) and in the task card of the viewer
(lesson 11). The library does not assign the task to anyone nor check who calls `approve`. Deciding who is shown the
claim and who may approve it is still your application's code: for example, `ClaimController` (or `ClaimApprovals`,
from lesson 9 on) checking that the authenticated user is the manager of the employee's department before approving.
The record's `department` component is exported as a resource parameter, but the role cannot give it a value yet
(`bpmn:resourceParameterBinding` is not supported).

This block is a reference: the rest of the course does not include it. If you add it to your project, keep the
`potentialOwners` and `humanPerformers` attributes every time a lesson rewrites `approve` (also in
`ExpenseClaimProcessV2`, in lesson 9).

## Check that it works

`./mvnw test` passes. Then start the application and play the role of the employee and of the manager:

```bash
curl -s -XPOST localhost:8080/claims -H 'Content-Type: application/json' \
     -d '{"employee":"E-1042","amount":1800,"receiptsAttached":true}'
```

```json
{"claimId":"7206118a-e55f-41f1-ac07-c4662cff3191","instanceId":"1968a269-18c7-43df-95d1-32bcd133143e"}
```

Query the instance (replace the id):

```bash
curl -s localhost:8080/claims/7206118a-e55f-41f1-ac07-c4662cff3191/instances
```

```json
[{"instanceId":"1968a269-…","processId":"EXP-CLAIM","processVersion":1,
  "correlationKey":{"value":"7206118a-…"},"businessKey":"claim:7206118a-…",
  "flowNodeId":"EXP-ROUTE","flowNodeName":"Needs approval?","startedAt":"…",
  "triggeringMessage":null,"nextFlowNodeIds":["EXP-APPROVE"]}]
```

Approve twice:

```bash
curl -s -XPOST localhost:8080/claims/7206118a-…/approval -H 'Content-Type: application/json' \
     -d '{"decision":"APPROVED"}' -w '%{http_code}\n'
```

The first one responds `204`; the second one, `409`. If you query the instances again, the claim's instance is
completed at `EXP-PAYMENT-ORDERED` (with `endedAt`).

If you added step 7, everything responds the same: the roles change neither the execution nor who may approve.

## Common mistakes

- **`require` without an instance** (a claim id that does not exist):

  ```
  No EXP-CLAIM instance for correlation 3f2c…
  ```

  If that is not an error for you, use `resume` and handle the empty `Optional`.
- **Checking the turn with `flowNodeId()`.** While it waits for the approval, `flowNodeId()` is `EXP-ROUTE`, not
  `EXP-APPROVE`. Use `isWaitingAt` or `waitingFlowNodeId()`.
- **Restarting the application between `submit` and the approval.** Right now both the instances and the claims are in
  memory and are lost. [Lesson 8](08-persistence.md) solves this.
- **A role that does not say who, or says it twice** (if you added step 7): a `@PotentialOwner` without attributes, or
  with both `resourceRef` and `expression`, does not compile:

  ```
  a potential owner declares exactly one of resourceRef or expression
  ```

- **Forgetting `@ProcessResource` on the `resourceRef` type**: it does not compile (and, without the processor, the
  application does not start, with `UNKNOWN_RESOURCE_REF`):

  ```
  resourceRef com.example.expenses.claim.ExpenseApprover is not a type annotated with @ProcessResource
  ```

- **Expecting the library to stop someone else from approving.** Roles are not checked at runtime; you put the
  authorisation in the controller.

## Exercise

Add to `ClaimController` a `GET /claims/pending-approval` that returns the ids of the claims waiting for a manager.

<details><summary>Solution</summary>

`history.instances()` returns all the instances. Filter the active `EXP-CLAIM` ones that are waiting at
`EXP-APPROVE`; the claim id is the value of their correlation.

```java
	@GetMapping("/pending-approval")
	List<UUID> pendingApproval() {
		return history.instances().stream()
				.filter(ProcessInstanceSummary.Active.class::isInstance)
				.map(ProcessInstanceSummary.Active.class::cast)
				.filter(active -> active.processId().equals(ExpenseClaimProcess.PROCESS_ID))
				.filter(active -> active.waitingFlowNodeId().equals(ExpenseClaimProcess.APPROVE))
				.map(active -> active.correlationKey().value())
				.toList();
	}
```

`instances()` loads all the instances, active and completed. If you only need how many are waiting at each node,
`ProcessExecutionStatistics.waitingByNode` computes it per version (lesson 11).

</details>

## Learn more

- [Execution: waiting tasks](../execution.md#waiting-tasks).
- [Querying: `ProcessInstanceHistory`](../execution.md#querying-processinstancehistory).
- [Annotations: user task roles](../annotations.md#user-task-roles).

---

[← Previous: Running with the journal](05-running-with-the-journal.md) | [Index](README.md) | [Next: Messages between processes →](07-messages-between-processes.md)
