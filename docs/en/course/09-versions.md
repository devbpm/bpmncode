# Lesson 9. Process and rule versions

> 🇪🇸 [Versión en español](../../es/curso/09-versiones.md)

**Goal:** lower the approval threshold to 500 EUR by publishing version 2 of the process, without breaking the claims
that are still running on version 1.

## What you will learn

- When a change needs a new version of the process or of a rule.
- How to declare two versions of a rule and reference each one with `ID@version`.
- How two versions of a process coexist and who decides which one is started.
- How to complete a task of an old instance with the code of its version.

## Starting point

The code from [lesson 8](08-persistence.md): instances and claims are stored in H2 with JPA, and `submit`, `approve`
and `confirm` are transactional. Before you start, **start the application and leave a claim of 1,800 EUR waiting for
approval**: it will be your "old" version 1 instance. Write down its `claimId`.

## Steps

### 1. Do you need a new version?

An instance is tied forever to the version it started with: the journal checks it against that definition even if
another one is deployed. So the question is: should running instances keep what they had?

| Change | New version? |
|---|---|
| Refactoring without changing flow or rules, changing texts | no |
| Adding, removing or reordering nodes or flows | yes, of the process |
| Changing the threshold or the logic of a rule | yes, of the rule; and of the process, if instances must be able to tell which version was applied to them |
| Changing the meaning of a rule | a new rule, with another id |

The new threshold changes rule `R-EXP-002`. You want the history to tell each claim which threshold was applied to it,
so there will be `R-EXP-002@2` and `EXP-CLAIM` v2.

### 2. Two versions of the rule

Each version is a different method (or type) with `@BusinessRule`. The previous version **is left untouched**.

`src/main/java/com/example/expenses/claim/ApprovalThresholdRule.java`:

```java
package com.example.expenses.claim;

import java.math.BigDecimal;

import io.github.eldiegos.bpmncode.annotation.BusinessRule;
import io.github.eldiegos.bpmncode.model.BusinessRuleRef;

public final class ApprovalThresholdRule {

	public static final String ID = "R-EXP-002";
	public static final String V1 = ID + BusinessRuleRef.SEPARATOR + 1;   // "R-EXP-002@1"
	public static final String V2 = ID + BusinessRuleRef.SEPARATOR + 2;   // "R-EXP-002@2"

	private static final BigDecimal THRESHOLD_V1 = new BigDecimal("1000");
	private static final BigDecimal THRESHOLD_V2 = new BigDecimal("500");

	private ApprovalThresholdRule() {
	}

	@BusinessRule(id = ID, version = 1, name = "Manager approval above 1,000 EUR")
	public static boolean needsApproval(BigDecimal amount) {
		return amount.compareTo(THRESHOLD_V1) > 0;
	}

	@BusinessRule(id = ID, version = 2, name = "Manager approval above 500 EUR")
	public static boolean needsApprovalV2(BigDecimal amount) {
		return amount.compareTo(THRESHOLD_V2) > 0;
	}
}
```

`BusinessRuleRef.SEPARATOR` is `"@"`, and the concatenation is a constant expression, so `V1` and `V2` are valid in an
annotation.

Start the application right now, without changing anything else. It does not start:

```
[AMBIGUOUS_REFERENCE] com.example.expenses.claim.ExpenseClaimProcess#route(ProcessInstanceRef, ExpenseClaim, boolean): references rule R-EXP-002 without a version, but it has several ([R-EXP-002 v1, R-EXP-002 v2]); use R-EXP-002@<version>
```

`"R-EXP-002"` without a version is only valid while the rule has a single one. In `ExpenseClaimProcess` (v1), change
the gateway's reference:

```java
	@ExclusiveGateway(id = ROUTE, name = "Needs approval?", businessRuleRefs = ApprovalThresholdRule.V1,
```

### 3. Version 2 of the process

Each version of a process is a class with the same `id` and a different `version`. Node ids are repeated across
versions (that is normal); that is why v2 reuses the constants of v1 with `import static`, and its `enum`s too. What
changes is the gateway: it applies `R-EXP-002@2` and its condition says 500 EUR.

`src/main/java/com/example/expenses/claim/ExpenseClaimProcessV2.java`:

```java
package com.example.expenses.claim;

import static com.example.expenses.claim.ExpenseClaimProcess.APPROVE;
import static com.example.expenses.claim.ExpenseClaimProcess.CHECK;
import static com.example.expenses.claim.ExpenseClaimProcess.DECISION;
import static com.example.expenses.claim.ExpenseClaimProcess.PAY;
import static com.example.expenses.claim.ExpenseClaimProcess.PAYMENT_ORDERED;
import static com.example.expenses.claim.ExpenseClaimProcess.PROCESS_ID;
import static com.example.expenses.claim.ExpenseClaimProcess.REJECTED;
import static com.example.expenses.claim.ExpenseClaimProcess.ROUTE;
import static com.example.expenses.claim.ExpenseClaimProcess.SUBMITTED;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.expenses.claim.ExpenseClaimProcess.Decision;
import com.example.expenses.claim.ExpenseClaimProcess.Route;

import io.github.eldiegos.bpmncode.annotation.BusinessRuleTask;
import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.MessageEndEvent;
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
@Process(id = PROCESS_ID, name = "Expense claim", version = 2,
		documentation = "An employee claims an expense; amounts above 500 EUR need the approval of a manager.")
public class ExpenseClaimProcessV2 {

	static final ProcessDefinitionRef KEY = ProcessDefinitionRef.of(PROCESS_ID, 2);

	private final ProcessJournal journal;
	private final ExpenseClaims claims;
	private final ApplicationEventPublisher events;

	ExpenseClaimProcessV2(ProcessJournal journal, ExpenseClaims claims, ApplicationEventPublisher events) {
		this.journal = journal;
		this.claims = claims;
		this.events = events;
	}

	@Transactional
	@StartEvent(id = SUBMITTED, name = "Claim submitted", outgoing = @SequenceFlow(targetRef = CHECK))
	public ProcessInstanceRef submit(ExpenseClaim claim) {
		claims.save(claim);
		var instance = journal.startProcessInstance(KEY, CorrelationKey.of(claim.id()), FlowNodeOutcome.of("SUBMITTED"));
		journal.setBusinessKey(instance, "claim:" + claim.id());
		var compliant = check(instance, claim);
		switch (route(instance, claim, compliant)) {
			case NON_COMPLIANT -> reject(instance, "NON_COMPLIANT");
			case AUTO_APPROVED -> pay(instance, claim);
			case NEEDS_APPROVAL -> {
				// Waits at EXP-APPROVE.
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

	@ExclusiveGateway(id = ROUTE, name = "Needs approval?", businessRuleRefs = ApprovalThresholdRule.V2,
			outgoing = {
					@SequenceFlow(when = "NON_COMPLIANT", targetRef = REJECTED, name = "Not compliant"),
					@SequenceFlow(when = "NEEDS_APPROVAL", targetRef = APPROVE, name = "Above threshold",
							conditionExpression = "Amount > 500 EUR"),
					@SequenceFlow(when = "AUTO_APPROVED", targetRef = PAY, name = "Within threshold") })
	Route route(ProcessInstanceRef instance, ExpenseClaim claim, boolean compliant) {
		Route route;
		if (!compliant) {
			route = Route.NON_COMPLIANT;
		}
		else if (ApprovalThresholdRule.needsApprovalV2(claim.amount())) {
			route = Route.NEEDS_APPROVAL;
		}
		else {
			route = Route.AUTO_APPROVED;
		}
		return journal.selectSequenceFlow(instance, ROUTE, route);
	}

	@Transactional
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

	@MessageEndEvent(id = PAYMENT_ORDERED, name = "Payment ordered", messageRef = PaymentOrderedV1.class)
	void paymentOrdered(ProcessInstanceRef instance, ExpenseClaim claim) {
		var message = new PaymentOrderedV1(journal.messageHeader(instance), claim.id(), claim.amount());
		journal.reachEndEvent(instance, PAYMENT_ORDERED, message);
		events.publishEvent(message);
	}

	@EndEvent(id = REJECTED, name = "Claim rejected")
	void reject(ProcessInstanceRef instance, String reason) {
		journal.reachEndEvent(instance, REJECTED, FlowNodeOutcome.of(reason));
	}
}
```

Yes, there is repeated code. It is deliberate: each version is a fixed snapshot of its process. When there are no
active v1 instances left, you delete its whole class without fear of breaking v2. If the logic of a step is long, move
it to a service that both use.

### 4. Who starts which version

Versions are not chosen on their own: **whoever starts chooses**. New claims come in through v2. Approvals, on the other
hand, can belong to instances of any version, so each one has to be dispatched to the code of its version. The journal
always checks the instance against its own definition, whatever class calls it; you dispatch by version because the
logic of each one may differ and so that traceability tells the truth about which code implements each step.

`src/main/java/com/example/expenses/claim/ClaimApprovals.java`:

```java
package com.example.expenses.claim;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.expenses.claim.ExpenseClaimProcess.Decision;

import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;

@Service
public class ClaimApprovals {

	private final ProcessJournal journal;
	private final ProcessInstanceHistory history;
	private final ExpenseClaimProcess v1;
	private final ExpenseClaimProcessV2 v2;

	ClaimApprovals(ProcessJournal journal, ProcessInstanceHistory history, ExpenseClaimProcess v1,
			ExpenseClaimProcessV2 v2) {
		this.journal = journal;
		this.history = history;
		this.v1 = v1;
		this.v2 = v2;
	}

	public boolean isWaitingForApproval(UUID claimId) {
		return journal.resume(ExpenseClaimProcess.PROCESS_ID, CorrelationKey.of(claimId))
				.map(instance -> history.isWaitingAt(instance.instanceId(), ExpenseClaimProcess.APPROVE))
				.orElse(false);
	}

	public void approve(UUID claimId, Decision decision) {
		var instance = journal.require(ExpenseClaimProcess.PROCESS_ID, CorrelationKey.of(claimId));
		var version = history.instance(instance.instanceId()).orElseThrow().processVersion();
		switch (version) {
			case 1 -> v1.approve(claimId, decision);
			case 2 -> v2.approve(claimId, decision);
			default -> throw new IllegalStateException("No approval for EXP-CLAIM v" + version);
		}
	}
}
```

`ProcessInstanceRef` does not carry the version; the instance summary provides it (`processVersion()`).

### 5. The controller, with v2

`ClaimController` starts with v2 and approves through `ClaimApprovals`. It no longer needs `ProcessJournal`.

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

@RestController
@RequestMapping("/claims")
class ClaimController {

	record NewClaim(String employee, BigDecimal amount, boolean receiptsAttached) {
	}

	record Submitted(UUID claimId, UUID instanceId) {
	}

	record Approval(Decision decision) {
	}

	private final ExpenseClaimProcessV2 process;
	private final ClaimApprovals approvals;
	private final ProcessInstanceHistory history;

	ClaimController(ExpenseClaimProcessV2 process, ClaimApprovals approvals, ProcessInstanceHistory history) {
		this.process = process;
		this.approvals = approvals;
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
		if (!approvals.isWaitingForApproval(claimId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Claim " + claimId + " is not waiting for approval");
		}
		approvals.approve(claimId, approval.decision());
	}

	@GetMapping("/{claimId}/instances")
	List<ProcessInstanceSummary> instances(@PathVariable UUID claimId) {
		return history.instances(CorrelationKey.of(claimId));
	}
}
```

### 6. A test for v2

Add the two beans and a test to `ExpenseClaimProcessTests`: 700 EUR did not need approval in v1, but it does in v2.

```java
	@Autowired
	ExpenseClaimProcessV2 v2;

	@Autowired
	ClaimApprovals approvals;

	@Test
	void version2AsksForApprovalAbove500() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("700"), true);

		var instance = v2.submit(claim);
		assertThat(history.isWaitingAt(instance.instanceId(), "EXP-APPROVE")).isTrue();

		approvals.approve(claim.id(), Decision.REJECTED);
		assertThat(history.flowNodeInstances(instance.instanceId()))
				.extracting(HistoricFlowNodeInstance::flowNodeId)
				.endsWith("EXP-DECISION", "EXP-REJECTED");
	}
```

New imports: `com.example.expenses.claim.ClaimApprovals` and `com.example.expenses.claim.ExpenseClaimProcessV2`.

## Check that it works

Start the application. The log shows both versions:

```
Process definition EXP-CLAIM v1 'Expense claim': 8 flow nodes, 9 sequence flows
Process definition EXP-CLAIM v2 'Expense claim': 8 flow nodes, 9 sequence flows
Process definition EXP-PAYMENT v1 'Expense payment': 3 flow nodes, 2 sequence flows
```

- Approve the "old" claim you left waiting: it answers `204`, and in `GET /claims/{claimId}/instances` its `EXP-CLAIM`
  instance still says `"processVersion":1`. It finished with the version it started with.
- Send a new claim of 700 EUR: its instance is `"processVersion":2` and it waits at `EXP-APPROVE`.
- There is one diagram per version: `/bpmn/EXP-CLAIM` is the current one (the highest, v2) and `/bpmn/EXP-CLAIM-v1` the
  previous one. In the v2 diagram, `EXP-ROUTE` carries `<pac:businessRuleRef id="R-EXP-002" name="Manager approval
  above 500 EUR" version="2"/>`.
- `./mvnw test` passes, including the new test.

## Common mistakes

- **Referencing a rule that has several versions without a version**: `AMBIGUOUS_REFERENCE` (step 2).
- **A rule version that does not exist** (`"R-EXP-002@3"`):

  ```
  [UNKNOWN_BUSINESS_RULE_REF] …#route(ProcessInstanceRef, ExpenseClaim, boolean): references version 3 of rule R-EXP-002, which does not exist (versions: [R-EXP-002 v1, R-EXP-002 v2])
  ```

- **A `@ProcessPart` without a version** (if you did the exercise of lesson 2): with two versions of the process it is
  ambiguous and also gives `AMBIGUOUS_REFERENCE`. Specify `@ProcessPart(processRef = "EXP-CLAIM", version = 1)`.
- **Retiring v1 too early.** Before deleting `ExpenseClaimProcess`, check that it has no active instances left, e.g.
  with `history.instances(ProcessDefinitionRef.of("EXP-CLAIM", 1))` filtering the `ProcessInstanceSummary.Active`
  ones. You will have to move the constants it shares with v2 first. If you delete it with v1 claims still pending,
  at startup you will see the warning

  ```
  Active process instances belong to versions that are no longer deployed: [EXP-CLAIM v1]. They cannot continue until the @Process class of each version is deployed again (set pac.process.store.undeployed-versions=fail to stop the start-up instead)
  ```

  and approving them will fail with `Process instance <id> runs EXP-CLAIM v1, which is no longer deployed: keep the
  @Process class of every version that still has active instances`. With `pac.process.store.undeployed-versions=fail`
  the application does not start (see [Retiring a version](../versions.md#retiring-a-version)).

## Exercise

Treasury wants the currency in the payment order. Add `PaymentOrderedV2(MessageHeader header, UUID claimId,
BigDecimal amount, String currency)` and make **only v2** of the process publish it, with `"EUR"`. v1 keeps publishing
`PaymentOrderedV1`.

<details><summary>Solution</summary>

1. The new version of the message:

   ```java
   package com.example.expenses.claim;

   import java.math.BigDecimal;
   import java.util.UUID;

   import io.github.eldiegos.bpmncode.annotation.MessageVersion;
   import io.github.eldiegos.bpmncode.runtime.MessageHeader;

   @MessageVersion(2)
   public record PaymentOrderedV2(MessageHeader header, UUID claimId, BigDecimal amount, String currency)
   		implements PaymentOrdered {
   }
   ```

2. The family admits it: `permits PaymentOrderedV1, PaymentOrderedV2`.
3. Compile. `ExpensePaymentProcess` fails, exactly where it should:
   `the switch expression does not cover all possible input values`. Add the case:

   ```java
   		var amount = switch (order) {
   			case PaymentOrderedV1 v1 -> v1.amount();
   			case PaymentOrderedV2 v2 -> v2.amount();
   		};
   ```

   (with its `import com.example.expenses.claim.PaymentOrderedV2;`).
4. In `ExpenseClaimProcessV2`, the end event publishes the new version:

   ```java
   	@MessageEndEvent(id = PAYMENT_ORDERED, name = "Payment ordered", messageRef = PaymentOrderedV2.class)
   	void paymentOrdered(ProcessInstanceRef instance, ExpenseClaim claim) {
   		var message = new PaymentOrderedV2(journal.messageHeader(instance), claim.id(), claim.amount(), "EUR");
   		journal.reachEndEvent(instance, PAYMENT_ORDERED, message);
   		events.publishEvent(message);
   	}
   ```

5. Start the application. The new contract is not frozen and the error gives you the line:

   ```
   [MESSAGE_CONTRACT_NOT_LOCKED] Message com.example.expenses.claim.PaymentOrderedV2: EV-EXP-PAYMENT-ORDERED@2 is not frozen in pac/message-contracts.lock; add the line: EV-EXP-PAYMENT-ORDERED@2 = sha256:c95ccd6fe4a496f95094a055b611fdcd54b6d787e0894e579b5328e19f9129ae
   ```

   Add it to `pac/message-contracts.lock`. `PaymentOrderedV1` is left untouched: there may be v1 messages in flight and
   v1 of the process keeps publishing them.

The rest of the course does not depend on this change: you can keep it or undo it. If you keep it, the lesson 7 test
that expects `MESSAGE_PUBLISHED:EV-EXP-PAYMENT-ORDERED@1` still passes, because it uses v1.

</details>

## Learn more

- [Versions](../versions.md): [process versions](../versions.md#process-versions),
  [when to create a version](../versions.md#when-to-create-a-version) and
  [business rule versions](../versions.md#business-rule-versions).
- [Messages: changing a contract](../messages.md#changing-a-contract).
- [Execution: versions and running instances](../execution.md#versions-and-running-instances).

---

[← Previous: Persistence](08-persistence.md) | [Index](README.md) | [Next: BPMN, traceability and i18n →](10-bpmn-traceability-i18n.md)
