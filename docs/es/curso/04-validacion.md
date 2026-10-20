# Lección 4. Validación al compilar y al arrancar

> 🇬🇧 [English version](../../en/course/04-validation.md)

**Objetivo:** provocar a propósito los errores típicos, reconocer quién los detecta y leer sus mensajes para
arreglarlos rápido.

## Lo que vas a aprender

- Qué comprueba el procesador de anotaciones al compilar y por qué no puede comprobarlo todo.
- Qué comprueba el arranque y cómo se lee una `ProcessModelException`.
- Por qué un error puede arrastrar otros y cuál hay que arreglar primero.
- Cómo una prueba sin Spring detecta un modelo roto antes de desplegar.

## Punto de partida

El código de la [lección 3](03-decisiones-con-gateways.md): `ExpenseClaimProcess` con 8 nodos, las reglas
`ExpensePolicyRule` y `ApprovalThresholdRule`, y la prueba `ProcessModelTests`. En esta lección **no añades código
nuevo**: rompes el existente, observas y lo dejas como estaba.

## Pasos

### 1. Dos momentos, dos alcances

`bpmncode` no deja que un modelo incoherente llegue a ejecutarse. Lo comprueba dos veces:

| Momento | Quién | Ve… | Ejemplos |
|---|---|---|---|
| Compilación | `ProcessAnnotationProcessor`, dentro de `javac` y del IDE | una anotación y los tipos que nombra | id inválido, nombre vacío, gateway sin `enum`, ramas que no cuadran con el `enum`, tarea con dos salidas |
| Arranque | el lector del modelo, al crear el bean `ProcessDefinitionRegistry` | toda la aplicación | `targetRef` que no existe, regla que no existe, nodos inalcanzables, ciclos sin salida, ids repetidos |

El procesador solo mira el elemento anotado, así que no sabe si el nodo al que apunta un `targetRef` existe en otra
clase. Esas comprobaciones globales se hacen al arrancar. Las de compilación son un subconjunto de las de arranque:
aunque olvides configurar el procesador, nada se escapa; solo te enteras más tarde.

### 2. Un error de compilación

Borra la rama `AUTO_APPROVED` de `EXP-ROUTE` (la última `@SequenceFlow` de su `outgoing`) y compila:

```bash
./mvnw compile
```

```
[ERROR] /…/com/example/expenses/claim/ExpenseClaimProcess.java:[51,9] constants of Route without an outgoing sequence flow: [AUTO_APPROVED]
```

El error apunta a la anotación del gateway, y tu IDE la subraya en rojo. Vuelve a poner la rama.

### 3. Un error de arranque

Ahora equivócate en un `targetRef` de otra forma que el procesador no puede ver. En `EXP-PAY`, escribe
`targetRef = "EXP-PAYMENT-ORDER"` (falta la `D`). Compila sin problemas. Arranca:

```bash
./mvnw spring-boot:run
```

La aplicación no arranca. Entre las causas del fallo verás la excepción de la librería:

```
io.github.eldiegos.bpmncode.model.ProcessModelException: Invalid process model (3 problem(s)):
  - [UNKNOWN_TARGET_REF] com.example.expenses.claim.ExpenseClaimProcess#pay(ExpenseClaim): the sequence flow targets the unknown flow node 'EXP-PAYMENT-ORDER'
  - [UNREACHABLE_FLOW_NODE] Process EXP-CLAIM v1: flow node EXP-PAYMENT-ORDERED is not reachable from the start event
  - [NO_PATH_TO_END_EVENT] Process EXP-CLAIM v1: no end event can be reached from flow node EXP-PAY: a cycle without exit
```

Cada línea es un `ModelViolation`: `[tipo] lugar: mensaje`. El lugar es la clase y el método (o el proceso y su
versión), así que sabes dónde mirar sin depurar.

### 4. Arregla la causa, no las consecuencias

Hay tres problemas, pero un solo error. El flujo de `EXP-PAY` apunta a un nodo que no existe, así que el lector lo
descarta; sin ese flujo, a `EXP-PAYMENT-ORDERED` no llega nadie y desde `EXP-PAY` no se llega a ningún fin. Las dos
últimas líneas son consecuencia de la primera.

Regla práctica: empieza por los errores de referencia (`UNKNOWN_…`, `AMBIGUOUS_REFERENCE`, `DUPLICATE_ID`) y vuelve a
arrancar; los de grafo (`UNREACHABLE_FLOW_NODE`, `NO_PATH_TO_END_EVENT`) suelen desaparecer solos.

Un caso más llamativo: si escribes mal una regla en un nodo (`businessRuleRefs = "R-EXP-01"` en `EXP-CHECK`), el lector
descarta ese nodo entero y salen muchas líneas: `UNKNOWN_BUSINESS_RULE_REF` en `EXP-CHECK`, `UNKNOWN_TARGET_REF` en
`submit` (que apuntaba a `EXP-CHECK`) y un `UNREACHABLE_FLOW_NODE` por cada nodo posterior. La causa es la primera:

```
[UNKNOWN_BUSINESS_RULE_REF] com.example.expenses.claim.ExpenseClaimProcess#check(ExpenseClaim): references the unknown rule 'R-EXP-01' (there is no @BusinessRule with that id)
```

Deshaz el cambio (`targetRef = PAYMENT_ORDERED`).

### 5. Que lo detecte una prueba

La prueba `ProcessModelTests` de la lección 3 llama a `AnnotationProcessModelReader.scan`, que lanza la misma
`ProcessModelException`. Repite el error del paso 3 y ejecuta `./mvnw test`: la prueba falla con el mismo mensaje, en
segundos y sin levantar Spring. Además, cualquier `@SpringBootTest` de la aplicación falla también, porque el modelo se
valida al crear el contexto. Con eso, un modelo roto no pasa de tu integración continua.

## Comprueba que funciona

Con el código otra vez como en la lección 3:

- `./mvnw compile` termina sin errores.
- `./mvnw test` pasa `ProcessModelTests`.
- Al arrancar ves `Process definition EXP-CLAIM v1 'Expense claim': 8 flow nodes, 9 sequence flows`.

## Errores frecuentes

- **Los errores de compilación no salen; solo fallan al arrancar.** El procesador no se está ejecutando. Revisa que
  esté en `annotationProcessors` del `maven-compiler-plugin` (lección 1); desde JDK 23 es obligatorio. Si tu IDE no
  los marca, activa el procesamiento de anotaciones en su configuración de compilación o reimporta el proyecto Maven.
- **Al añadir el procesador dejan de funcionar Lombok u otros.** Al listar uno en `annotationProcessors`, `javac` solo
  ejecuta los listados: añade también los demás.
- **Un método con dos anotaciones de nodo** (p. ej. `@ServiceTask` y `@UserTask`): no compila (`the method declares
  several flow nodes: …`).
- **Un método anotado en una clase sin `@Process` ni `@ProcessPart`**: no compila (`class … declares neither @Process
  nor @ProcessPart`).

## Ejercicio

Añade una tarea `EXP-ARCHIVE` («Archive claim») que vaya a `EXP-REJECTED`, pero sin que ningún flujo llegue a ella.
Antes de arrancar, predice qué tipo de error saldrá. Después arréglalo borrando la tarea.

<details><summary>Solución</summary>

```java
@ServiceTask(id = "EXP-ARCHIVE", name = "Archive claim", outgoing = @SequenceFlow(targetRef = REJECTED))
void archive() {
}
```

Compila: la anotación es correcta por sí sola. Al arrancar (o en `./mvnw test`):

```
  - [UNREACHABLE_FLOW_NODE] Process EXP-CLAIM v1: flow node EXP-ARCHIVE is not reachable from the start event
```

Todo nodo tiene que poder alcanzarse desde el evento de inicio y poder llegar a un evento de fin. Borra el método para
volver al modelo de la lección 3.

</details>

## Para saber más

- [Validación del modelo](../validacion.md): [al compilar](../validacion.md#al-compilar),
  [al arrancar](../validacion.md#al-arrancar) y [todos los tipos de error](../validacion.md#tipos-de-error).
- [Primeros pasos: procesador de anotaciones](../primeros-pasos.md#2-procesador-de-anotaciones).

---

[← Anterior: Decisiones con gateways](03-decisiones-con-gateways.md) | [Índice](README.md) | [Siguiente: Ejecutar con el journal →](05-ejecutar-con-el-journal.md)
