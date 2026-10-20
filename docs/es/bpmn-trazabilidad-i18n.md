# BPMN, trazabilidad e internacionalización

> 🇬🇧 [English version](../en/bpmn-traceability-i18n.md)

## Exportación BPMN 2.0

Los diagramas se derivan siempre del modelo leído del código: no hay ficheros `.bpmn` escritos a mano. Cada documento
lleva el modelo semántico (`bpmn:definitions`, procesos, nodos, flujos, mensajes, errores, recursos) y el intercambio de diagramas
(BPMN DI) con una disposición automática de izquierda a derecha, así que se abre directamente en
[bpmn.io](https://bpmn.io), Camunda Modeler o cualquier herramienta compatible. Las pruebas de la librería validan el
resultado contra los XSD oficiales de OMG.

### `BpmnDiagramService`

| Método | Devuelve |
|---|---|
| `diagrams()` | un diagrama por proceso y, si hay mensajes entre procesos, el de colaboración al final |
| `diagram(diagramId)` | un diagrama por su id |
| `diagramOf(ref)` | el diagrama que muestra una versión concreta de un proceso |
| `elements(diagram)` | los elementos seleccionables del BPMN, cada uno con el modelo del que procede |
| `xml(diagram, language, labels)` | el documento BPMN 2.0 XML, con los textos traducidos por `labels` |

`xml` guarda en caché el documento por diagrama e idioma (`language` es parte de la clave), así que debe identificar
el idioma de `labels`.

Hay tres tipos de diagrama (`BpmnDiagram`, interfaz sellada):

| Tipo | Id | Contenido |
|---|---|---|
| `ProcessDiagram` | el id del proceso, p. ej. `PER-REGISTRATION` | la versión vigente del proceso y, como piscinas colapsadas, los procesos con los que se comunica |
| `ProcessVersionDiagram` | `<proceso>-v<n>`, p. ej. `PER-REGISTRATION-v1` | una versión anterior que sigue desplegada |
| `CollaborationDiagram` | `COLLABORATION` | todos los procesos que intercambian mensajes, con sus flujos de mensaje |

`elements(diagram)` devuelve `DiagramElement` (interfaz sellada): `Participant`, `FlowNode`, `SequenceFlow` y
`MessageFlow`, cada uno con el id del elemento en el XML y la definición exacta (proceso y versión) que muestra. Sirve
para que un visor como `bpmn-js` muestre una ficha al hacer clic en un elemento.

### Sin Spring

`BpmnXmlExporter` hace lo mismo sin beans, útil en pruebas o en herramientas de línea de comandos:

```java
var definitions = AnnotationProcessModelReader.scan("com.example.orders");
String xml = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("ORDER"));
```

Hay sobrecargas con `LabelResolver` y con el `targetNamespace`.

### Atributos del documento

- `targetNamespace`: la propiedad `pac.process.bpmn.target-namespace` (por defecto `https://pac.dev/process`).
- `exporter` y `exporterVersion`: `bpmncode` y la versión del jar.

### Extensiones `pac:`

Lo que BPMN no define se exporta como elementos de extensión en el espacio de nombres `https://pac.dev/schema/bpmn`
(prefijo `pac:`), que el esquema BPMN permite en cualquier elemento:

| Extensión | Dónde | Significado |
|---|---|---|
| `pac:version` | proceso, participante, evento de fin de mensaje, tarea de envío, flujo de mensaje | versión del proceso, o versión publicada del mensaje |
| `pac:definedBy`, `pac:implementedBy` | proceso | clases que declaran el proceso (`@Process`, `@ProcessPart`) |
| `pac:definedBy` | recurso | tipo Java que declara el recurso (`@ProcessResource`) |
| `pac:implementation` | nodo de flujo | clase y método que lo implementan |
| `pac:businessRuleRef` | tareas y gateways | regla y versión aplicadas |
| `pac:when` | flujo de secuencia | constante del enum que selecciona la rama |
| `pac:schedule`, `pac:zone`, `pac:initialDelay` | evento de inicio de temporizador | el `@Scheduled` resuelto |
| `pac:messageVersion` | mensaje | cada versión, con su `itemDefinition` y la huella de su esquema |
| `pac:acceptedVersions` | evento de inicio de mensaje | versiones del mensaje que acepta |

`bpmn:documentation` solo lleva texto de negocio; los datos técnicos van siempre en extensiones.

Cada error con el que termina algún proceso del diagrama (`@ErrorEndEvent`) se exporta como `bpmn:error` con id
`Error_<id>`, `name`, `errorCode` y `structureRef` hacia un `bpmn:itemDefinition` (`ItemDefinition_<id>`) cuyo
`structureRef` es la clase Java de la excepción; el evento de fin lleva un `bpmn:errorEventDefinition` con
`errorRef="Error_<id>"`.

Los roles de las tareas humanas (`@PotentialOwner`, `@HumanPerformer`) se exportan como `bpmn:potentialOwner` y
`bpmn:humanPerformer` dentro del `bpmn:userTask` (ids `<taskId>_PotentialOwner_<n>` y `<taskId>_HumanPerformer_<n>`),
con un `bpmn:resourceRef` hacia `Resource_<id>` o un `bpmn:resourceAssignmentExpression` con
`bpmn:formalExpression language="text/plain"`. Cada recurso referenciado se exporta como `bpmn:resource` con sus
`bpmn:resourceParameter` y un `bpmn:itemDefinition` por parámetro (`ItemDefinition_<id>_<parámetro>`). Es solo
descripción: la librería no asigna tareas (véase [Roles de una tarea humana](anotaciones.md#roles-de-una-tarea-humana)).

## Trazabilidad

`TraceabilityReport.entries()` devuelve una `TraceabilityEntry` por cada nodo de flujo de cada versión de cada
proceso: la cadena **Proceso → Nodo de flujo → Regla → Código → Mensaje** (o error).

| Componente | Significado |
|---|---|
| `processId`, `processName`, `processVersion` | proceso y versión |
| `flowNodeId`, `flowNodeName`, `elementType` | nodo y su tipo BPMN |
| `javaClass`, `javaMethod` | código que implementa el nodo |
| `businessRules` | reglas (con versión) que aplica |
| `thrownMessage`, `caughtMessage` | mensaje que publica (evento de fin de mensaje o tarea de envío) o que lo dispara, si los hay |
| `thrownError` | error con el que termina el proceso en el nodo (`@ErrorEndEvent`), si lo hay |

Junto con `ProcessInstanceHistory` se completa la cadena hasta **Instancia → Evidencia**: cada
`HistoricFlowNodeInstance` lleva el proceso, la versión y el nodo, que enlazan con su entrada de trazabilidad.

En sentido contrario, `Definitions.usagesOf(businessRuleRef)` dice dónde se aplica una versión de una regla: un
`BusinessRuleUsage` (versión de proceso y nodo de flujo) por cada nodo que la referencia, en el orden del modelo. Para
los contratos de mensaje, `MessageContractCatalog` reúne publicadores y consumidores (véase
[Mensajes](mensajes.md#catálogo-de-contratos)).

```java
registry.definitions().usagesOf(new BusinessRuleRef("R-PER-001", 2))
		.forEach(usage -> log.info("{} {}", usage.process(), usage.flowNode().id()));
```

## Internacionalización

Los textos de las anotaciones (`name`, `documentation`, `conditionExpression`, `loopCondition`) son los **canónicos**.
`LabelResolver` los traduce con claves derivadas de los ids estables y del vocabulario BPMN, todas bajo un prefijo
(`pac.` por defecto) para no chocar con las claves de la aplicación:

| Clave | Texto |
|---|---|
| `pac.process.<id>.name` | nombre del proceso |
| `pac.process.<id>.documentation`, `pac.process.<id>.v<n>.documentation` | documentación del proceso, general o de una versión |
| `pac.flowNode.<id>.name` | nombre del nodo |
| `pac.flowNode.<id>.documentation`, `pac.flowNode.<id>.v<n>.documentation` | documentación del nodo |
| `pac.flowNode.<id>.loopCondition` | condición de su bucle estándar |
| `pac.sequenceFlow.<id>.name`, `pac.sequenceFlow.<id>.conditionExpression` | nombre y condición de un flujo |
| `pac.businessRule.<id>[.v<n>].name`, `….documentation` | nombre y documentación de una regla (general o de una versión) |
| `pac.message.<id>.name` | nombre de un mensaje |
| `pac.error.<id>.name`, `pac.error.<id>.documentation` | nombre y documentación de un error (`@ProcessError`) |
| `pac.resource.<id>.name`, `pac.resource.<id>.documentation` | nombre y documentación de un recurso (`@ProcessResource`) |
| `pac.bpmn.<localName>` | nombre de un tipo de elemento, p. ej. `pac.bpmn.serviceTask` |

Si una clave no tiene traducción se devuelve el texto canónico: nunca se muestra una clave en lugar de un texto.

`LabelResolver` es una interfaz funcional con un único método, `resolve(key, fallback, arguments)`, y métodos por
defecto para cada texto (`processName`, `flowNodeName`, `businessRuleName`, `messageName`, `errorName`, `resourceName`,
`elementType`…). `LabelResolver.CANONICAL` devuelve siempre los textos canónicos. Con un `MessageSource` de Spring,
usa `MessageSourceLabelResolver`:

```java
var labels = new MessageSourceLabelResolver(messages, locale);   // o (messages, locale, "myapp.") con otro prefijo
String xml = diagrams.xml(diagram, locale.toLanguageTag(), labels);
```

`MessageSourceLabelResolver` no es un bean: créalo donde lo necesites, normalmente uno por idioma (`locale()` dice
cuál). Una clave sin traducción devuelve el texto canónico, formateado como `LabelResolver.CANONICAL`; nunca devuelve
la clave ni lanza por una clave ausente.

```properties
# messages_es.properties
pac.process.ORDER.name=Gestión de pedidos
pac.flowNode.CHECK-CREDIT.name=¿Tiene crédito suficiente?
pac.sequenceFlow.Flow_CHECK-CREDIT_SHIP.name=Sí
pac.bpmn.serviceTask=Tarea de servicio
```

Para usar otro prefijo, pásalo a `MessageSourceLabelResolver` o sobrescribe `keyPrefix()` en tu implementación.

### Textos por defecto

La librería trae los nombres de los tipos de elemento BPMN que usa (`pac.bpmn.startEvent`, `pac.bpmn.endEvent`,
`pac.bpmn.serviceTask`, `pac.bpmn.businessRuleTask`, `pac.bpmn.userTask`, `pac.bpmn.sendTask`,
`pac.bpmn.exclusiveGateway`) y la
documentación de las piscinas de otros procesos (`pac.bpmn.doc.externalProcess`), en inglés y en español, en el
bundle `io/github/eldiegos/bpmncode/i18n/messages`. Son opcionales: la aplicación los usa añadiéndolo a sus
`basename`, y sus propias claves siguen mandando si las repite.

```properties
spring.messages.basename=messages,io/github/eldiegos/bpmncode/i18n/messages
```

El [módulo web](estadisticas-web.md#textos) los usa siempre, después de los mensajes de la aplicación.

## Visor web

Para ver los diagramas en la propia aplicación, con la ficha de cada elemento, el camino de una instancia y el mapa de
calor de una versión, activa el módulo web opcional (`pac.process.web.enabled=true`); véase
[Estadísticas y módulo web](estadisticas-web.md#módulo-web).
