# Lección 9. Versiones de procesos y reglas

> 🇬🇧 [English version](../../en/course/09-versions.md)

**Objetivo:** bajar el umbral de aprobación a 500 EUR publicando la versión 2 del proceso, sin romper las solicitudes
que siguen en curso con la versión 1.

## Lo que vas a aprender

- Cuándo un cambio necesita una versión nueva del proceso o de una regla.
- Cómo declarar dos versiones de una regla y referenciar cada una con `ID@versión`.
- Cómo conviven dos versiones de un proceso y quién decide cuál se inicia.
- Cómo completar una tarea de una instancia antigua con el código de su versión.

## Punto de partida

El código de la [lección 8](08-persistencia.md): las instancias y las solicitudes se guardan en H2 con JPA, y
`submit`, `approve` y `confirm` son transaccionales. Antes de empezar, **arranca la aplicación y deja una solicitud de
1.800 EUR esperando aprobación**: será tu instancia «antigua» de la versión 1. Apunta su `claimId`.

## Pasos

### 1. ¿Hace falta una versión nueva?

Una instancia queda ligada para siempre a la versión con la que empezó: el journal la comprueba contra esa definición
aunque se despliegue otra. Así que la pregunta es: ¿deben las instancias en curso seguir con lo que había?

| Cambio | ¿Versión nueva? |
|---|---|
| Refactorizar sin cambiar flujo ni reglas, cambiar textos | no |
| Añadir, quitar o reordenar nodos o flujos | sí, del proceso |
| Cambiar el umbral o la lógica de una regla | sí, de la regla; y del proceso, si las instancias deben poder decir qué versión se les aplicó |
| Cambiar el significado de una regla | regla nueva, con otro id |

El nuevo umbral cambia la regla `R-EXP-002`. Quieres que el historial diga a cada solicitud qué umbral se le aplicó,
así que habrá `R-EXP-002@2` y `EXP-CLAIM` v2.

### 2. Dos versiones de la regla

Cada versión es un método (o un tipo) distinto con `@BusinessRule`. La versión anterior **no se toca**.

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

`BusinessRuleRef.SEPARATOR` es `"@"`, y la suma es una expresión constante, así que `V1` y `V2` valen en una
anotación.

Arranca ahora mismo, sin tocar nada más. La aplicación no arranca:

```
[AMBIGUOUS_REFERENCE] com.example.expenses.claim.ExpenseClaimProcess#route(ProcessInstanceRef, ExpenseClaim, boolean): references rule R-EXP-002 without a version, but it has several ([R-EXP-002 v1, R-EXP-002 v2]); use R-EXP-002@<version>
```

`"R-EXP-002"` sin versión solo vale mientras la regla tiene una. En `ExpenseClaimProcess` (v1), cambia la referencia
del gateway:

```java
	@ExclusiveGateway(id = ROUTE, name = "Needs approval?", businessRuleRefs = ApprovalThresholdRule.V1,
```

### 3. La versión 2 del proceso

Cada versión de un proceso es una clase con el mismo `id` y otra `version`. Los ids de los nodos se repiten entre
versiones (es lo normal); por eso la v2 reutiliza las constantes de la v1 con `import static`, y también sus `enum`.
Lo que cambia es el gateway: aplica `R-EXP-002@2` y su condición dice 500 EUR.

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
				// Espera en EXP-APPROVE.
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

Sí, hay código repetido. Es deliberado: cada versión es una foto fija de su proceso. Cuando ya no queden instancias
activas de la v1, borras su clase entera sin miedo a romper la v2. Si la lógica de un paso es larga, sácala a un
servicio que usen las dos.

### 4. Quién inicia qué versión

Las versiones no se eligen solas: **quien inicia elige**. Las solicitudes nuevas entran por la v2. Las aprobaciones,
en cambio, pueden ser de instancias de cualquier versión, así que hay que despachar cada una al código de su versión.
El journal comprueba siempre la instancia contra su propia definición, sea cual sea la clase que lo llame; despachas
por versión porque la lógica de cada una puede ser distinta y para que la trazabilidad diga la verdad sobre qué código
implementa cada paso.

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

`ProcessInstanceRef` no lleva la versión; la da el resumen de la instancia (`processVersion()`).

### 5. El controlador, con la v2

`ClaimController` inicia con la v2 y aprueba a través de `ClaimApprovals`. Ya no necesita `ProcessJournal`.

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

### 6. Una prueba de la v2

Añade a `ExpenseClaimProcessTests` los dos beans y una prueba: 700 EUR no necesitaba aprobación en la v1, pero sí en la
v2.

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

Imports nuevos: `com.example.expenses.claim.ClaimApprovals` y `com.example.expenses.claim.ExpenseClaimProcessV2`.

## Comprueba que funciona

Arranca. El log muestra las dos versiones:

```
Process definition EXP-CLAIM v1 'Expense claim': 8 flow nodes, 9 sequence flows
Process definition EXP-CLAIM v2 'Expense claim': 8 flow nodes, 9 sequence flows
Process definition EXP-PAYMENT v1 'Expense payment': 3 flow nodes, 2 sequence flows
```

- Aprueba la solicitud «antigua» que dejaste esperando: responde `204`, y en `GET /claims/{claimId}/instances` su
  instancia de `EXP-CLAIM` sigue diciendo `"processVersion":1`. Terminó con la versión con la que empezó.
- Envía una solicitud nueva de 700 EUR: su instancia es `"processVersion":2` y espera en `EXP-APPROVE`.
- Hay un diagrama por versión: `/bpmn/EXP-CLAIM` es la vigente (la mayor, v2) y `/bpmn/EXP-CLAIM-v1` la anterior. En
  el de la v2, `EXP-ROUTE` lleva `<pac:businessRuleRef id="R-EXP-002" name="Manager approval above 500 EUR"
  version="2"/>`.
- `./mvnw test` pasa, también la prueba nueva.

## Errores frecuentes

- **Referenciar una regla con varias versiones sin versión**: `AMBIGUOUS_REFERENCE` (paso 2).
- **Una versión de regla que no existe** (`"R-EXP-002@3"`):

  ```
  [UNKNOWN_BUSINESS_RULE_REF] …#route(ProcessInstanceRef, ExpenseClaim, boolean): references version 3 of rule R-EXP-002, which does not exist (versions: [R-EXP-002 v1, R-EXP-002 v2])
  ```

- **Un `@ProcessPart` sin versión** (si hiciste el ejercicio de la lección 2): con dos versiones del proceso es
  ambiguo y también da `AMBIGUOUS_REFERENCE`. Indica `@ProcessPart(processRef = "EXP-CLAIM", version = 1)`.
- **Retirar la v1 demasiado pronto.** Antes de borrar `ExpenseClaimProcess`, comprueba que no quedan instancias
  activas suyas, p. ej. con `history.instances(ProcessDefinitionRef.of("EXP-CLAIM", 1))` filtrando las
  `ProcessInstanceSummary.Active`. Las constantes que comparte con la v2 tendrás que moverlas antes.

## Ejercicio

Tesorería quiere la moneda en la orden de pago. Añade `PaymentOrderedV2(MessageHeader header, UUID claimId,
BigDecimal amount, String currency)` y haz que **solo la v2** del proceso la publique, con `"EUR"`. La v1 sigue
publicando `PaymentOrderedV1`.

<details><summary>Solución</summary>

1. La nueva versión del mensaje:

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

2. La familia la admite: `permits PaymentOrderedV1, PaymentOrderedV2`.
3. Compila. Falla `ExpensePaymentProcess`, justo donde debe:
   `the switch expression does not cover all possible input values`. Añade el caso:

   ```java
   		var amount = switch (order) {
   			case PaymentOrderedV1 v1 -> v1.amount();
   			case PaymentOrderedV2 v2 -> v2.amount();
   		};
   ```

   (con su `import com.example.expenses.claim.PaymentOrderedV2;`).
4. En `ExpenseClaimProcessV2`, el evento de fin publica la versión nueva:

   ```java
   	@MessageEndEvent(id = PAYMENT_ORDERED, name = "Payment ordered", messageRef = PaymentOrderedV2.class)
   	void paymentOrdered(ProcessInstanceRef instance, ExpenseClaim claim) {
   		var message = new PaymentOrderedV2(journal.messageHeader(instance), claim.id(), claim.amount(), "EUR");
   		journal.reachEndEvent(instance, PAYMENT_ORDERED, message);
   		events.publishEvent(message);
   	}
   ```

5. Arranca. El contrato nuevo no está congelado y el error te da la línea:

   ```
   [MESSAGE_CONTRACT_NOT_LOCKED] Message com.example.expenses.claim.PaymentOrderedV2: EV-EXP-PAYMENT-ORDERED@2 is not frozen in pac/message-contracts.lock; add the line: EV-EXP-PAYMENT-ORDERED@2 = sha256:c95ccd6fe4a496f95094a055b611fdcd54b6d787e0894e579b5328e19f9129ae
   ```

   Añádela a `pac/message-contracts.lock`. `PaymentOrderedV1` no se toca: puede haber mensajes v1 en vuelo y la v1
   del proceso sigue publicándolos.

El resto del curso no depende de este cambio: puedes conservarlo o deshacerlo. Si lo conservas, la prueba de la
lección 7 que espera `MESSAGE_PUBLISHED:EV-EXP-PAYMENT-ORDERED@1` sigue pasando, porque usa la v1.

</details>

## Para saber más

- [Versiones](../versiones.md): [de un proceso](../versiones.md#versiones-de-un-proceso),
  [cuándo crear una](../versiones.md#cuándo-crear-una-versión) y [de las reglas](../versiones.md#versiones-de-las-reglas).
- [Mensajes: cambiar un contrato](../mensajes.md#cambiar-un-contrato).
- [Ejecución: versiones e instancias en curso](../ejecucion.md#versiones-e-instancias-en-curso).

---

[← Anterior: Persistencia](08-persistencia.md) | [Índice](README.md) | [Siguiente: BPMN, trazabilidad e i18n →](10-bpmn-trazabilidad-i18n.md)
