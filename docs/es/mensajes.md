# Mensajes entre procesos

> 🇬🇧 [English version](../en/messages.md)

Un proceso puede iniciar otro publicando un mensaje: al terminar, en un `@MessageEndEvent(messageRef = …)`, o a mitad
del proceso, en una `@SendTask(messageRef = …)` tras la que sigue sin esperar respuesta; el otro empieza en un
`@MessageStartEvent` que lo recibe. El mensaje es un **contrato** entre dos procesos que pueden evolucionar por
separado, así que `bpmncode` lo protege en tres capas.

`bpmncode` no transporta los mensajes: se publican con el `ApplicationEventPublisher` de Spring y se reciben con un
listener. Para entrega fiable y asíncrona, Spring Modulith (`@ApplicationModuleListener` y su registro de
publicaciones) encaja de forma natural.

## 1. Versiones como tipos sellados

`@Message` se pone en una `sealed interface` (la familia) y cada versión es un record con `@MessageVersion` que
implementa `ProcessMessage`, es decir, lleva una cabecera `MessageHeader header` como componente:

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

Si un mensaje tiene una sola versión, `@Message` puede ir directamente en el record.

- **Quien publica elige una versión concreta**: `@MessageEndEvent(messageRef = PersonRegisteredV2.class)` o
  `@SendTask(messageRef = PersonRegisteredV2.class)`.
- **Quien consume declara qué acepta**: la familia (`messageRef = PersonRegistered.class`, todas las versiones) o una
  versión. Si se publica una versión que ningún consumidor acepta, la aplicación no arranca
  (`MESSAGE_VERSION_NOT_ACCEPTED`). Aceptar versiones que ya nadie publica sí está permitido: puede haber mensajes en
  vuelo.
- **El consumidor trata las versiones con un `switch` sin `default`**. Al añadir una versión a la familia sellada, el
  consumidor deja de compilar hasta que decide qué hacer con ella.

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

## 2. Esquema congelado

Al arrancar, `bpmncode` genera de cada versión un **JSON Schema 2020-12** determinista y calcula su huella SHA-256:

- los componentes del record, en su orden de declaración, sin la cabecera (que es común a todos);
- `Optional` no es obligatorio; UUID, `java.time`, enums, records anidados, listas y mapas, con su formato;
- dos esquemas iguales tienen la misma huella, y cualquier cambio de campo, tipo u obligatoriedad la cambia.

Las huellas se guardan en `src/main/resources/pac/message-contracts.lock`, que se revisa en el control de versiones
como cualquier otro contrato:

```
# Contratos congelados de los mensajes entre procesos.
EV-PERSON-REGISTERED@1 = sha256:458641d6132d26886dace3de7990529bb26052dd37854b76bd5520d78f85cfe6
EV-PERSON-REGISTERED@2 = sha256:1427690b…
```

| Situación | Resultado al arrancar |
|---|---|
| Se cambia un campo de una versión ya publicada | `MESSAGE_CONTRACT_CHANGED`, con la huella congelada y la actual: hay que crear una versión nueva |
| Se añade una versión | `MESSAGE_CONTRACT_NOT_LOCKED`, con la línea exacta que hay que añadir al fichero |
| Se borra una versión que sigue en el fichero | `MESSAGE_CONTRACT_REMOVED`: puede haber mensajes en vuelo |
| No hay fichero | no se comprueba nada (útil al empezar) |

Se leen y combinan todos los `pac/message-contracts.lock` del classpath, así que cada jar puede traer los de sus
mensajes. `Definitions` expone cada esquema (`MessageVersionDefinition.schema()`), por si la aplicación quiere
mostrarlos o publicarlos.

## 3. Cabecera del mensaje

Cada versión lleva una `MessageHeader`:

| Componente | Significado |
|---|---|
| `messageId` | identificador único del envío (`MessageId`) |
| `correlationKey` | correlación de la instancia que publica; la heredan las instancias que inicie |
| `occurredAt` | momento de la publicación |
| `source` | proceso y versión que lo publica |
| `sourceInstanceId` | instancia que lo publica |

No se construye a mano: la prepara el journal a partir de la instancia que publica.

```java
// Publicar: evento de fin de mensaje
var registered = new PersonRegisteredV1(journal.messageHeader(instance), person.id(), person.createdAt());
journal.reachEndEvent(instance, "REG-COMPLETED", registered);   // evidencia MESSAGE_PUBLISHED EV-PERSON-REGISTERED@1
events.publishEvent(registered);                                 // después de registrarlo

// Publicar: tarea de envío; la instancia sigue en el nodo siguiente
var requested = new InvoiceRequested(journal.messageHeader(instance), subscription.id());
journal.completeActivity(instance, "REQUEST-INVOICE", requested);   // evidencia MESSAGE_PUBLISHED EV-INVOICE-REQUESTED@1 <messageId>
events.publishEvent(requested);

// Recibir: evento de inicio de mensaje; la correlación sale de la cabecera
journal.startProcessInstance(KEY, message);   // evidencia MESSAGE_RECEIVED EV-PERSON-REGISTERED@1

// Recibir de forma idempotente: una entrega repetida devuelve ProcessStart.AlreadyStarted
journal.startProcessInstanceIfAbsent(KEY, message,
		FlowNodeOutcome.messageReceived(new MessageVersionRef("EV-PERSON-REGISTERED", 1)));
```

Registra **antes** de publicar: si la conformidad rechaza el paso (por ejemplo, una petición repetida sobre una instancia
ya completada), `reachEndEvent` o `completeActivity` lanzan la excepción y el mensaje no llega a salir. En el orden
inverso, los listeners ya lo habrían recibido.

El journal comprueba los dos extremos:

- `reachEndEvent` exige que el mensaje sea la versión que declara el `@MessageEndEvent` y que su cabecera sea de esa
  instancia. Un evento de fin de mensaje no se puede alcanzar sin publicar el mensaje.
- `completeActivity(instance, sendTaskId, message)` exige lo mismo para una tarea de envío y devuelve su iteración
  (`loopCounter`). Una tarea de envío no se puede completar con un resultado
  (`… sends EV-INVOICE-REQUESTED@1: complete it with completeActivity(instance, sendTaskId, message)`), ni otra tarea
  con un mensaje (`… sends no message: complete it with completeActivity(instance, activityId, outcome)`).
- `startProcessInstance` exige que el evento de inicio acepte ese mensaje y esa versión. Un proceso que empieza por
  mensaje no se puede iniciar sin él.
- Cada instancia guarda qué envío la inició y cuál publicó al terminar (`ProcessInstanceSummary.triggeringMessage()` y
  `Completed.publishedMessage()`). Lo que envían sus tareas de envío queda en la evidencia de cada tarea
  (`MESSAGE_PUBLISHED` con la versión y el `messageId`).

## Cambiar un contrato

1. Añade a la familia un record con `@MessageVersion(n + 1)`.
2. Publícalo desde la nueva versión del proceso que lo emite (`@MessageEndEvent(messageRef = …V(n+1).class)` o
   `@SendTask(messageRef = …V(n+1).class)`).
3. Trátalo en el `switch` de cada consumidor; el compilador te dirá dónde.
4. Arranca la aplicación y añade al fichero de contratos la línea que indica `MESSAGE_CONTRACT_NOT_LOCKED`.

Las versiones existentes no se tocan nunca.

## Catálogo de contratos

`MessageContractCatalog` (paquete `model`) es un bean que reúne, para una pantalla de gobierno o una herramienta, lo
que hay que saber de cada contrato sin recorrer el modelo. Se construye con el modelo y con
`pac/message-contracts.lock` del classpath.

| Método o record | Contenido |
|---|---|
| `contracts()`, `contract(messageId)` | un `Contract` por mensaje: el `Message`, sus versiones y sus consumidores |
| `lockPresent()` | si la aplicación tiene fichero de contratos |
| `Version` | `ref` (`ID@versión`), `definition` (record y esquema), `fields`, `lockStatus`, `lockLine` (la línea exacta que la congela) y `publishers` |
| `Field` | un componente del record: `name`, `type` (nombre simple; el envuelto si es `Optional`), `optional`, `header` (la `MessageHeader` común) y `enumConstants` |
| `LockStatus` | `FROZEN` (congelada con su huella actual), `NOT_FROZEN` (el fichero no la congela con esa huella) o `NO_LOCK_FILE` |
| `Publisher` | versión de proceso (`process`) y nodo que publica la versión (`flowNode`): un `EndEvent` de mensaje o una `SendTask` |
| `Consumer` | versión de proceso, `StartEvent` que acepta el mensaje y versiones aceptadas |

Todo va en el orden del modelo y sin textos traducidos. Con fichero de contratos la aplicación no arranca si una
versión no está congelada, así que `NOT_FROZEN` solo aparece si el catálogo se construye fuera del arranque (p. ej. en
una prueba).

## Diagramas

Los flujos de mensaje aparecen en el BPMN: en el diagrama de cada proceso, el proceso con el que se comunica es una
piscina colapsada, y el diagrama de colaboración (`COLLABORATION`) dibuja juntos todos los procesos comunicados. La
versión publicada se exporta como `pac:version` y las versiones aceptadas, como `pac:acceptedVersions`. El origen de
un flujo de mensaje es el evento de fin o la tarea de envío que publica; su id es `MessageFlow_<origen>_<destino>`.

## Persistencia del mensaje

Si usas el registro de publicaciones JPA de Spring Modulith, este guarda el mensaje serializado en JSON. Con cabecera y
carga no suele caber en los 255 caracteres que Hibernate da por defecto a esa columna: amplíala (p. ej. con un
`META-INF/orm.xml` en la aplicación) o usa un tipo de texto largo en tu script de esquema.
