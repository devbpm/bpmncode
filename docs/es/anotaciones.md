# Anotaciones

> 🇬🇧 [English version](../en/annotations.md)

Paquete `io.github.eldiegos.bpmncode.annotation`. Cada anotación se llama como el elemento BPMN 2.0.2 que genera y
sus atributos, como los atributos BPMN correspondientes (`id`, `name`, `documentation`, `outgoing`, `targetRef`,
`messageRef`, `errorRef`, `resourceRef`, `conditionExpression`). Lo que BPMN no define se exporta como extensión `pac:` (véase
[BPMN](bpmn-trazabilidad-i18n.md#extensiones-pac)).

## Resumen

| Anotación | Dónde | Elemento BPMN |
|---|---|---|
| `@Process` | clase | `bpmn:process` |
| `@ProcessPart` | clase | — (reparte los nodos de un proceso entre varias clases) |
| `@StartEvent` | método | `bpmn:startEvent` sin definición: lo inicia una llamada de la aplicación |
| `@MessageStartEvent` | método listener de Spring | `bpmn:startEvent` + `bpmn:messageEventDefinition` |
| `@TimerStartEvent` | método con `@Scheduled` | `bpmn:startEvent` + `bpmn:timerEventDefinition` |
| `@ServiceTask` | método | `bpmn:serviceTask` |
| `@UserTask` | método | `bpmn:userTask` |
| `@BusinessRuleTask` | método | `bpmn:businessRuleTask` |
| `@SendTask` | método | `bpmn:sendTask`: publica un mensaje y el proceso sigue |
| `@StandardLoop` | atributo `loop` de una tarea | `bpmn:standardLoopCharacteristics` |
| `@PotentialOwner` | atributo `potentialOwners` de una `@UserTask` | `bpmn:potentialOwner` |
| `@HumanPerformer` | atributo `humanPerformers` de una `@UserTask` | `bpmn:humanPerformer` |
| `@ExclusiveGateway` | método que devuelve un `enum` | `bpmn:exclusiveGateway` |
| `@SequenceFlow` | atributo `outgoing` de un nodo | `bpmn:sequenceFlow` |
| `@EndEvent` | método | `bpmn:endEvent` sin definición |
| `@MessageEndEvent` | método | `bpmn:endEvent` + `bpmn:messageEventDefinition`: publica un mensaje al terminar |
| `@ErrorEndEvent` | método | `bpmn:endEvent` + `bpmn:errorEventDefinition`: termina con un error de negocio |
| `@Message` | `sealed interface` o record | `bpmn:message` + `bpmn:itemDefinition` |
| `@MessageVersion` | record | `bpmn:itemDefinition` de una versión del mensaje |
| `@ProcessError` | excepción (subclase de `Throwable`) | `bpmn:error` + `bpmn:itemDefinition` |
| `@ProcessResource` | tipo Java (normalmente un record) | `bpmn:resource` (un rol de negocio) + `bpmn:resourceParameter` |
| `@BusinessRule` | clase o método | — (identifica el código de una regla de negocio) |

## Identificadores y nombres

- Todos los `id` deben ser **NCName** (empiezan por letra o `_`, sin espacios ni `:`), porque son ids XML del BPMN
  exportado. Ejemplos válidos: `ORDER`, `REG-CHECK-CREDIT`, `Task_1`.
- Los ids de nodos y flujos son únicos en toda la aplicación, no solo en su proceso: el diagrama de colaboración pone
  varios procesos en el mismo documento. Un prefijo por proceso (`REG-`, `VER-`) evita choques. La excepción son las
  versiones de un mismo proceso, que pueden (y suelen) repetir los ids de sus nodos.
- `name` es obligatorio y no puede estar vacío. Es el texto canónico (se recomienda en inglés); la interfaz lo traduce
  con claves derivadas del id (véase [i18n](bpmn-trazabilidad-i18n.md#internacionalización)).
- `documentation` es opcional y solo lleva texto de negocio; acaba en `bpmn:documentation`.
- Conviene declarar los ids como constantes (`static final String`) para usarlos también en las llamadas al journal.

## `@Process`

```java
@Process(id = "ORDER", name = "Order handling", version = 1, documentation = "…")
public class OrderHandling { … }
```

| Atributo | Obligatorio | Significado |
|---|---|---|
| `id` | sí | id estable del proceso, común a todas sus versiones |
| `name` | sí | nombre canónico |
| `version` | sí | versión, desde 1; cada versión es una clase distinta (véase [Versiones](versiones.md)) |
| `documentation` | no | descripción de negocio |

Hay exactamente una clase por par id y versión.

## `@ProcessPart`

Para repartir los nodos de un proceso grande entre varias clases:

```java
@ProcessPart(processRef = "ORDER", version = 1)
class OrderShipping {

	@ServiceTask(id = "SHIP", name = "Ship order", outgoing = @SequenceFlow(targetRef = "SHIPPED"))
	void ship(…) { … }
}
```

`version` puede omitirse (valor 0) solo si el proceso tiene una única versión. Una clase no puede llevar `@Process` y
`@ProcessPart` a la vez.

## Eventos de inicio

Cada versión de un proceso tiene exactamente un evento de inicio, de uno de estos tres tipos, con exactamente un
flujo de salida sin condición.

### `@StartEvent`: inicio por llamada

```java
@StartEvent(id = "ORDER-RECEIVED", name = "Order received", outgoing = @SequenceFlow(targetRef = "CHECK-CREDIT"))
public void receive(Order order) {
	var instance = journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("RECEIVED"));
	…
}
```

### `@MessageStartEvent`: inicio por mensaje

El método debe ser un listener de Spring (`@EventListener`, `@TransactionalEventListener`, o una anotación que los
lleve, como `@ApplicationModuleListener` de Spring Modulith) cuyo parámetro sea el mensaje.

```java
@ApplicationModuleListener
@MessageStartEvent(id = "ORDER-PAID", name = "Payment received", messageRef = PaymentReceived.class,
		outgoing = @SequenceFlow(targetRef = "PREPARE"))
public void on(PaymentReceived message) {
	var instance = journal.startProcessInstance(KEY, message);
	…
}
```

`messageRef` es la familia `@Message` (acepta todas sus versiones) o una versión concreta (`@MessageVersion`). La
instancia hereda la correlación de la cabecera del mensaje. Véase [Mensajes](mensajes.md).

### `@TimerStartEvent`: inicio por temporizador

El temporizador es el `@Scheduled` de Spring del mismo método; `bpmncode` no tiene planificador propio.

```java
@Scheduled(cron = "${shop.reminders.cron:0 0 8 * * *}", zone = "Europe/Madrid")
@TimerStartEvent(id = "EVERY-MORNING", name = "Every morning", outgoing = @SequenceFlow(targetRef = "REMIND"))
void everyMorning() {
	var instance = journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("TIMER_FIRED"));
	…
}
```

- Exige exactamente un `@Scheduled` activo (directo, dentro de `@Schedules` o en una anotación propia que lo lleve)
  con un único disparador: `cron`, `fixedRate` o `fixedDelay`. `cron = "-"` (desactivado) no vale.
- Los `${…}` se resuelven con la configuración de la aplicación, así que el BPMN muestra la planificación real.
- Se exporta como `timeCycle` (el cron, o `R/PT5M` para un intervalo) más `pac:schedule`, `pac:zone` y
  `pac:initialDelay`.
- Recuerda habilitar la planificación en la aplicación (`@EnableScheduling`).

## Tareas: `@ServiceTask`, `@UserTask`, `@BusinessRuleTask`

```java
@BusinessRuleTask(id = "EVALUATE", name = "Evaluate rules", businessRuleRefs = { "R-PER-002", "R-PER-001@2" },
		outgoing = @SequenceFlow(targetRef = "ELIGIBILITY"))
Assessment evaluate(…) { … }
```

| Atributo | Obligatorio | Significado |
|---|---|---|
| `id`, `name`, `documentation` | `id` y `name` | como en el resto de nodos |
| `outgoing` | sí | exactamente un `@SequenceFlow`, sin `when` ni `conditionExpression` |
| `businessRuleRefs` | sí en `@BusinessRuleTask` (al menos una); opcional en las otras | reglas que aplica: `ID` o `ID@versión` |
| `loop` | no | como mucho un `@StandardLoop` |
| `potentialOwners`, `humanPerformers` | no (solo en `@UserTask`) | quién puede o debe realizarla (véase [Roles de una tarea humana](#roles-de-una-tarea-humana)) |

Las tres se comportan igual en ejecución; la diferencia es semántica y se ve en el diagrama. Una `@UserTask` suele
ser un método al que llama la interfaz cuando la persona termina la tarea (véase
[Tareas que esperan](ejecucion.md#tareas-que-esperan)).

### Roles de una tarea humana

Una `@UserTask` puede declarar quién puede reclamarla y completarla (`potentialOwners`, elementos
`bpmn:potentialOwner`) y quién la realiza (`humanPerformers`, elementos `bpmn:humanPerformer`). Por defecto, ninguno.

> **La librería describe; la aplicación asigna.** Los roles documentan el modelo: aparecen en el BPMN y en el visor
> web, pero `bpmncode` no asigna la tarea a ninguna persona, no gestiona bandejas de tareas ni comprueba quién llama a
> `completeActivity`. Decidir a quién se asigna y quién puede completarla es código de tu aplicación (seguridad,
> consultas a tu directorio de usuarios…).

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

Cada `@PotentialOwner` y cada `@HumanPerformer` declara **exactamente uno** de estos atributos:

| Atributo | Significado | Se exporta como |
|---|---|---|
| `resourceRef` | un tipo anotado con [`@ProcessResource`](#processresource) | `bpmn:resourceRef` con `Resource_<id>` |
| `expression` | texto legible que identifica a quién corresponde (p. ej. `manager of the employee`) | `bpmn:resourceAssignmentExpression` con `bpmn:formalExpression language="text/plain"` |

Los roles se exportan dentro del `bpmn:userTask`, tras `bpmn:outgoing` y antes de `bpmn:standardLoopCharacteristics`,
con ids `<taskId>_PotentialOwner_<n>` y `<taskId>_HumanPerformer_<n>` (`n` empieza en 1, en el orden declarado). La
expresión no se evalúa: es texto para quien lee el modelo.

### `@ProcessResource`

Declara un `bpmn:resource`, un rol de negocio (p. ej. «Responsable de gastos»), en un tipo Java. No se llama
`@Resource` para no chocar con `jakarta.annotation.Resource`.

| Atributo | Obligatorio | Significado |
|---|---|---|
| `id` | sí | id estable del recurso (NCName, único en la aplicación) |
| `name` | sí | nombre canónico |
| `documentation` | no | descripción de negocio |

- Si el tipo es un record, cada componente es un `bpmn:resourceParameter` (id `Resource_<id>_<componente>`) cuyo
  `type` es un `bpmn:itemDefinition` `ItemDefinition_<id>_<componente>` con el tipo Java del componente (el de dentro
  si es `Optional<T>`). Un componente `Optional` tiene `isRequired="false"`; el resto, `isRequired="true"`. Si el tipo
  no es un record, el recurso no tiene parámetros.
- Se exporta como `bpmn:resource` (id `Resource_<id>`, `name`, `bpmn:documentation`) con la extensión
  `pac:definedBy class="…"` (el tipo Java), solo si alguna tarea humana del diagrama lo referencia.
- Aún no se admite `bpmn:resourceParameterBinding`: un rol no puede dar valor a los parámetros del recurso (p. ej. el
  departamento concreto). Los parámetros describen de qué depende el rol; resolverlos es cosa de la aplicación.

### `@SendTask`

Una tarea de envío publica un mensaje a mitad del proceso y la instancia continúa sin esperar respuesta. Tiene los
mismos atributos que las otras tareas (`id`, `name`, `documentation`, `outgoing` con exactamente un flujo,
`businessRuleRefs` opcional, `loop`) y además `messageRef`, obligatorio:

```java
@SendTask(id = "REQUEST-INVOICE", name = "Request invoice", messageRef = InvoiceRequested.class,
		outgoing = @SequenceFlow(targetRef = "ACTIVATE"))
void requestInvoice(ProcessInstanceRef instance, Subscription subscription) {
	var message = new InvoiceRequested(journal.messageHeader(instance), subscription.id());
	journal.completeActivity(instance, "REQUEST-INVOICE", message);   // registra MESSAGE_PUBLISHED
	events.publishEvent(message);                                      // después de registrarlo
}
```

- `messageRef` es una versión concreta, como en `@MessageEndEvent`: un record `@MessageVersion`, o el record de un mensaje de
  una sola versión; nunca la familia.
- Se exporta como `bpmn:sendTask` con el atributo `messageRef` y `pac:version`, y es el origen de un
  `bpmn:messageFlow` hacia cada `@MessageStartEvent` que consume el mensaje.
- Se completa con `completeActivity(instance, sendTaskId, message)`, no con un resultado (véase
  [Mensajes](mensajes.md#3-cabecera-del-mensaje)). Con `loop`, cada iteración envía un mensaje.

### `@StandardLoop`

Repite la tarea seguida mientras se cumple una condición (`testBefore="false"`: primero se ejecuta, luego se
comprueba). En el diagrama aparece el marcador ↻.

```java
@UserTask(id = "SUBMIT", name = "Submit new address", outgoing = @SequenceFlow(targetRef = "VALIDITY"),
		loop = @StandardLoop(loopCondition = "The address does not meet R-ADR-001", loopMaximum = 3))
public Result submit(…) {
	var attempt = journal.completeActivity(instance, "SUBMIT", outcome);   // 1, 2, 3…
	…
}
```

- `loopCondition` es texto legible y obligatorio. `loopMaximum` es el número máximo de iteraciones; 0 significa sin
  límite.
- `completeActivity` devuelve el número de iteración (`loopCounter`) y rechaza la que supere `loopMaximum`.
- Para comprobar la condición antes de ejecutar, o para repetir varios nodos, modela un ciclo con un
  `@ExclusiveGateway` cuyo flujo vuelva atrás.

## `@ExclusiveGateway`

El método devuelve un `enum`, y cada constante es un flujo de salida (`when`):

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

- Al menos dos flujos, y las constantes `when` son **exactamente** las del enum: ni sobra ni falta ninguna.
- `conditionExpression` es texto legible (por defecto, el valor de `when`) y se exporta como
  `bpmn:conditionExpression`.
- Un flujo que vuelve a un nodo anterior forma un ciclo; es válido si desde todos los nodos se puede llegar a un evento
  de fin.

## `@SequenceFlow`

Solo existe dentro de `outgoing`.

| Atributo | Significado |
|---|---|
| `targetRef` | id del nodo de destino (obligatorio); puede estar en otra clase del mismo proceso |
| `id` | id del flujo; por defecto `Flow_<origen>_<destino>` |
| `name` | etiqueta del flujo en el diagrama |
| `when` | solo en gateways: la constante del enum que selecciona esta rama (`pac:when`) |
| `conditionExpression` | solo en gateways: condición legible |

## Eventos de fin

Cada proceso tiene al menos un evento de fin, de cualquiera de estos tres tipos, y todos los nodos deben poder llegar a
alguno. Un evento de fin no tiene flujos de salida. Los tres tienen `id`, `name` y `documentation`; el de mensaje añade
`messageRef` y el de error, `errorRef`, ambos obligatorios.

### `@EndEvent`: fin simple

```java
@EndEvent(id = "SHIPPED", name = "Order shipped")
void shipped() { }
```

Se alcanza con `reachEndEvent(instance, endEventId, outcome)`.

### `@MessageEndEvent`: fin publicando un mensaje

```java
@MessageEndEvent(id = "COMPLETED", name = "Registration completed", messageRef = PersonRegisteredV2.class)
Result complete(…) { … }
```

Publica esa versión concreta (un record `@MessageVersion`, o el record de un mensaje de una sola versión; nunca la
familia) y la instancia termina; para publicar sin terminar, usa una [`@SendTask`](#sendtask). Es el origen de un
`bpmn:messageFlow` hacia cada `@MessageStartEvent` que consume el mensaje. Se alcanza con
`reachEndEvent(instance, endEventId, message)` (véase [Mensajes](mensajes.md#3-cabecera-del-mensaje)).

### `@ErrorEndEvent`: fin con error

```java
@ProcessError(id = "ERR-CREDIT-REJECTED", name = "Credit rejected", errorCode = "CREDIT_REJECTED")
public class CreditRejected extends RuntimeException { … }

@ErrorEndEvent(id = "LOAN-REJECTED", name = "Credit rejected", errorRef = CreditRejected.class)
void rejected(ProcessInstanceRef instance, CreditRejected error) {
	journal.reachEndEvent(instance, "LOAN-REJECTED", error);   // registra ERROR_THROWN con el errorCode
}
```

- `errorRef` es una excepción anotada con [`@ProcessError`](#processerror).
- La instancia termina en este evento (estado `COMPLETED`): el error es su resultado de negocio, no un fallo técnico.
- Se alcanza con `reachEndEvent(instance, endEventId, error)`, que acepta la excepción del error declarado o una
  subclase suya (véase [Ejecución](ejecucion.md#eventos-de-fin-de-error)).
- Se exporta como `bpmn:endEvent` con `bpmn:errorEventDefinition errorRef="Error_<id>"`.

### `@ProcessError`

Declara un `bpmn:error` en una clase que extiende `Throwable`:

| Atributo | Obligatorio | Significado |
|---|---|---|
| `id` | sí | id estable del error (NCName, único en la aplicación) |
| `name` | sí | nombre canónico |
| `errorCode` | sí | código estable con el que lo identifican otros sistemas; no puede estar vacío |
| `documentation` | no | descripción de negocio |

Se exporta como `bpmn:error` (id `Error_<id>`, `name`, `errorCode` y `structureRef`) más un `bpmn:itemDefinition`
(`ItemDefinition_<id>`) cuyo `structureRef` es la clase Java. Una subclase de la excepción que no lleve su propio
`@ProcessError` cuenta como el mismo error.

## `@Message` y `@MessageVersion`

Declaran los mensajes que un proceso publica (al terminar o con una tarea de envío) y otro recibe. Se explican en [Mensajes entre procesos](mensajes.md).

```java
@Message(id = "EV-PERSON-REGISTERED", name = "Person registration completed")
public sealed interface PersonRegistered extends ProcessMessage permits PersonRegisteredV1, PersonRegisteredV2 { … }

@MessageVersion(1)
public record PersonRegisteredV1(MessageHeader header, PersonId personId, Instant registeredAt)
		implements PersonRegistered { }
```

## `@BusinessRule`

Identifica el código de una regla de negocio, en una clase o en un método:

```java
@BusinessRule(id = "R-PER-001", version = 2, name = "Minimum age of 18",
		documentation = "A person younger than 18 on the reference date cannot be registered.")
public static Optional<Violation> checkAdult(BirthDate birthDate, LocalDate referenceDate) { … }
```

- `version` empieza en 1 (valor por defecto). Varias versiones de la misma regla conviven, cada una en su tipo o método.
- Los nodos la referencian con `businessRuleRefs`: `"R-PER-001"` si solo tiene una versión, o `"R-PER-001@2"`
  (`BusinessRuleRef.SEPARATOR` es `@`). Véase [Versiones](versiones.md#versiones-de-las-reglas).
- BPMN no modela las reglas: la referencia se exporta como `pac:businessRuleRef`.

## Visibilidad y llamadas

- Los métodos anotados pueden ser privados. `bpmncode` los lee por reflexión, sin instanciar las clases ni
  interceptar las llamadas.
- Las anotaciones no ejecutan nada: el orden real lo marca tu código, y el journal comprueba que sigue los flujos
  declarados.
