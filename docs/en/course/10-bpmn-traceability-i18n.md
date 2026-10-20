# Lesson 10. BPMN, traceability and i18n

> 🇪🇸 [Versión en español](../../es/curso/10-bpmn-trazabilidad-i18n.md)

**Goal:** make the most of the model coming from the code: list the diagrams, follow each step to its method and its
rule, and show the diagram in Spanish.

## What you will learn

- Which diagrams the library generates and what the exported XML contains, including the `pac:` extensions.
- How to walk the chain Process → Node → Rule → Code → Message with `TraceabilityReport`.
- How to find out where a version of a rule is applied with `Definitions.usagesOf`.
- How to translate the model texts with `MessageSourceLabelResolver` and keys derived from the ids.

## Starting point

The code from [lesson 9](09-versions.md): `EXP-CLAIM` v1 and v2, `EXP-PAYMENT` v1, rule `R-EXP-002` in two versions
and the data in H2 with JPA. `DiagramController` is still as in lesson 1, with `LabelResolver.CANONICAL`.

## Steps

### 1. Which diagrams there are

`BpmnDiagramService.diagrams()` returns one diagram per process (with its current version), one for each previous
version that is still deployed and, since there are messages between processes, the collaboration diagram. Add a
listing to `DiagramController`:

```java
	@GetMapping("/bpmn")
	List<String> ids() {
		return diagrams.diagrams().stream().map(BpmnDiagram::id).toList();
	}
```

With the imports `java.util.List` and `io.github.eldiegos.bpmncode.bpmn.BpmnDiagram`. `GET /bpmn` returns:

```json
["EXP-CLAIM","EXP-CLAIM-v1","EXP-PAYMENT","COLLABORATION"]
```

| Id | Type (`BpmnDiagram`) | What it draws |
|---|---|---|
| `EXP-CLAIM` | `ProcessDiagram` | the current version (v2) and, as a collapsed pool, the process it communicates with |
| `EXP-CLAIM-v1` | `ProcessVersionDiagram` | v1, which is still deployed |
| `EXP-PAYMENT` | `ProcessDiagram` | the payment |
| `COLLABORATION` | `CollaborationDiagram` | both processes and the message flow between them |

### 2. Read the XML

Open `/bpmn/EXP-CLAIM`. Besides the semantic model (`bpmn:process`, nodes, flows) and the drawing (`bpmndi`), you will
see what BPMN cannot express, in extensions with the `pac:` prefix:

| Extension | Where | Example from the course |
|---|---|---|
| `pac:version` | process, participant, message flow | `pac:version="2"` |
| `pac:definedBy`, `pac:implementedBy` | process | `ExpenseClaimProcessV2` |
| `pac:implementation` | each node | `method="route(ProcessInstanceRef, ExpenseClaim, boolean)"` |
| `pac:businessRuleRef` | tasks and gateways | `id="R-EXP-002" … version="2"` |
| `pac:when` | flow of a gateway | `pac:when="NEEDS_APPROVAL"` |
| `pac:messageVersion` | message | `version="1"` and `schemaHash="sha256:0077…"` |

The payment pool appears collapsed in this diagram, and its documentation starts with
`Process EXP-PAYMENT v1 (outside this diagram).` That text is translated too (key `pac.bpmn.doc.externalProcess`,
which the library ships in English and Spanish; step 4).

If you added the roles of step 7 of [lesson 6](06-waiting-tasks.md), `EXP-APPROVE` carries a `bpmn:potentialOwner`
(`EXP-APPROVE_PotentialOwner_1`) with `bpmn:resourceRef` `Resource_RES-EXPENSE-APPROVER` and a `bpmn:humanPerformer`
with the expression in `bpmn:formalExpression`, and the document declares the `bpmn:resource` with its
`bpmn:resourceParameter` `department` and `pac:definedBy`. Its name is translated with
`pac.resource.RES-EXPENSE-APPROVER.name`.

### 3. Traceability

`TraceabilityReport` is a bean with one entry (`TraceabilityEntry`) for each node of each version of each process:
process and version, node and type, class and method, applied rules, published or received message and, at an error
end event, the error it ends with (`thrownError`). For the opposite direction, `registry.definitions().usagesOf(rule)` tells you which nodes of which versions apply a version of
a rule.

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

The evidence completes the chain: each `HistoricFlowNodeInstance` carries `processId`, `processVersion` and
`flowNodeId`, so from any step of any claim you reach its traceability entry, that is, the method and the version of
the rule that was applied to it.

### 4. Translate the texts

The `name`s in the annotations are the **canonical** texts. Translating them does not touch the code: `LabelResolver`
looks up keys derived from the ids, with the `pac.` prefix:

| Key | Text |
|---|---|
| `pac.process.<id>.name` | name of the process |
| `pac.flowNode.<id>.name` | name of a node |
| `pac.sequenceFlow.<id>.name` | label of a flow (its default id is `Flow_<source>_<target>`) |
| `pac.businessRule.<id>.name`, `pac.message.<id>.name` | name of a rule, of a message |

If a key has no translation, the canonical text is used: you will never see a key instead of a text.

Create the application's message files:

`src/main/resources/messages.properties` (empty: the canonical English is already in the code):

```properties
```

`src/main/resources/messages_es.properties`:

```properties
pac.process.EXP-CLAIM.name=Solicitud de gastos
pac.flowNode.EXP-ROUTE.name=¿Necesita aprobación?
pac.sequenceFlow.Flow_EXP-DECISION_EXP-PAY.name=Sí
```

And add to `application.properties` the library's bundle, which ships the names of the BPMN element types in English
and Spanish (`pac.bpmn.userTask`…). Your keys win if you repeat one:

```properties
spring.messages.basename=messages,io/github/eldiegos/bpmncode/i18n/messages
spring.messages.fallback-to-system-locale=false
```

The second line matters. By default, if you ask for a language without its own file (`en`: there is no
`messages_en.properties`), Spring first looks for the one of the system language. On a machine configured in Spanish,
the "English" diagram would come out in Spanish. With `false`, a language without a file uses `messages.properties`
and, failing that, the canonical texts.

### 5. The diagram in the requested language

`MessageSourceLabelResolver` translates with Spring's `MessageSource` for a `Locale`. It is not a bean: create it
wherever you need it. The complete `DiagramController`:

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

`xml` caches one document per diagram and language, so the second argument has to identify the language of the
`LabelResolver`: if you always passed `"en"`, the first translation requested would serve for all of them.

## Check that it works

Start the application and request the trace and the usages of each version of the threshold rule:

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

And the diagram in both languages:

```bash
curl -s 'localhost:8080/bpmn/EXP-CLAIM?lang=es' | grep -o 'name="[^"]*"' | sort -u
```

Among the names you see `Solicitud de gastos`, `¿Necesita aprobación?` and `Sí`; the ones you did not translate stay in
English (`Approve claim`, `No`…). With `?lang=en`, or without the parameter, everything comes out in English.

## Common mistakes

- **Accented characters come out wrong.** Spring Boot reads `messages*.properties` in UTF-8 by default
  (`spring.messages.encoding`); save the file in UTF-8.
- **A translation does not show up.** Check the key: ids are case-sensitive and the id of a flow is
  `Flow_<source>_<target>` unless you gave it another `id`. A misspelt key does not raise an error: the canonical
  text is shown.
- **The English diagram comes out in Spanish.** `spring.messages.fallback-to-system-locale=false` is missing and the
  system is in Spanish (step 4).
- **The language "sticks".** You passed `xml` a language that does not match the `LabelResolver` (step 5).
- **You want a prefix other than `pac.`.** Use `new MessageSourceLabelResolver(messages, locale, "myapp.")`.

## Exercise

Translate the payment process into Spanish: its name and its three nodes. Check the result in
`/bpmn/EXP-PAYMENT?lang=es` and in the collapsed pool of `/bpmn/EXP-CLAIM?lang=es`.

<details><summary>Solution</summary>

In `messages_es.properties`:

```properties
pac.process.EXP-PAYMENT.name=Pago de gastos
pac.flowNode.PAY-ORDER-RECEIVED.name=Orden de pago recibida
pac.flowNode.PAY-TRANSFER.name=Enviar transferencia
pac.flowNode.PAY-CONFIRMED.name=Pago confirmado
```

The name of the process appears in its own diagram and in the participant `Participant_EXP-PAYMENT` of the
`EXP-CLAIM` diagram, because both come from the same key.

</details>

## Learn more

- [BPMN 2.0 export](../bpmn-traceability-i18n.md#bpmn-20-export) and
  [`pac:` extensions](../bpmn-traceability-i18n.md#pac-extensions).
- [Traceability](../bpmn-traceability-i18n.md#traceability).
- [Internationalization](../bpmn-traceability-i18n.md#internationalization) and
  [default texts](../bpmn-traceability-i18n.md#default-texts).
- [Contract catalog](../messages.md#contract-catalog), for the message side.

---

[← Previous: Process and rule versions](09-versions.md) | [Index](README.md) | [Next: Statistics and web viewer →](11-statistics-and-web-viewer.md)
