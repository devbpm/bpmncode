# BPMN, traceability and internationalization

> 🇪🇸 [Versión en español](../es/bpmn-trazabilidad-i18n.md)

## BPMN 2.0 export

Diagrams are always derived from the model read from the code: there are no hand-written `.bpmn` files. Each document
carries the semantic model (`bpmn:definitions`, processes, nodes, flows, messages, errors, resources) and the diagram interchange
(BPMN DI) with an automatic left-to-right layout, so it opens directly in [bpmn.io](https://bpmn.io), Camunda Modeler or
any compatible tool. The library's tests validate the output against the official OMG XSDs.

### `BpmnDiagramService`

| Method | Returns |
|---|---|
| `diagrams()` | one diagram per process and, if there are messages between processes, the collaboration diagram at the end |
| `diagram(diagramId)` | a diagram by its id |
| `diagramOf(ref)` | the diagram showing a specific version of a process |
| `elements(diagram)` | the selectable elements of the BPMN, each with the model it comes from |
| `xml(diagram, language, labels)` | the BPMN 2.0 XML document, with the texts translated by `labels` |

`xml` caches the document per diagram and language (`language` is part of the key), so it must identify the language
of `labels`.

There are three kinds of diagram (`BpmnDiagram`, a sealed interface):

| Kind | Id | Content |
|---|---|---|
| `ProcessDiagram` | the process id, e.g. `PER-REGISTRATION` | the current version of the process and, as collapsed pools, the processes it communicates with |
| `ProcessVersionDiagram` | `<process>-v<n>`, e.g. `PER-REGISTRATION-v1` | an earlier version that is still deployed |
| `CollaborationDiagram` | `COLLABORATION` | all the processes that exchange messages, with their message flows |

`elements(diagram)` returns `DiagramElement` (a sealed interface): `Participant`, `FlowNode`, `SequenceFlow` and
`MessageFlow`, each with the element id in the XML and the exact definition (process and version) it shows. It lets a
viewer such as `bpmn-js` show a detail card when an element is clicked.

### Without Spring

`BpmnXmlExporter` does the same without beans, useful in tests or command-line tools:

```java
var definitions = AnnotationProcessModelReader.scan("com.example.orders");
String xml = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("ORDER"));
```

There are overloads with `LabelResolver` and with the `targetNamespace`.

### Document attributes

- `targetNamespace`: the property `pac.process.bpmn.target-namespace` (by default `https://pac.dev/process`).
- `exporter` and `exporterVersion`: `bpmncode` and the jar version.

### `pac:` extensions

Whatever BPMN does not define is exported as extension elements in the namespace `https://pac.dev/schema/bpmn`
(prefix `pac:`), which the BPMN schema allows on any element:

| Extension | Where | Meaning |
|---|---|---|
| `pac:version` | process, participant, message end event, send task, message flow | process version, or published message version |
| `pac:definedBy`, `pac:implementedBy` | process | classes declaring the process (`@Process`, `@ProcessPart`) |
| `pac:definedBy` | resource | Java type declaring the resource (`@ProcessResource`) |
| `pac:implementation` | flow node | class and method implementing it |
| `pac:businessRuleRef` | tasks and gateways | rule and version applied |
| `pac:when` | sequence flow | enum constant that selects the branch |
| `pac:schedule`, `pac:zone`, `pac:initialDelay` | timer start event | the resolved `@Scheduled` |
| `pac:messageVersion` | message | each version, with its `itemDefinition` and its schema fingerprint |
| `pac:acceptedVersions` | message start event | message versions it accepts |

`bpmn:documentation` only carries business text; technical data always goes in extensions.

Each error some process of the diagram ends with (`@ErrorEndEvent`) is exported as a `bpmn:error` with id `Error_<id>`,
`name`, `errorCode` and a `structureRef` to a `bpmn:itemDefinition` (`ItemDefinition_<id>`) whose `structureRef` is
the Java class of the exception; the end event carries a `bpmn:errorEventDefinition` with `errorRef="Error_<id>"`.

User task roles (`@PotentialOwner`, `@HumanPerformer`) are exported as `bpmn:potentialOwner` and `bpmn:humanPerformer`
inside the `bpmn:userTask` (ids `<taskId>_PotentialOwner_<n>` and `<taskId>_HumanPerformer_<n>`), with a
`bpmn:resourceRef` to `Resource_<id>` or a `bpmn:resourceAssignmentExpression` with
`bpmn:formalExpression language="text/plain"`. Each referenced resource is exported as a `bpmn:resource` with its
`bpmn:resourceParameter` elements and one `bpmn:itemDefinition` per parameter (`ItemDefinition_<id>_<parameter>`). It
is description only: the library does not assign tasks (see [User task roles](annotations.md#user-task-roles)).

## Traceability

`TraceabilityReport.entries()` returns one `TraceabilityEntry` for each flow node of each version of each process: the
chain **Process → Flow node → Rule → Code → Message** (or error).

| Component | Meaning |
|---|---|
| `processId`, `processName`, `processVersion` | process and version |
| `flowNodeId`, `flowNodeName`, `elementType` | node and its BPMN type |
| `javaClass`, `javaMethod` | code implementing the node |
| `businessRules` | rules (with version) it applies |
| `thrownMessage`, `caughtMessage` | message it publishes (message end event or send task) or that triggers it, if any |
| `thrownError` | error the process ends with at the node (`@ErrorEndEvent`), if any |

Together with `ProcessInstanceHistory` the chain is completed down to **Instance → Evidence**: each
`HistoricFlowNodeInstance` carries the process, the version and the node, which link to its traceability entry.

The other way round, `Definitions.usagesOf(businessRuleRef)` tells where a version of a rule is applied: one
`BusinessRuleUsage` (process version and flow node) per node that references it, in model order. For message
contracts, `MessageContractCatalog` gathers publishers and consumers (see [Messages](messages.md#contract-catalog)).

```java
registry.definitions().usagesOf(new BusinessRuleRef("R-PER-001", 2))
		.forEach(usage -> log.info("{} {}", usage.process(), usage.flowNode().id()));
```

## Internationalization

The texts of the annotations (`name`, `documentation`, `conditionExpression`, `loopCondition`) are the **canonical**
ones. `LabelResolver` translates them with keys derived from the stable ids and the BPMN vocabulary, all under a prefix
(`pac.` by default) so they do not clash with the application's keys:

| Key | Text |
|---|---|
| `pac.process.<id>.name` | process name |
| `pac.process.<id>.documentation`, `pac.process.<id>.v<n>.documentation` | process documentation, general or for one version |
| `pac.flowNode.<id>.name` | node name |
| `pac.flowNode.<id>.documentation`, `pac.flowNode.<id>.v<n>.documentation` | node documentation |
| `pac.flowNode.<id>.loopCondition` | condition of its standard loop |
| `pac.sequenceFlow.<id>.name`, `pac.sequenceFlow.<id>.conditionExpression` | name and condition of a flow |
| `pac.businessRule.<id>[.v<n>].name`, `….documentation` | name and documentation of a rule (general or for one version) |
| `pac.message.<id>.name` | name of a message |
| `pac.error.<id>.name`, `pac.error.<id>.documentation` | name and documentation of an error (`@ProcessError`) |
| `pac.resource.<id>.name`, `pac.resource.<id>.documentation` | name and documentation of a resource (`@ProcessResource`) |
| `pac.bpmn.<localName>` | name of an element type, e.g. `pac.bpmn.serviceTask` |

If a key has no translation, the canonical text is returned: a key is never shown instead of a text.

`LabelResolver` is a functional interface with a single method, `resolve(key, fallback, arguments)`, and default
methods for each text (`processName`, `flowNodeName`, `businessRuleName`, `messageName`, `errorName`, `resourceName`,
`elementType`…). `LabelResolver.CANONICAL` always returns the canonical texts. With a Spring `MessageSource`, use
`MessageSourceLabelResolver`:

```java
var labels = new MessageSourceLabelResolver(messages, locale);   // or (messages, locale, "myapp.") with another prefix
String xml = diagrams.xml(diagram, locale.toLanguageTag(), labels);
```

`MessageSourceLabelResolver` is not a bean: create it where you need it, usually one per language (`locale()` tells
which). A key with no translation returns the canonical text, formatted like `LabelResolver.CANONICAL`; it never
returns the key nor throws for a missing key.

```properties
# messages_es.properties
pac.process.ORDER.name=Gestión de pedidos
pac.flowNode.CHECK-CREDIT.name=¿Tiene crédito suficiente?
pac.sequenceFlow.Flow_CHECK-CREDIT_SHIP.name=Sí
pac.bpmn.serviceTask=Tarea de servicio
```

To use another prefix, pass it to `MessageSourceLabelResolver` or override `keyPrefix()` in your implementation.

### Default texts

The library ships the names of the BPMN element types it uses (`pac.bpmn.startEvent`, `pac.bpmn.endEvent`,
`pac.bpmn.serviceTask`, `pac.bpmn.businessRuleTask`, `pac.bpmn.userTask`, `pac.bpmn.sendTask`,
`pac.bpmn.exclusiveGateway`) and the
documentation of the pools of other processes (`pac.bpmn.doc.externalProcess`), in English and Spanish, in the bundle
`io/github/eldiegos/bpmncode/i18n/messages`. They are opt-in: the application uses them by adding it to its
`basename`, and its own keys still win if it repeats them.

```properties
spring.messages.basename=messages,io/github/eldiegos/bpmncode/i18n/messages
```

The [web module](statistics-web.md#texts) always uses them, after the application's messages.

## Web viewer

To see the diagrams in the application itself, with the card of each element, the path of an instance and the
heatmap of a version, enable the optional web module (`pac.process.web.enabled=true`); see
[Statistics and web module](statistics-web.md#web-module).
