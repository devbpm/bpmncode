# Ejecución y conformidad

> 🇬🇧 [English version](../en/execution.md)

`bpmncode` no es un motor de procesos: no decide qué se ejecuta ni cuándo. Tu código sigue siendo Java normal, y
`ProcessJournal` registra lo que ocurre en cada instancia y **rechaza lo que no encaja con el modelo declarado**. Esa
comprobación es la *conformidad en ejecución*.

## Conceptos

| Concepto | Tipo | Significado |
|---|---|---|
| Instancia de proceso | `ProcessInstanceRef` | una ejecución de una versión concreta de un proceso |
| Correlación | `CorrelationKey` | identificador de negocio que agrupa las instancias relacionadas; hay como mucho una instancia por proceso y correlación |
| Clave de negocio | `String` | referencia seudónima al sujeto de negocio, p. ej. `person:<uuid>`; nunca datos personales |
| Token | — | el nodo de flujo en el que está la instancia; solo hay uno (no hay paralelismo) |
| Evidencia | `HistoricFlowNodeInstance` | un registro por cada nodo por el que pasa la instancia, con su resultado |
| Resultado | `FlowNodeOutcome` | código (`[A-Z][A-Z0-9_]*`) y, opcionalmente, un detalle no traducible: `RULES_VIOLATED:R-PER-001@2` |

## API de `ProcessJournal`

| Método | Nodo | Qué hace |
|---|---|---|
| `startProcessInstance(ref, correlationKey, outcome)` | `@StartEvent`, `@TimerStartEvent` | crea la instancia de la versión `ref` en su evento de inicio |
| `startProcessInstance(ref, message, outcome)` | `@MessageStartEvent` | igual, con la correlación que trae la cabecera del mensaje |
| `startProcessInstance(ref, message)` | `@MessageStartEvent` | igual, con el resultado `MESSAGE_RECEIVED` y la versión recibida como detalle |
| `startProcessInstanceIfAbsent(ref, message, outcome)`, `startProcessInstanceIfAbsent(ref, correlationKey, outcome)` | eventos de inicio | inicia la instancia salvo que ya exista una del mismo proceso con esa correlación; devuelve `ProcessStart` (véase [Transacciones e idempotencia](#transacciones-e-idempotencia)) |
| `completeActivity(instance, activityId, outcome)` | tareas, salvo `@SendTask` | avanza a la tarea y devuelve su iteración (`loopCounter`) |
| `completeActivity(instance, sendTaskId, message)` | `@SendTask` | igual, registrando el mensaje que envía; la instancia sigue su flujo |
| `selectSequenceFlow(instance, gatewayId, enumValue)` | `@ExclusiveGateway` | avanza al gateway, fija el flujo de salida que se seguirá y devuelve la misma constante |
| `reachEndEvent(instance, endEventId, outcome)` | `@EndEvent` | avanza al evento de fin y completa la instancia |
| `reachEndEvent(instance, endEventId, message)` | `@MessageEndEvent` | igual, registrando el mensaje que se publica |
| `reachEndEvent(instance, endEventId, error)` | `@ErrorEndEvent` | igual, con la excepción del error declarado como resultado de negocio (véase [Eventos de fin de error](#eventos-de-fin-de-error)) |
| `messageHeader(instance)` | — | prepara la cabecera del mensaje que publicará la instancia |
| `resume(processId, correlationKey)` | — | recupera una instancia para continuarla, si existe |
| `require(processId, correlationKey)` | — | igual, pero la instancia tiene que existir: si no, `ProcessConformanceException` |
| `setBusinessKey(instance, businessKey)` | — | asocia la instancia a su sujeto de negocio |

Están anotadas con `@Transactional`: si la aplicación usa Spring Transaction, se unen a la transacción de quien llama;
si no, la anotación se ignora.

## Qué se comprueba en cada paso

Cada llamada lleva el token del nodo actual al nodo indicado, y antes comprueba que:

- la versión de la instancia sigue desplegada (véase [Versiones e instancias en curso](#versiones-e-instancias-en-curso));
- el nodo existe en **la versión de la instancia** (no en la vigente) y es del tipo que corresponde al método
  (`completeActivity` solo acepta tareas, `reachEndEvent` solo eventos de fin…), y que se usa la sobrecarga con mensaje
  justo cuando el nodo publica uno (evento de fin de mensaje, tarea de envío), y la sobrecarga con excepción justo
  cuando termina con un error;
- hay un flujo de secuencia declarado desde el nodo actual hasta él;
- si el nodo actual es un gateway, el destino es el del flujo que se seleccionó (`when`);
- la instancia no está completada;
- en un evento de inicio de mensaje, el proceso acepta ese mensaje y esa versión; en uno de fin de mensaje o en una
  tarea de envío, el mensaje es la versión que declara el nodo y su cabecera es de esta instancia.

Si algo no encaja, lanza `ProcessConformanceException` (una `IllegalStateException`) y no registra nada. Lo normal es
dejar que deshaga la transacción: es un error de programación, no de negocio.

```java
journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("RECEIVED"));
journal.reachEndEvent(instance, "SHIPPED", …);
// ProcessConformanceException: No sequence flow ORDER-RECEIVED → SHIPPED is declared in ORDER
```

Los pasos son los nodos, no los flujos: no hay que registrar el paso por un flujo de secuencia.

## Resultados

`FlowNodeOutcome` describe qué pasó en el nodo, con un código estable que la interfaz puede traducir:

```java
FlowNodeOutcome.of("PERSON_CREATED");
FlowNodeOutcome.of("RULES_VIOLATED", "R-PER-002@1, R-PER-001@2");
FlowNodeOutcome.of("RULES_VIOLATED", BusinessRuleRef.join(violatedRules));   // el mismo detalle, sin repetir
```

Algunos los pone el propio journal:

| Código | Cuándo |
|---|---|
| `SEQUENCE_FLOW_SELECTED` | `selectSequenceFlow`, con la constante y el nodo al que lleva como detalle, p. ej. `ACCEPT → ST-WORK` |
| `MESSAGE_PUBLISHED` | `reachEndEvent` con mensaje, con `ID@versión` como detalle; `completeActivity` de una tarea de envío, con `ID@versión <messageId>` |
| `MESSAGE_RECEIVED` | `startProcessInstance(ref, message)`, con `ID@versión` como detalle |
| `ERROR_THROWN` | `reachEndEvent` con excepción, con el `errorCode` del error como detalle |

Los cuatro códigos son constantes de `FlowNodeOutcome` (`FlowNodeOutcome.SEQUENCE_FLOW_SELECTED`…), y
`FlowNodeOutcome.messageReceived(messageVersionRef)` construye `MESSAGE_RECEIVED` si quieres registrarlo tú.

Como `selectSequenceFlow` devuelve la constante, el método del gateway puede terminar con él:

```java
@ExclusiveGateway(id = "CHECK-CREDIT", name = "Enough credit?", outgoing = { … })
Credit checkCredit(ProcessInstanceRef instance, Order order) {
	return journal.selectSequenceFlow(instance, "CHECK-CREDIT", credits.check(order));
}
```

Su forma de texto (`CÓDIGO` o `CÓDIGO:detalle`) ocupa como mucho `FlowNodeOutcome.MAX_LENGTH` (500) caracteres, lo
que guarda cualquier almacén. Uno más largo se rechaza al crearlo, igual con todos los almacenes:
`IllegalArgumentException: Outcome RULES_VIOLATED is 501 characters long; the evidence stores at most 500`.

Los resultados se guardan en la evidencia, que no debe llevar datos personales: usa códigos y referencias, y deja los
datos en tus propias tablas.

## Eventos de fin de error

Un `@ErrorEndEvent` es un desenlace del proceso, no un fallo técnico: la instancia termina en ese evento (estado
`COMPLETED`) y el error es su resultado de negocio. Se registra con la excepción del error:

```java
@ErrorEndEvent(id = "LOAN-REJECTED", name = "Credit rejected", errorRef = CreditRejected.class)
void rejected(ProcessInstanceRef instance, CreditRejected error) {
	journal.reachEndEvent(instance, "LOAN-REJECTED", error);
}
```

- La excepción tiene que ser la del error declarado en `errorRef` o una subclase suya; se busca con
  `Definitions#errorOf(Class)`, que recorre la clase y sus superclases.
- Registra `ERROR_THROWN` con el `errorCode` como detalle.
- Si quien llama vuelve a lanzar la excepción dentro de la misma transacción, la transacción se deshace y la evidencia
  con ella: el journal no lanza la excepción, solo la registra.

Errores de conformidad propios de estos eventos (`ProcessConformanceException`):

| Llamada | Mensaje |
|---|---|
| `reachEndEvent(instance, id, outcome)` sobre un `@ErrorEndEvent` | `<id> ends with the error <errorId>: reach it with reachEndEvent(instance, endEventId, error)` |
| `reachEndEvent(instance, id, error)` sobre un evento sin error | `<id> does not end with an error` |
| `reachEndEvent(instance, id, error)` con una excepción sin `@ProcessError` | `<clase> is not a @ProcessError` |
| `reachEndEvent(instance, id, error)` con la excepción de otro error | `<id> ends with the error <errorId>, not <otro errorId>` |

## Tareas que esperan

Una `@UserTask` puede terminar días después de iniciarse la instancia, en otra petición. La instancia se recupera con
su proceso y su correlación, que la aplicación guarda junto a sus datos:

```java
@Transactional
@UserTask(id = "CONFIRM", name = "Confirm new address", outgoing = @SequenceFlow(targetRef = "CONFIRMATION"))
public Result confirm(AddressChangeId id, Verdict verdict) {
	var change = changes.findById(id).orElseThrow();
	var instance = journal.require(PROCESS_ID, change.correlationKey());
	journal.completeActivity(instance, "CONFIRM", FlowNodeOutcome.of("OPERATOR_VERDICT", verdict.name()));
	…
}
```

`require` lanza `ProcessConformanceException` si no hay instancia; `resume` devuelve un `Optional` para decidir tú.

Para saber qué tareas esperan, consulta las instancias activas: `ProcessInstanceSummary.Active.waitingFlowNodeId()` es
el nodo en el que espera el token y `nextFlowNodeIds()`, los nodos a los que la conformidad permite avanzar ahora.
`ProcessInstanceHistory.isWaitingAt(instanceId, flowNodeId)` lo resume en una línea: indica si la instancia está
activa y ese nodo está entre los que admite ahora, p. ej. para comprobar el turno de una tarea humana sin guardar un
estado propio.

El journal comprueba el turno, no la persona: no mira quién llama a `completeActivity`. Los roles declarados con
`potentialOwners` y `humanPerformers` (véase [Roles de una tarea humana](anotaciones.md#roles-de-una-tarea-humana))
describen quién puede o debe realizar la tarea; asignarla y autorizar a quien la completa es código de la aplicación.

## Bucles y ciclos

Con un único token hay dos formas de repetir:

- **Bucle estándar** (`@StandardLoop` en una tarea): `completeActivity` sobre la tarea en la que ya está el token
  registra otra iteración. Devuelve el `loopCounter` (1 al llegar, 2 en la segunda vuelta…) y rechaza la iteración que
  supere `loopMaximum`. Una tarea sin `@StandardLoop` no puede completarse dos veces seguidas.
- **Ciclo**: un flujo de un gateway que vuelve a un nodo anterior. El journal lo trata como cualquier otro flujo
  declarado. Al volver a una tarea por un ciclo, su bucle empieza de nuevo en 1.

## Versiones e instancias en curso

Cada instancia queda ligada a la versión con la que empezó y avanza siempre según esa definición, aunque se despliegue
una versión más nueva. Quien inicia el proceso elige la versión (`ProcessDefinitionRef.of("ORDER", 2)`). Véase
[Versiones](versiones.md).

Si se retira la clase `@Process` de una versión que aún tiene instancias activas, esas instancias no pueden avanzar:
su siguiente paso lanza `ProcessConformanceException` (`Process instance <id> runs ORDER v1, which is no longer
deployed: keep the @Process class of every version that still has active instances`) y no registra nada. Se siguen
pudiendo consultar: las completadas, como siempre; las activas, con `nextFlowNodeIds()` vacío (y `isWaitingAt`
devuelve `false`). Al arrancar, la librería avisa de esas versiones (véase [Retirar una versión](versiones.md#retirar-una-versión)).

## Transacciones e idempotencia

- Con un almacén transaccional (el JPA o el JDBC, véase [Almacenamiento](almacenamiento.md)), llama al journal en la misma
  transacción que tus cambios de negocio: la evidencia y los datos se confirman o se deshacen juntos. El almacén en
  memoria no es transaccional.
- Hay como mucho una instancia por proceso y correlación: un segundo inicio con la misma correlación lanza
  `DuplicateProcessInstanceException`. Con mensajes entregados *al menos una vez* (p. ej. el registro de publicaciones
  de Spring Modulith), inicia con `startProcessInstanceIfAbsent` (abajo) o trata esa excepción como un duplicado.
- Las instancias usan bloqueo optimista: si dos avances concurrentes de la misma instancia parten del mismo estado, el
  segundo lanza `ProcessInstanceConflictException` y no guarda nada.
- Cada posición del historial de una instancia se registra una sola vez. Con transacción, dos pasos concurrentes de la
  misma instancia ya se serializan; sin ella, si los dos llegan a registrar la misma posición, el segundo lanza
  `ProcessInstanceConflictException` (`Flow node instance <n> of process instance <id> is already recorded: another
  step of the same instance was recorded concurrently`) en lugar de duplicarla.

`startProcessInstanceIfAbsent` hace idempotente un inicio: si ya hay una instancia del proceso con esa correlación no
registra nada y devuelve `ProcessStart.AlreadyStarted` con la existente (en cualquier estado, también completada); si
no, la inicia y devuelve `ProcessStart.Started`. Si otra transacción la inicia a la vez, la restricción única del
almacén sigue siendo la última garantía (`DuplicateProcessInstanceException`).

```java
switch (journal.startProcessInstanceIfAbsent(KEY, message, FlowNodeOutcome.of("REQUEST_RECEIVED"))) {
	case ProcessStart.Started started -> continueWith(started.instance());
	case ProcessStart.AlreadyStarted repeated -> log.info("Repeated delivery for {}", repeated.instance());
}
```

## Qué garantiza el journal

`bpmncode` es una librería: registra y comprueba lo que el código de la aplicación le comunica, y las decisiones de
política (cuándo llamar, qué transacción usar, qué hacer con un rechazo) son de la aplicación.

| Garantiza | No puede garantizar |
|---|---|
| que lo que se registra cumple el modelo de la versión de la instancia; si no, lo rechaza sin registrar nada | que el código de la aplicación llame al journal en cada paso: un paso que no se comunica no deja evidencia |
| que lo registrado queda como evidencia que solo se añade, con cada posición del historial una sola vez | la atomicidad entre el estado de la instancia, su historial y tus datos sin una transacción |

- **Llamadas que faltan.** Pruébalas con tests de camino: ejecuta cada recorrido del proceso y compara la evidencia con
  `ProcessAssertions.pathOf(history, instanceId)` (véase [`ProcessAssertions`](configuracion.md#processassertions)).
- **Atomicidad.** Depende de una transacción. Con Spring Boot y los almacenes JDBC o JPA la hay: el journal se une a
  la de quien llama (véase [Transacciones e idempotencia](#transacciones-e-idempotencia)). El almacén en memoria no es
  transaccional: lo que ya escribió un paso que falla después (el estado de la instancia, o tus datos) no se deshace.

## Consultar: `ProcessInstanceHistory`

| Método | Devuelve |
|---|---|
| `instances()` | todas las instancias, activas y completadas |
| `instances(correlationKey)` | las de una correlación (p. ej. un alta y la verificación que inició) |
| `instances(processDefinitionRef)` | las de una versión de un proceso, de la más reciente a la más antigua, con una sola consulta al almacén |
| `instance(instanceId)` | una instancia |
| `flowNodeInstances(instanceId)` | su evidencia, en orden |
| `flowNodeInstances(processDefinitionRef)` | la evidencia de todas las instancias de una versión, como `Map` por instancia y en orden de secuencia, con una sola consulta; las instancias sin evidencia no aparecen |
| `isWaitingAt(instanceId, flowNodeId)` | si la instancia está activa y la conformidad permitiría registrar ahora ese nodo |

Para agregados ya calculados (mapa de calor, camino de una instancia, recuentos por versión) usa
`ProcessExecutionStatistics`; véase [Estadísticas y módulo web](estadisticas-web.md).

`ProcessInstanceSummary` es una interfaz sellada con dos casos:

- `Active`: aún no ha llegado a un evento de fin. Ofrece `waitingFlowNodeId()` y `nextFlowNodeIds()` (vacío si su
  versión ya no está desplegada).
- `Completed`: con fecha de fin y, si terminó en un evento de fin de mensaje, `publishedMessage()` (los mensajes de sus
  tareas de envío no cuentan: están en su evidencia).

Ambos ofrecen `triggeringMessage()`, el mensaje que inició la instancia si empezó por uno.

`HistoricFlowNodeInstance` lleva, para cada paso, el proceso y su versión, el nodo y su tipo BPMN, el `loopCounter`,
el momento, el resultado, la correlación y la clave de negocio.

## Dónde se guarda

En memoria si la aplicación no tiene base de datos, o en las tablas `pac_process_instance` y `pac_flow_node_instance`
si la tiene, con JPA o con JDBC. Se elige solo y se puede cambiar o sustituir por un almacén propio; véase [Almacenamiento](almacenamiento.md).
