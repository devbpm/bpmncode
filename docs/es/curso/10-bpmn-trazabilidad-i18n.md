# Lección 10. BPMN, trazabilidad e i18n

> 🇬🇧 [English version](../../en/course/10-bpmn-traceability-i18n.md)

**Objetivo:** sacar partido de que el modelo sale del código: listar los diagramas, seguir cada paso hasta su método y
su regla, y mostrar el diagrama en español.

## Lo que vas a aprender

- Qué diagramas genera la librería y qué lleva el XML exportado, incluidas las extensiones `pac:`.
- Cómo recorrer la cadena Proceso → Nodo → Regla → Código → Mensaje con `TraceabilityReport`.
- Cómo saber dónde se aplica una versión de una regla con `Definitions.usagesOf`.
- Cómo traducir los textos del modelo con `MessageSourceLabelResolver` y claves derivadas de los ids.

## Punto de partida

El código de la [lección 9](09-versiones.md): `EXP-CLAIM` v1 y v2, `EXP-PAYMENT` v1, la regla `R-EXP-002` en dos
versiones y los datos en H2 con JPA. `DiagramController` sigue como en la lección 1, con `LabelResolver.CANONICAL`.

## Pasos

### 1. Qué diagramas hay

`BpmnDiagramService.diagrams()` devuelve un diagrama por proceso (con su versión vigente), uno por cada versión
anterior que sigue desplegada y, como hay mensajes entre procesos, el de colaboración. Añade un listado a
`DiagramController`:

```java
	@GetMapping("/bpmn")
	List<String> ids() {
		return diagrams.diagrams().stream().map(BpmnDiagram::id).toList();
	}
```

Con los imports `java.util.List` y `io.github.eldiegos.bpmncode.bpmn.BpmnDiagram`. `GET /bpmn` devuelve:

```json
["EXP-CLAIM","EXP-CLAIM-v1","EXP-PAYMENT","COLLABORATION"]
```

| Id | Tipo (`BpmnDiagram`) | Qué dibuja |
|---|---|---|
| `EXP-CLAIM` | `ProcessDiagram` | la versión vigente (v2) y, como piscina colapsada, el proceso con el que se comunica |
| `EXP-CLAIM-v1` | `ProcessVersionDiagram` | la v1, que sigue desplegada |
| `EXP-PAYMENT` | `ProcessDiagram` | el pago |
| `COLLABORATION` | `CollaborationDiagram` | los dos procesos y el flujo de mensaje entre ellos |

### 2. Lee el XML

Abre `/bpmn/EXP-CLAIM`. Además del modelo semántico (`bpmn:process`, nodos, flujos) y del dibujo (`bpmndi`), verás lo
que BPMN no sabe expresar, en extensiones con el prefijo `pac:`:

| Extensión | Dónde | Ejemplo del curso |
|---|---|---|
| `pac:version` | proceso, participante, flujo de mensaje | `pac:version="2"` |
| `pac:definedBy`, `pac:implementedBy` | proceso | `ExpenseClaimProcessV2` |
| `pac:implementation` | cada nodo | `method="route(ProcessInstanceRef, ExpenseClaim, boolean)"` |
| `pac:businessRuleRef` | tareas y gateways | `id="R-EXP-002" … version="2"` |
| `pac:when` | flujo de un gateway | `pac:when="NEEDS_APPROVAL"` |
| `pac:messageVersion` | mensaje | `version="1"` y `schemaHash="sha256:0077…"` |

La piscina del pago aparece colapsada en este diagrama, y su documentación empieza por
`Process EXP-PAYMENT v1 (outside this diagram).` Ese texto también se traduce (clave `pac.bpmn.doc.externalProcess`,
que la librería trae en inglés y en español; paso 4).

Si añadiste los roles del paso 7 de la [lección 6](06-tareas-que-esperan.md), `EXP-APPROVE` lleva un
`bpmn:potentialOwner` (`EXP-APPROVE_PotentialOwner_1`) con `bpmn:resourceRef` `Resource_RES-EXPENSE-APPROVER` y un
`bpmn:humanPerformer` con la expresión en `bpmn:formalExpression`, y el documento declara el `bpmn:resource` con su
`bpmn:resourceParameter` `department` y `pac:definedBy`. Su nombre se traduce con `pac.resource.RES-EXPENSE-APPROVER.name`.

### 3. La trazabilidad

`TraceabilityReport` es un bean con una entrada (`TraceabilityEntry`) por cada nodo de cada versión de cada proceso:
proceso y versión, nodo y tipo, clase y método, reglas aplicadas, mensaje publicado o recibido y, en un evento de fin
de error, el error con el que termina (`thrownError`). Para el lado contrario, `registry.definitions().usagesOf(regla)` dice qué nodos de qué versiones aplican una versión de una regla.

`src/main/java/com/example/expenses/TraceabilityController.java`:

```java
package com.example.expenses;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.github.eldiegos.bpmncode.model.BusinessRuleRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.trace.TraceabilityReport;

@RestController
class TraceabilityController {

	private final TraceabilityReport traceability;
	private final ProcessDefinitionRegistry registry;

	TraceabilityController(TraceabilityReport traceability, ProcessDefinitionRegistry registry) {
		this.traceability = traceability;
		this.registry = registry;
	}

	@GetMapping("/traceability")
	List<String> entries() {
		return traceability.entries().stream()
				.map(entry -> entry.processId() + " v" + entry.processVersion() + " | " + entry.flowNodeId() + " | "
						+ entry.javaClass() + "#" + entry.javaMethod() + " | "
						+ entry.businessRules().stream().map(rule -> rule.ref().reference()).toList())
				.toList();
	}

	@GetMapping("/traceability/rules/{id}/{version}")
	List<String> usages(@PathVariable String id, @PathVariable int version) {
		return registry.definitions().usagesOf(BusinessRuleRef.of(id, version)).stream()
				.map(usage -> usage.process() + " " + usage.flowNode().id())
				.toList();
	}
}
```

La evidencia completa la cadena: cada `HistoricFlowNodeInstance` lleva `processId`, `processVersion` y `flowNodeId`,
así que de cualquier paso de cualquier solicitud llegas a su entrada de trazabilidad, es decir, al método y a la
versión de la regla que se le aplicó.

### 4. Traduce los textos

Los `name` de las anotaciones son los textos **canónicos**. Para traducirlos no se toca el código: `LabelResolver`
busca claves derivadas de los ids, con el prefijo `pac.`:

| Clave | Texto |
|---|---|
| `pac.process.<id>.name` | nombre del proceso |
| `pac.flowNode.<id>.name` | nombre de un nodo |
| `pac.sequenceFlow.<id>.name` | etiqueta de un flujo (su id por defecto es `Flow_<origen>_<destino>`) |
| `pac.businessRule.<id>.name`, `pac.message.<id>.name` | nombre de una regla, de un mensaje |

Si una clave no tiene traducción, se usa el texto canónico: nunca verás una clave en lugar de un texto.

Crea los ficheros de mensajes de la aplicación:

`src/main/resources/messages.properties` (vacío: el inglés canónico ya está en el código):

```properties
```

`src/main/resources/messages_es.properties`:

```properties
pac.process.EXP-CLAIM.name=Solicitud de gastos
pac.flowNode.EXP-ROUTE.name=¿Necesita aprobación?
pac.sequenceFlow.Flow_EXP-DECISION_EXP-PAY.name=Sí
```

Y añade a `application.properties` el bundle de la librería, que trae los nombres de los tipos de elemento BPMN en
inglés y en español (`pac.bpmn.userTask`…). Tus claves mandan si repites alguna:

```properties
spring.messages.basename=messages,io/github/eldiegos/bpmncode/i18n/messages
spring.messages.fallback-to-system-locale=false
```

La segunda línea importa. Por defecto, si pides un idioma sin fichero propio (`en`: no hay `messages_en.properties`),
Spring busca antes el del idioma del sistema. En un equipo configurado en español, el diagrama «en inglés» saldría en
español. Con `false`, un idioma sin fichero usa `messages.properties` y, en su defecto, los textos canónicos.

### 5. El diagrama en el idioma pedido

`MessageSourceLabelResolver` traduce con el `MessageSource` de Spring para un `Locale`. No es un bean: créalo donde lo
necesites. `DiagramController` completo:

`src/main/java/com/example/expenses/DiagramController.java`:

```java
package com.example.expenses;

import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.eldiegos.bpmncode.bpmn.BpmnDiagram;
import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;
import io.github.eldiegos.bpmncode.i18n.MessageSourceLabelResolver;

@RestController
class DiagramController {

	private final BpmnDiagramService diagrams;
	private final MessageSource messages;

	DiagramController(BpmnDiagramService diagrams, MessageSource messages) {
		this.diagrams = diagrams;
		this.messages = messages;
	}

	@GetMapping("/bpmn")
	List<String> ids() {
		return diagrams.diagrams().stream().map(BpmnDiagram::id).toList();
	}

	@GetMapping(value = "/bpmn/{id}", produces = MediaType.APPLICATION_XML_VALUE)
	String bpmn(@PathVariable String id, @RequestParam(defaultValue = "en") String lang) {
		var diagram = diagrams.diagram(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		var locale = Locale.forLanguageTag(lang);
		return diagrams.xml(diagram, locale.toLanguageTag(), new MessageSourceLabelResolver(messages, locale));
	}
}
```

`xml` guarda en caché un documento por diagrama e idioma, así que el segundo argumento tiene que identificar el idioma
del `LabelResolver`: si pasaras siempre `"en"`, la primera traducción que se pidiera serviría para todas.

## Comprueba que funciona

Arranca y pide la traza y los usos de cada versión de la regla del umbral:

```bash
curl -s localhost:8080/traceability
curl -s localhost:8080/traceability/rules/R-EXP-002/1
curl -s localhost:8080/traceability/rules/R-EXP-002/2
```

```
["EXP-CLAIM v1 | EXP-SUBMITTED | com.example.expenses.claim.ExpenseClaimProcess#submit(ExpenseClaim) | []",
 "EXP-CLAIM v1 | EXP-CHECK | com.example.expenses.claim.ExpenseClaimProcess#check(ProcessInstanceRef, ExpenseClaim) | [R-EXP-001@1]",
 "EXP-CLAIM v1 | EXP-ROUTE | com.example.expenses.claim.ExpenseClaimProcess#route(ProcessInstanceRef, ExpenseClaim, boolean) | [R-EXP-002@1]",
 …]
["EXP-CLAIM v1 EXP-ROUTE"]
["EXP-CLAIM v2 EXP-ROUTE"]
```

Y el diagrama en los dos idiomas:

```bash
curl -s 'localhost:8080/bpmn/EXP-CLAIM?lang=es' | grep -o 'name="[^"]*"' | sort -u
```

Entre los nombres aparecen `Solicitud de gastos`, `¿Necesita aprobación?` y `Sí`; los que no tradujiste siguen en
inglés (`Approve claim`, `No`…). Con `?lang=en`, o sin parámetro, todo sale en inglés.

## Errores frecuentes

- **Las tildes salen mal.** Spring Boot lee los `messages*.properties` en UTF-8 por defecto
  (`spring.messages.encoding`); guarda el fichero en UTF-8.
- **Una traducción no aparece.** Revisa la clave: los ids distinguen mayúsculas y el de un flujo es
  `Flow_<origen>_<destino>` salvo que le dieras otro `id`. Una clave mal escrita no da error: se muestra el texto
  canónico.
- **El diagrama en inglés sale en español.** Falta `spring.messages.fallback-to-system-locale=false` y el sistema
  está en español (paso 4).
- **El idioma «se pega».** Pasaste a `xml` un idioma que no corresponde al `LabelResolver` (paso 5).
- **Quieres otro prefijo que `pac.`.** Usa `new MessageSourceLabelResolver(messages, locale, "myapp.")`.

## Ejercicio

Traduce al español el proceso de pago: su nombre y sus tres nodos. Comprueba el resultado en `/bpmn/EXP-PAYMENT?lang=es`
y en la piscina colapsada de `/bpmn/EXP-CLAIM?lang=es`.

<details><summary>Solución</summary>

En `messages_es.properties`:

```properties
pac.process.EXP-PAYMENT.name=Pago de gastos
pac.flowNode.PAY-ORDER-RECEIVED.name=Orden de pago recibida
pac.flowNode.PAY-TRANSFER.name=Enviar transferencia
pac.flowNode.PAY-CONFIRMED.name=Pago confirmado
```

El nombre del proceso aparece en su propio diagrama y en el participante `Participant_EXP-PAYMENT` del diagrama de
`EXP-CLAIM`, porque ambos salen de la misma clave.

</details>

## Para saber más

- [Exportación BPMN 2.0](../bpmn-trazabilidad-i18n.md#exportación-bpmn-20) y
  [extensiones `pac:`](../bpmn-trazabilidad-i18n.md#extensiones-pac).
- [Trazabilidad](../bpmn-trazabilidad-i18n.md#trazabilidad).
- [Internacionalización](../bpmn-trazabilidad-i18n.md#internacionalización) y
  [textos por defecto](../bpmn-trazabilidad-i18n.md#textos-por-defecto).
- [Catálogo de contratos de mensaje](../mensajes.md#catálogo-de-contratos), para el lado de los mensajes.

---

[← Anterior: Versiones de procesos y reglas](09-versiones.md) | [Índice](README.md) | [Siguiente: Estadísticas y visor web →](11-estadisticas-y-visor-web.md)
