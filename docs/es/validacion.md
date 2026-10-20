# Validación del modelo

> 🇬🇧 [English version](../en/validation.md)

`bpmncode` no deja que un modelo incoherente llegue a ejecutarse. Lo comprueba en dos momentos:

| Momento | Quién | Qué mira |
|---|---|---|
| Compilación | `ProcessAnnotationProcessor` (en `javac`) | solo el elemento anotado y los tipos que referencia |
| Arranque | `AnnotationProcessModelReader` (al crear `ProcessDefinitionRegistry`) | el modelo completo de la aplicación |

Las comprobaciones de compilación son un subconjunto de las de arranque, así que nada se escapa aunque no se use el
procesador; solo se descubre más tarde.

## Al compilar

El procesador hace fallar `javac` y el IDE subraya el error en su sitio. Se activa como se explica en
[Primeros pasos](primeros-pasos.md#2-procesador-de-anotaciones).

| Elemento | Qué exige |
|---|---|
| `@TimerStartEvent` | exactamente un `@Scheduled` (directo, en `@Schedules` o dentro de una anotación propia), no desactivado (`cron = "-"`) y con un solo disparador |
| `@MessageStartEvent` | ser listener de Spring (`@EventListener` o una anotación que lo lleve, como `@ApplicationModuleListener`) que recibe un `@Message` o una de sus versiones |
| `@MessageEndEvent.messageRef` | una versión concreta (record), no la familia (`an end event publishes one concrete version: use one of the @MessageVersion records of … as messageRef`) |
| `@SendTask.messageRef` | declarado (`a send task sends a message: declare its messageRef`) y una versión concreta (record), no la familia (`a send task sends one concrete version: use one of the @MessageVersion records of … as messageRef`) |
| Anotaciones de nodo | clase con `@Process` o `@ProcessPart` (no las dos) y un solo nodo por método |
| Eventos de inicio y tareas | un único flujo de salida, con `targetRef` y sin `when` ni `conditionExpression` |
| `@StandardLoop` | como mucho uno, con `loopCondition` y `loopMaximum` ≥ 0 |
| `@ExclusiveGateway` | devolver un `enum` con al menos dos ramas, que son exactamente sus constantes |
| `@BusinessRuleTask` | al menos una regla |
| `@Message` / `@MessageVersion` | familia sellada de records `ProcessMessage`, versiones ≥ 1 y sin repetir |
| `@ProcessError` | ir en una excepción (`@ProcessError goes on an exception (a subclass of Throwable)`) con `errorCode` (`@ProcessError does not declare its errorCode`) |
| `@ErrorEndEvent.errorRef` | una excepción con `@ProcessError` (`errorRef … is not an exception annotated with @ProcessError`) |
| `@PotentialOwner` / `@HumanPerformer` | exactamente uno de `resourceRef` o `expression` (`a potential owner declares exactly one of resourceRef or expression`, `a human performer declares exactly one of resourceRef or expression`); `resourceRef`, un tipo con `@ProcessResource` (`resourceRef … is not a type annotated with @ProcessResource`) |
| Identidad | ids NCName, nombres no vacíos (también en `@ProcessResource`), versiones de `@Process` y `@BusinessRule` desde 1 |

El procesador está pensado para funcionar sin cargar nada dentro de `javac`:

- reconoce las anotaciones por su nombre, sin cargar Spring ni el resto de la librería;
- no depende de en qué jar estén los tipos referenciados.

Lo que depende de toda la aplicación (destinos en otra clase, reglas, quién publica y quién consume cada mensaje,
alcanzabilidad, que haya un evento de fin, ids únicos, fichero de contratos, `${…}`) se comprueba al arrancar.

## Al arrancar

El lector recorre los paquetes de la aplicación, construye el modelo y reúne **todas** las violaciones antes de
fallar. Si hay alguna, lanza `ProcessModelException` y la aplicación no arranca:

```
io.github.eldiegos.bpmncode.model.ProcessModelException: Invalid process model (2 problem(s)):
  - UNKNOWN_TARGET_REF …
  - GATEWAY_OUTGOING_MISMATCH …
```

Cada violación es un `ModelViolation` con su `ViolationKind`, el lugar (clase, método o fichero) y un mensaje en
inglés, como los logs. `ProcessModelException.violations()` las devuelve para quien quiera tratarlas.

### Tipos de error

| `ViolationKind` | Significado |
|---|---|
| `DUPLICATE_ID` | un id de nodo, flujo, proceso y versión, regla y versión, mensaje, error o recurso se repite (las versiones de un mismo proceso sí pueden compartir ids de nodo) |
| `INVALID_ID` | un id no es NCName |
| `UNIDENTIFIED_ELEMENT` | un elemento sin id o sin nombre |
| `AMBIGUOUS_REFERENCE` | una referencia admite varias interpretaciones, p. ej. `R-PER-001` cuando la regla tiene varias versiones, `@ProcessPart` sin versión de un proceso con varias, un proceso con varios eventos de inicio, o un método con varias anotaciones de nodo |
| `MISSING_PROCESS_ANNOTATION` | un método con anotación de nodo en una clase sin `@Process` ni `@ProcessPart` |
| `UNKNOWN_PROCESS_REF` | `@ProcessPart` apunta a un proceso o versión que no existe |
| `UNKNOWN_TARGET_REF` | un `@SequenceFlow` apunta a un nodo que no existe en el proceso |
| `UNKNOWN_BUSINESS_RULE_REF` | `businessRuleRefs` nombra una regla o versión que no existe |
| `UNKNOWN_MESSAGE_REF` | `messageRef` apunta a un tipo que no es un `@Message` ni una de sus versiones |
| `UNKNOWN_ERROR_REF` | `errorRef` apunta a una clase que no es una excepción anotada con `@ProcessError` (`errorRef … is not an exception annotated with @ProcessError`) |
| `UNKNOWN_RESOURCE_REF` | el `resourceRef` de un `@PotentialOwner` o `@HumanPerformer` apunta a un tipo sin `@ProcessResource` (`resourceRef … is not a type annotated with @ProcessResource`) |
| `INVALID_ERROR` | `@ProcessError` en una clase que no es una excepción, o sin `errorCode` |
| `MISSING_START_EVENT` | un proceso sin evento de inicio |
| `MISSING_END_EVENT` | un proceso sin evento de fin de ningún tipo (`declares no end event (@EndEvent, @MessageEndEvent or @ErrorEndEvent)`) |
| `UNREACHABLE_FLOW_NODE` | un nodo al que no se llega desde ningún evento de inicio |
| `NO_PATH_TO_END_EVENT` | un nodo desde el que no se llega a ningún evento de fin (un ciclo sin salida) |
| `INCOMPATIBLE_METADATA` | metadatos que no encajan con el elemento: versiones menores que 1, `@MessageStartEvent` que no es listener, `@TimerStartEvent` sin `@Scheduled`, bucles mal declarados, un `@PotentialOwner` o `@HumanPerformer` que no declara exactamente uno de `resourceRef` o `expression`… |
| `GATEWAY_OUTGOING_MISMATCH` | los `when` de un gateway no son exactamente las constantes del enum que devuelve |
| `INVALID_MESSAGE_VERSION` | versiones de un mensaje mal declaradas (no sellada, no record, sin `ProcessMessage`, versión repetida…) o un `@MessageEndEvent` o una `@SendTask` que publica la familia en lugar de una versión concreta |
| `MESSAGE_VERSION_NOT_ACCEPTED` | se publica una versión de un mensaje que ningún consumidor acepta |
| `MESSAGE_CONTRACT_CHANGED` | el esquema de una versión publicada ya no coincide con su huella en el fichero de contratos |
| `MESSAGE_CONTRACT_NOT_LOCKED` | hay una versión que aún no está en el fichero de contratos (el mensaje incluye la línea que hay que añadir) |
| `MESSAGE_CONTRACT_REMOVED` | el fichero de contratos tiene una versión que ya no existe en el código |

Los tres últimos se explican en [Mensajes entre procesos](mensajes.md#2-esquema-congelado).

## Probar el modelo

Como el modelo se valida al crear el contexto, cualquier `@SpringBootTest` de la aplicación falla si el modelo no es
válido. Para probar solo el modelo, sin contexto de Spring, lee los paquetes directamente:

```java
@Test
void theProcessModelIsValid() {
	var definitions = AnnotationProcessModelReader.scan("com.example.orders");   // lanza ProcessModelException si no es válido
	assertThat(definitions.process("ORDER")).isPresent();
}
```

`AnnotationProcessModelReader` está en el paquete `model.reader`, que es interno: su firma puede cambiar entre
versiones `0.x`.

Para comprobar además que los diagramas exportados son BPMN 2.0 válido según los XSD de OMG y que cada elemento
dibujado tiene su elemento del modelo, usa `ProcessAssertions`, del jar de pruebas de la librería (véase
[Configuración](configuracion.md#processassertions)).
