# Lección 6. Tareas que esperan

> 🇬🇧 [English version](../../en/course/06-waiting-tasks.md)

**Objetivo:** que la aprobación del responsable llegue cuando llegue, en otra petición HTTP, y que el proceso sepa
retomarse desde donde se quedó.

## Lo que vas a aprender

- Por qué una tarea humana no puede depender de tener la `ProcessInstanceRef` a mano.
- Cómo recuperar una instancia con `journal.require` y `journal.resume` a partir de su correlación.
- Cómo saber en qué nodo espera una instancia (`waitingFlowNodeId`, `nextFlowNodeIds`, `isWaitingAt`).
- Cómo exponer el proceso con un controlador REST que responde bien cuando la tarea no toca.
- Cómo describir quién aprueba con roles BPMN (`@ProcessResource`, `@PotentialOwner`) y por qué asignar la tarea
  sigue siendo cosa de tu aplicación.

## Punto de partida

El código de la [lección 5](05-ejecutar-con-el-journal.md): `ExpenseClaimProcess` registra cada paso con el journal,
y `approve(ProcessInstanceRef, ExpenseClaim, Decision)` recibe la instancia y la solicitud. Las pruebas
`ExpenseClaimProcessTests` y `ProcessModelTests` pasan.

## Pasos

### 1. El problema

En la lección 5, la prueba llamaba a `approve` justo después de `submit`, con la instancia en la mano. En la vida
real, el responsable aprueba días después, desde otra pantalla. Lo único que llega es el id de la solicitud y la
decisión.

La solución está en la correlación: como usaste `CorrelationKey.of(claim.id())` y hay como mucho una instancia por
proceso y correlación, el id de la solicitud basta para encontrar su instancia. Y la solicitud en sí la guardas tú,
como cualquier dato de negocio.

### 2. Guarda las solicitudes

De momento, en memoria. En la [lección 8](08-persistencia.md) irán a la base de datos.

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

### 3. Retoma la instancia en la tarea

`journal.require(processId, correlationKey)` devuelve la `ProcessInstanceRef` de la instancia de ese proceso con esa
correlación, o lanza `ProcessConformanceException` si no existe. `journal.resume` hace lo mismo pero devuelve un
`Optional`, para cuando quieras decidir tú qué hacer.

En `ExpenseClaimProcess`:

1. Añade el import `java.util.UUID`.
2. Inyecta `ExpenseClaims` en el constructor y guarda la solicitud al principio de `submit`.
3. Cambia `approve` para que reciba solo el id y la decisión.

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
		// … el resto de submit no cambia …
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

El resto de la clase queda igual. Fíjate en que la conformidad sigue protegiéndote: si la instancia no está esperando
en `EXP-APPROVE`, `completeActivity` lanza la excepción y no se registra nada.

### 4. ¿En qué nodo espera?

Mientras espera la aprobación, la instancia está **activa** y su token sigue en `EXP-ROUTE`, el gateway que eligió la
rama. `ProcessInstanceSummary.Active` lo cuenta así:

| Método | Valor | Significado |
|---|---|---|
| `flowNodeId()` | `EXP-ROUTE` | el último nodo registrado |
| `nextFlowNodeIds()` | `[EXP-APPROVE]` | los nodos a los que la conformidad permite avanzar ahora |
| `waitingFlowNodeId()` | `EXP-APPROVE` | el nodo en el que espera: el siguiente, si solo hay uno |

Para la pregunta más habitual, «¿le toca a esta tarea?», usa `history.isWaitingAt(instanceId, flowNodeId)`: es
`true` si la instancia está activa y ese nodo está entre los que admite ahora. No necesitas guardar un estado propio.

### 5. El controlador REST

El controlador comprueba el turno antes de llamar a la tarea y responde `409 Conflict` si no toca. Así un doble clic
o una solicitud ya pagada no llegan a la conformidad, que queda como última red.

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

### 6. Ajusta las pruebas

En `ExpenseClaimProcessTests`, `approve` ya no recibe la instancia. Sustituye las dos pruebas que lo usaban:

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

### 7. Quién aprueba: describirlo no es asignarlo (referencia)

El controlador decide **cuándo** se puede aprobar (el turno), pero no **quién**: cualquiera que llame a
`POST /claims/{claimId}/approval` aprueba. El journal tampoco lo mira: comprueba el turno, no la persona.

BPMN tiene vocabulario para describir quién realiza una tarea humana, y `bpmncode` lo exporta: un **recurso**
(`@ProcessResource`, un rol de negocio) y los roles de la tarea, `@PotentialOwner` (quién puede reclamarla y
completarla) y `@HumanPerformer` (quién la realiza). Cada rol apunta a un recurso con `resourceRef` o lo dice con una
`expression` de texto, nunca las dos cosas. Así quedaría la tarea de aprobación:

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
		// … igual que en el paso 3 …
	}
```

Imports nuevos en `ExpenseClaimProcess`: `io.github.eldiegos.bpmncode.annotation.PotentialOwner` e
`io.github.eldiegos.bpmncode.annotation.HumanPerformer`.

Esto **solo describe** el modelo: aparece en el BPMN exportado (lección 10) y en la ficha de la tarea del visor
(lección 11). La librería no asigna la tarea a nadie ni comprueba quién llama a `approve`. Decidir a quién se le
muestra la solicitud y quién puede aprobarla sigue siendo código de tu aplicación: por ejemplo, que `ClaimController`
(o `ClaimApprovals`, desde la lección 9) compruebe que el usuario autenticado es el responsable del departamento del
empleado antes de aprobar. El componente `department` del record se exporta como parámetro del recurso, pero aún no
se le puede dar valor desde el rol (`bpmn:resourceParameterBinding` no está soportado).

Este bloque es de referencia: el resto del curso no lo incluye. Si lo añades a tu proyecto, conserva los atributos
`potentialOwners` y `humanPerformers` cada vez que una lección reescriba `approve` (también en `ExpenseClaimProcessV2`,
en la lección 9).

## Comprueba que funciona

`./mvnw test` pasa. Después arranca la aplicación y juega el papel del empleado y del responsable:

```bash
curl -s -XPOST localhost:8080/claims -H 'Content-Type: application/json' \
     -d '{"employee":"E-1042","amount":1800,"receiptsAttached":true}'
```

```json
{"claimId":"7206118a-e55f-41f1-ac07-c4662cff3191","instanceId":"1968a269-18c7-43df-95d1-32bcd133143e"}
```

Consulta la instancia (sustituye el id):

```bash
curl -s localhost:8080/claims/7206118a-e55f-41f1-ac07-c4662cff3191/instances
```

```json
[{"instanceId":"1968a269-…","processId":"EXP-CLAIM","processVersion":1,
  "correlationKey":{"value":"7206118a-…"},"businessKey":"claim:7206118a-…",
  "flowNodeId":"EXP-ROUTE","flowNodeName":"Needs approval?","startedAt":"…",
  "triggeringMessage":null,"nextFlowNodeIds":["EXP-APPROVE"]}]
```

Aprueba dos veces:

```bash
curl -s -XPOST localhost:8080/claims/7206118a-…/approval -H 'Content-Type: application/json' \
     -d '{"decision":"APPROVED"}' -w '%{http_code}\n'
```

La primera responde `204`; la segunda, `409`. Si consultas otra vez las instancias, la de la solicitud está
completada en `EXP-PAYMENT-ORDERED` (con `endedAt`).

Si añadiste el paso 7, todo responde igual: los roles no cambian la ejecución ni quién puede aprobar.

## Errores frecuentes

- **`require` sin instancia** (un id de solicitud que no existe):

  ```
  No EXP-CLAIM instance for correlation 3f2c…
  ```

  Si para ti no es un error, usa `resume` y trata el `Optional` vacío.
- **Comprobar el turno con `flowNodeId()`.** Mientras espera la aprobación, `flowNodeId()` es `EXP-ROUTE`, no
  `EXP-APPROVE`. Usa `isWaitingAt` o `waitingFlowNodeId()`.
- **Reiniciar la aplicación entre `submit` y la aprobación.** Ahora mismo tanto las instancias como las solicitudes
  están en memoria y se pierden. Lo resuelve la [lección 8](08-persistencia.md).
- **Un rol que no dice quién, o que lo dice dos veces** (si añadiste el paso 7): `@PotentialOwner` sin atributos, o con
  `resourceRef` y `expression` a la vez, no compila:

  ```
  a potential owner declares exactly one of resourceRef or expression
  ```

- **Olvidar `@ProcessResource` en el tipo de `resourceRef`**: no compila (y, sin procesador, la aplicación no arranca
  con `UNKNOWN_RESOURCE_REF`):

  ```
  resourceRef com.example.expenses.claim.ExpenseApprover is not a type annotated with @ProcessResource
  ```

- **Esperar que la librería impida aprobar a otra persona.** Los roles no se comprueban en ejecución; la autorización
  la pones tú en el controlador.

## Ejercicio

Añade a `ClaimController` un `GET /claims/pending-approval` que devuelva los ids de las solicitudes que esperan a un
responsable.

<details><summary>Solución</summary>

`history.instances()` devuelve todas las instancias. Filtra las activas de `EXP-CLAIM` que esperan en
`EXP-APPROVE`; el id de la solicitud es el valor de su correlación.

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

`instances()` carga todas las instancias, activas y completadas. Si solo necesitas cuántas esperan en cada nodo,
`ProcessExecutionStatistics.waitingByNode` lo calcula por versión (lección 11).

</details>

## Para saber más

- [Ejecución: tareas que esperan](../ejecucion.md#tareas-que-esperan).
- [Consultar: `ProcessInstanceHistory`](../ejecucion.md#consultar-processinstancehistory).
- [Anotaciones: roles de una tarea humana](../anotaciones.md#roles-de-una-tarea-humana).

---

[← Anterior: Ejecutar con el journal](05-ejecutar-con-el-journal.md) | [Índice](README.md) | [Siguiente: Mensajes entre procesos →](07-mensajes-entre-procesos.md)
