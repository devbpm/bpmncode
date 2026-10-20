# Lección 5. Ejecutar con el journal

> 🇬🇧 [English version](../../en/course/05-running-with-the-journal.md)

**Objetivo:** que cada solicitud deje constancia de los pasos por los que pasa y que la librería rechace cualquier
paso que no siga el diagrama.

## Lo que vas a aprender

- Qué es una instancia de proceso, su correlación y su clave de negocio.
- Qué método de `ProcessJournal` corresponde a cada tipo de nodo.
- Cómo se registra un resultado con `FlowNodeOutcome`.
- Qué es la conformidad en ejecución y qué mensajes da cuando tu código se salta el diagrama.
- Cómo consultar las instancias y su evidencia con `ProcessInstanceHistory`.

## Punto de partida

El código de la [lección 3](03-decisiones-con-gateways.md) (la lección 4 no lo cambió): `ExpenseClaimProcess` con 8
nodos cuyos métodos se llaman entre sí, pero sin dejar rastro, y la prueba `ProcessModelTests`.

## Pasos

### 1. Describir no es registrar

Las anotaciones **describen** el proceso: con ellas se valida y se dibuja. Pero no dicen nada de lo que pasa con cada
solicitud. Para eso está `ProcessJournal`, un bean de la librería. Tu código lo llama en cada paso y el journal:

1. guarda la **evidencia**: un registro por nodo, con su resultado y su momento;
2. comprueba la **conformidad**: que el paso sigue un flujo declarado desde el nodo actual. Si no, lanza
   `ProcessConformanceException` y no guarda nada.

`bpmncode` no es un motor: no decide qué se ejecuta ni cuándo. Tu código sigue mandando; el journal solo vigila que
coincida con el diagrama.

### 2. Instancia, correlación y clave de negocio

- Una **instancia** (`ProcessInstanceRef`) es una ejecución de una versión del proceso: una por solicitud.
- La **correlación** (`CorrelationKey`) agrupa las instancias relacionadas y sirve para encontrarlas más tarde. Hay
  como mucho **una instancia por proceso y correlación**. Como cada solicitud ya tiene un `UUID`, úsalo:
  `CorrelationKey.of(claim.id())`.
- La **clave de negocio** es una referencia legible al sujeto, p. ej. `claim:<uuid>`. Va en la evidencia, así que
  **nunca pongas datos personales** (ni el nombre del empleado ni su correo).

`ProcessDefinitionRef.of("EXP-CLAIM", 1)` identifica la versión que vas a iniciar. La lección 9 explica por qué la
elige quien inicia.

### 3. Un método del journal por tipo de nodo

| Nodo | Método | Devuelve |
|---|---|---|
| `@StartEvent` | `startProcessInstance(ref, correlationKey, outcome)` | la `ProcessInstanceRef` nueva |
| tareas | `completeActivity(instance, activityId, outcome)` | el número de iteración (1 si no hay bucle) |
| `@ExclusiveGateway` | `selectSequenceFlow(instance, gatewayId, constante)` | la misma constante |
| `@EndEvent` | `reachEndEvent(instance, endEventId, outcome)` | nada; la instancia queda completada |

Los eventos de fin de mensaje (`@MessageEndEvent`, lección 7) y de error (`@ErrorEndEvent`, lección 3) tienen su
propia sobrecarga: `reachEndEvent(instance, endEventId, message)` y `reachEndEvent(instance, endEventId, error)`, con
la excepción del error; esta registra `ERROR_THROWN` con el `errorCode` como detalle.

Los pasos son los nodos: no hay que registrar los flujos. Y como `selectSequenceFlow` devuelve la constante, el método
del gateway puede terminar con un `return journal.selectSequenceFlow(…)`.

### 4. Resultados

`FlowNodeOutcome` dice qué pasó en el nodo con un **código estable** (mayúsculas, dígitos y `_`, empezando por letra)
que una interfaz puede traducir, y opcionalmente un detalle no traducible:

```java
FlowNodeOutcome.of("COMPLIANT");
FlowNodeOutcome.of("RULES_VIOLATED", "R-EXP-001@1");   // qué regla, con su versión
FlowNodeOutcome.of("DECIDED", decision.name());
```

En los gateways no pones resultado: el journal registra `SEQUENCE_FLOW_SELECTED` con la constante y el destino.

### 5. El proceso, registrando cada paso

Cambios:

- El constructor recibe `ProcessJournal`.
- `submit` inicia la instancia y la devuelve. Los demás métodos reciben la `ProcessInstanceRef`.
- Cada método llama al journal con **su** id de nodo.
- `approve` recibe la instancia y la solicitud. En la lección 6 cambiará, porque la aprobación llega días después.

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
				// La instancia espera en EXP-APPROVE hasta que el responsable decida.
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

Si una solicitud necesita aprobación, `submit` termina con la instancia **activa**: el token está en `EXP-ROUTE`, que
ya eligió la rama hacia `EXP-APPROVE`. Sigue ahí hasta que alguien llame a `approve`.

### 6. Consulta lo que ha pasado

`ProcessInstanceHistory` es otro bean de la librería, de solo lectura:

| Método | Devuelve |
|---|---|
| `instance(instanceId)` | un `Optional<ProcessInstanceSummary>`: `Active` (en curso) o `Completed` (terminada) |
| `instances(correlationKey)` | las instancias de una correlación |
| `flowNodeInstances(instanceId)` | la evidencia, en orden: una `HistoricFlowNodeInstance` por paso |

Escribe una prueba de punta a punta. `@SpringBootTest` levanta la aplicación (y valida el modelo); sin base de datos,
las instancias van a memoria.

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

La tercera prueba es la conformidad en acción: una solicitud pequeña ya está pagada, así que aprobarla es un error de
programación y el journal lo impide.

## Comprueba que funciona

```bash
./mvnw test
```

Las tres pruebas pasan. En el log de la prueba verás el principio y el fin de cada instancia:

```
i.g.e.bpmncode.runtime.ProcessJournal    : Process instance 5aeb8652-… of EXP-CLAIM v1 started (correlation 7812269c-…)
i.g.e.bpmncode.runtime.ProcessJournal    : Process instance 5aeb8652-… of EXP-CLAIM completed at EXP-PAYMENT-ORDERED
```

Fíjate en el resultado del gateway: `SEQUENCE_FLOW_SELECTED:AUTO_APPROVED → EXP-PAY`. El código y el detalle van
separados por `:` cuando el resultado se escribe como texto.

## Errores frecuentes

La conformidad convierte los despistes en excepciones claras. Todas son `ProcessConformanceException` (una
`IllegalStateException`): son errores de programación, no de negocio.

- **Saltarse un nodo** (completar `EXP-PAY` justo después de iniciar):

  ```
  No sequence flow EXP-SUBMITTED → EXP-PAY is declared in EXP-CLAIM
  ```

- **Ir por una rama distinta de la elegida** (el gateway eligió `NEEDS_APPROVAL` y completas `EXP-PAY`):

  ```
  Gateway EXP-ROUTE selected Flow_EXP-ROUTE_EXP-APPROVE, not the sequence flow to EXP-PAY
  ```

- **Usar el método que no toca** (`reachEndEvent` sobre una tarea):

  ```
  EXP-APPROVE is not an end event but a userTask
  ```

- **Iniciar dos veces con la misma correlación** lanza `DuplicateProcessInstanceException` (una
  `ProcessConformanceException`):

  ```
  There is already an instance of EXP-CLAIM with correlation 0633e1d2-…
  ```

- **Un código de resultado en minúsculas** (`FlowNodeOutcome.of("approved")`): `IllegalArgumentException` con
  `Invalid outcome code: approved`.
- **Un método anotado que no llama al journal**: no da error, pero ese paso no queda en la evidencia y el siguiente
  paso fallará por conformidad. Cada nodo que ejecutas tiene que registrarse.

## Ejercicio

Escribe una prueba que envíe una solicitud **sin justificantes** de 50 EUR y compruebe que termina en `EXP-REJECTED`
y que el resultado de `EXP-CHECK` dice qué regla se incumplió.

<details><summary>Solución</summary>

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

`outcome()` es un `FlowNodeOutcome` con `code()` y `detail()` (un `Optional<String>`).

</details>

## Para saber más

- [Ejecución y conformidad](../ejecucion.md): [conceptos](../ejecucion.md#conceptos),
  [API de `ProcessJournal`](../ejecucion.md#api-de-processjournal),
  [qué se comprueba en cada paso](../ejecucion.md#qué-se-comprueba-en-cada-paso) y
  [resultados](../ejecucion.md#resultados).
- [Consultar: `ProcessInstanceHistory`](../ejecucion.md#consultar-processinstancehistory).

---

[← Anterior: Validación al compilar y al arrancar](04-validacion.md) | [Índice](README.md) | [Siguiente: Tareas que esperan →](06-tareas-que-esperan.md)
