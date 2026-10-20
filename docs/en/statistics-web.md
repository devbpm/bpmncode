# Statistics and web module

> 🇪🇸 [Versión en español](../es/estadisticas-web.md)

The evidence the journal stores is good for more than auditing: `ProcessExecutionStatistics` aggregates it into
heatmaps, instance paths and counts, and the optional web module draws it on the BPMN diagram in a bpmn-js viewer.

## Execution statistics

`ProcessExecutionStatistics` (package `statistics`) is a bean that `ProcessStatisticsAutoConfiguration` registers in
every application; if you do not inject it, it is not used. It only reads, through `ProcessInstanceHistory`, and
returns identifiers and figures, never texts: each application presents and translates them its own way (for example
with [`LabelResolver`](bpmn-traceability-i18n.md#internationalization)).

| Method | Returns |
|---|---|
| `heatmap(ref)` | `Optional<ExecutionHeatmap>`: the heatmap of a deployed version; empty if that version is not deployed |
| `path(instanceId)` | `Optional<InstancePath>`: the path followed by an instance; empty if it does not exist |
| `waitingByNode(ref)` | `Map<String, Long>`: active instances waiting at each node of a version (nodes with none are absent) |
| `countsByVersion()` | `Map<ProcessDefinitionRef, InstanceCounts>`: active and completed instances of each version, sorted by process and version; versions without instances are absent (use `InstanceCounts.NONE`) |

A version is computed with two store queries (its instances and all its evidence, see
[queries by version](storage.md#a-custom-store)), never with one per instance.

```java
var heatmap = statistics.heatmap(ProcessDefinitionRef.of("ORDER", 2)).orElseThrow();
heatmap.instances();                                  // running + completed
heatmap.node("CHECK-CREDIT").map(NodeStatistics::executions);
statistics.path(instanceId).flatMap(InstancePath::waitingFlowNodeId);
```

### `ExecutionHeatmap`

| Component | Meaning |
|---|---|
| `process` | process and version |
| `instances`, `running`, `completed` | instances of the version, active and completed (`instances = running + completed`) |
| `maxExecutions` | executions of the most executed node (0 if there are none) |
| `nodes` | one `NodeStatistics` per node of the model, in model order, also those never executed |
| `flows` | one `FlowStatistics` per sequence flow of the model, in model order |

`NodeStatistics` carries `flowNodeId`, `executions` (times it was executed: a loop or a cycle counts it several
times), `instances` (distinct instances that went through it), `share` (percentage of the version's instances that
went through it, from 0 to 100) and `waiting` (active instances waiting at it). `FlowStatistics` carries
`sequenceFlowId` and `traversals`: a step between two consecutive nodes of the evidence counts as a traversal only if
the model declares that flow, so another iteration of a standard loop traverses none.

### `InstancePath`

| Component | Meaning |
|---|---|
| `instanceId`, `process` | instance and the version it runs |
| `running` | whether it is still active |
| `steps` | `PathStep` in sequence order: `order` (from 1), `flowNodeId`, `loopCounter` and `occurredAt`; a node appears several times if a loop or a cycle repeated it |
| `sequenceFlowIds` | flows traversed, in order |
| `waitingFlowNodeId` | node it waits at if it is active; empty if it is completed |

`InstanceCounts` carries `running`, `completed` and `total()`.

## Web module

An optional module publishes a read-only API and a bpmn-js viewer: diagrams, the card of each element (what it is,
which version it belongs to, which code and which rules implement it, which message it consumes, publishes or sends,
which error it ends with and, for a user task, its roles), the path of an instance and the heatmap of a version, in the
language of each request. The application does not use its classes (the `web` package is not a named interface), only
its routes.

### Enabling it

**It is off by default**, because it exposes the code that implements each step and the instances' data.
`ProcessWebAutoConfiguration` only applies if all three conditions hold:

- the application is a servlet web application;
- `DispatcherServlet` (Spring MVC) is on the classpath;
- `pac.process.web.enabled=true`.

`spring-webmvc` and `jakarta.servlet-api` are optional dependencies of the library: the application brings them (e.g.
with `spring-boot-starter-webmvc`). The application also provides bpmn-js; by default it is loaded from the WebJar:

```xml
<dependency>
    <groupId>org.webjars.npm</groupId>
    <artifactId>bpmn-js</artifactId>
    <version>18.10.1</version>
    <exclusions>
        <exclusion>
            <groupId>*</groupId>
            <artifactId>*</artifactId>
        </exclusion>
    </exclusions>
</dependency>
```

The exclusion is required: only the `dist` folder included in the jar is needed, and without it Maven cannot resolve
the WebJar's transitive dependencies (`Could not resolve version conflict among [...] tiny-svg ...`).

```properties
pac.process.web.enabled=true
# optional
pac.process.web.base-path=/bpmncode
pac.process.web.bpmn-js-location=/webjars/bpmn-js/18.10.1/dist
```

| Property | Default | Use |
|---|---|---|
| `pac.process.web.enabled` | `false` | publishes the viewer and the API |
| `pac.process.web.base-path` | `/bpmncode` | path under which they are published; starts with `/` and does not end with `/` (otherwise the application does not start) |
| `pac.process.web.bpmn-js-location` | `/webjars/bpmn-js/18.10.1/dist` | bpmn-js `dist` folder, absolute or relative to the context path (must not be blank; a trailing slash is removed) |

**It adds no security.** If the application has Spring Security, these routes are protected by its rules like any
other; if it does not, they are public. Protect them or enable the module only in the environments where it belongs.

### Routes

All under `<base-path>` (`/bpmncode` by default) and read-only (`GET`).

| Route | Returns |
|---|---|
| `/viewer.html` | the viewer |
| `/static/**` | viewer resources (`viewer.js`, `viewer.css`) |
| `/api/views` | available diagrams (`id` and translated name) |
| `/api/views/{id}` | the BPMN 2.0 XML of a diagram, with translated texts |
| `/api/views/{id}/elements` | the card of each element of the diagram |
| `/api/views/{id}/heatmap` | the heatmap of the version the diagram draws; 404 for the collaboration diagram |
| `/api/instances/{id}/path` | the path of an instance, on the diagram of its version |
| `/api/i18n/viewer` | the viewer texts |
| `/api/processes` | `Definitions`: the complete model |
| `/api/processes/messages` | the messages and their versions |
| `/api/processes/traceability` | the entries of `TraceabilityReport` |
| `/api/processes/instances` | every instance (`ProcessInstanceSummary`) |
| `/api/processes/instances/{id}/evidence` | the evidence of an instance (`HistoricFlowNodeInstance`) |

An unknown diagram or instance is a 404. The `/api/views…`, `/api/instances…` and `/api/i18n…` routes accept `?lang=`
and answer with `Content-Language`; the `/api/processes` routes are technical: identifiers, canonical names and codes,
untranslated.

The card of a user task carries its roles in the `roles` list, separate from `events` (it is empty for every other
element). Each `@PotentialOwner` and each `@HumanPerformer` is one entry, in declaration order (potential owners
first):

| Field | Resource (`resourceRef`) | Expression (`expression`) |
|---|---|---|
| `kind` | translated role: `pac.web.diagramElement.potentialOwner` or `.humanPerformer` | same |
| `resourceId` | resource id | `null` |
| `name` | translated resource name (`pac.resource.<id>.name`) | text of the expression |
| `type` | Java class declaring the resource | `null` |

The viewer shows them in their own section (`pac.web.viewer.details.roles`). It is the description of the model, not
the person the application has assigned the task to.

### The viewer

`<base-path>/viewer.html` accepts these parameters:

| Parameter | Effect |
|---|---|
| `view` | diagram to open, e.g. `ORDER` or `ORDER-v1` |
| `element` | element selected on opening, with its card |
| `instance` | draws the path of that instance: numbered steps, traversed flows and waiting step |
| `heatmap=1` | draws the heatmap of the version (not combined with `instance`) |
| `lang` | language of the texts; if absent, that of the request |

```
/bpmncode/viewer.html?view=ORDER&heatmap=1&lang=es
/bpmncode/viewer.html?instance=3f2c…&element=CHECK-CREDIT
```

### Texts

Each text is looked up first in the application's `MessageSource`, which can thus replace any of them, and then in
the library's bundles (English by default, and Spanish):

| Bundle | Keys |
|---|---|
| `io/github/eldiegos/bpmncode/web/messages` | `pac.web.*`: viewer and cards, e.g. `pac.web.diagramElement.publishes`, `.sends`, `.throws` (*Ends with error* / *Termina con error*), `.potentialOwner` (*Potential owner* / *Propietario potencial*) or `.humanPerformer` (*Human performer* / *Persona que la realiza*), and `pac.web.viewer.details.roles` (*Who does it* / *Quién la hace*) |
| `io/github/eldiegos/bpmncode/i18n/messages` | `pac.bpmn.*`: BPMN element types (see [i18n](bpmn-traceability-i18n.md#default-texts)) |

The model texts (`pac.process.*`, `pac.flowNode.*`…) come from the application as in the exported BPMN, so the viewer
shows the same names as the diagram. A key with no translation in either returns the canonical text.
