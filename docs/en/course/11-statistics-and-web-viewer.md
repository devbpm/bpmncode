# Lesson 11. Statistics and web viewer

> 🇪🇸 [Versión en español](../../es/curso/11-estadisticas-y-visor-web.md)

**Goal:** turn the evidence into useful information (how many claims are waiting and where, which path each one
followed) and see it painted on the diagram with the viewer that ships with the library.

## What you will learn

- What `ProcessExecutionStatistics` computes: heatmap, path of an instance, waiting instances and counts.
- How to enable the optional web module and provide bpmn-js.
- How to open the viewer with a diagram, the heatmap or the path of an instance.
- Why the web module is off by default and how to protect it.

## Starting point

The code from [lesson 10](10-bpmn-traceability-i18n.md): two processes with messages, two versions of `EXP-CLAIM`,
data in H2 with JPA, a translated `DiagramController` and `TraceabilityController`.

## Steps

### 1. The statistics

`ProcessExecutionStatistics` is a read-only bean that aggregates the evidence **per version**: each calculation makes
two queries to the store (the instances of the version and all their evidence), never one per instance. It returns ids
and figures, without texts: you decide how to present them.

| Method | Returns |
|---|---|
| `heatmap(ref)` | `Optional<ExecutionHeatmap>`: for each node, executions, instances, percentage and how many are waiting in it |
| `path(instanceId)` | `Optional<InstancePath>`: the steps of an instance in order and, if it is still active, where it is waiting |
| `waitingByNode(ref)` | `Map<String, Long>`: active instances waiting in each node |
| `countsByVersion()` | `Map<ProcessDefinitionRef, InstanceCounts>`: active and completed per version |

`src/main/java/com/example/expenses/StatisticsController.java`:

```java
package com.example.expenses;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.statistics.ProcessExecutionStatistics;

@RestController
class StatisticsController {

	private final ProcessExecutionStatistics statistics;

	StatisticsController(ProcessExecutionStatistics statistics) {
		this.statistics = statistics;
	}

	@GetMapping("/statistics/counts")
	Map<String, Long> counts() {
		var counts = new LinkedHashMap<String, Long>();
		statistics.countsByVersion().forEach((ref, count) -> counts.put(ref.toString(), count.total()));
		return counts;
	}

	@GetMapping("/statistics/{processId}/{version}/waiting")
	Map<String, Long> waiting(@PathVariable String processId, @PathVariable int version) {
		return statistics.waitingByNode(ProcessDefinitionRef.of(processId, version));
	}

	@GetMapping("/statistics/instances/{instanceId}/path")
	List<String> path(@PathVariable UUID instanceId) {
		var path = statistics.path(instanceId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		return path.steps().stream().map(step -> step.order() + ". " + step.flowNodeId()).toList();
	}
}
```

`ProcessDefinitionRef.toString()` writes `EXP-CLAIM v2`, which works as a readable key.

### 2. Enable the web module

The library ships with a bpmn-js viewer and a read-only API. **It is off by default**, because it shows which code
implements each step and the data of the instances. It is only enabled in a servlet web application with Spring MVC
(you already have one) and with this property:

```properties
pac.process.web.enabled=true
```

The viewer loads bpmn-js from the WebJar, which you provide. Add the dependency **with the exclusion**: the WebJar
declares its npm dependencies with version ranges that Maven cannot reconcile, and you only need its `dist` folder,
which is already inside the jar itself.

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

The routes are published under `pac.process.web.base-path` (`/bpmncode` by default). The most useful ones:

| Route | Returns |
|---|---|
| `/bpmncode/viewer.html` | the viewer |
| `/bpmncode/api/views` | the available diagrams, with their translated name |
| `/bpmncode/api/views/{id}/heatmap` | the heatmap of the version the diagram draws |
| `/bpmncode/api/instances/{id}/path` | the path of an instance |
| `/bpmncode/api/processes/traceability` | the complete traceability |

The `/api/views…` and `/api/instances…` routes accept `?lang=`; without it, they use the language of the request.

### 3. Open the viewer

Start with a clean database (delete `data/`), generate some activity (five claims with receipts, of 120, 700, 1,800,
40 and 900 EUR) and open in the browser:

| URL | What you see |
|---|---|
| `/bpmncode/viewer.html?view=EXP-CLAIM&lang=en` | the diagram of v2; clicking a node shows its details: type, version, class and method, rules (and, on `EXP-APPROVE`, its roles in the «Who does it» section if you added step 7 of [lesson 6](06-waiting-tasks.md)) |
| `/bpmncode/viewer.html?view=EXP-CLAIM&heatmap=1&lang=en` | the heatmap of v2: the most travelled nodes, more intense, and how many are waiting in `EXP-APPROVE` |
| `/bpmncode/viewer.html?view=EXP-CLAIM-v1` | the previous version, with its own figures if you ask for `heatmap=1` |
| `/bpmncode/viewer.html?instance=<instanceId>` | the path of a claim: numbered steps, travelled flows and the waiting step |

The names in the viewer are the same as in the exported BPMN, because they come from the same keys (`pac.process.*`,
`pac.flowNode.*`…) of your `messages*.properties`. The viewer's own texts come from the library, in English and in
Spanish.

### 4. Protect it

The module **adds no security**. If your application has Spring Security, the `/bpmncode/**` routes are subject to its
rules like any other; if not, they are public. Before deploying, either protect it or enable it only where it belongs.
You will settle this in [lesson 12](12-testing-and-production.md).

## Check that it works

With the five claims from step 3 (if you sent others, the figures change):

```bash
curl -s localhost:8080/statistics/counts
curl -s localhost:8080/statistics/EXP-CLAIM/2/waiting
curl -s localhost:8080/statistics/EXP-PAYMENT/1/waiting
curl -s 'localhost:8080/bpmncode/api/views?lang=en'
```

```
{"EXP-CLAIM v2":5,"EXP-PAYMENT v1":2}
{"EXP-APPROVE":3}
{"PAY-CONFIRMED":2}
[{"id":"EXP-CLAIM","label":"Expense claim (EXP-CLAIM v2)"},{"id":"EXP-CLAIM-v1","label":"Expense claim (EXP-CLAIM v1, previous version)"},…]
```

With v2, 700, 1,800 and 900 EUR are waiting for approval; 120 and 40 were paid directly and their payments are waiting
for the bank. Notice that the former count in `EXP-APPROVE` (the node they can advance to), and the payments, in
`PAY-CONFIRMED`. With the `instanceId` of a waiting claim:

```bash
curl -s localhost:8080/statistics/instances/<instanceId>/path
```

```
["1. EXP-SUBMITTED","2. EXP-CHECK","3. EXP-ROUTE"]
```

`/bpmncode/api/instances/<instanceId>/path?lang=en` gives the same for the viewer, with `"waitingNodeId":"EXP-APPROVE"`.

## Common mistakes

- **Maven does not resolve the WebJar** without the exclusion:

  ```
  Could not resolve version conflict among [org.webjars.npm:bpmn-js:jar:18.10.1 -> org.webjars.npm:diagram-js:jar:[15.5.0,16.0.0-0) -> org.webjars.npm:tiny-svg:jar:[4.1.4,5.0.0-0), org.webjars.npm:bpmn-js:jar:18.10.1 -> org.webjars.npm:tiny-svg:jar:[3.1.3,4.0.0-0)]
  ```

- **`/bpmncode/viewer.html` returns 404.** `pac.process.web.enabled=true` is missing (or the application is not a
  servlet web application).
- **The viewer opens, but the diagram is not drawn.** bpmn-js cannot be found: check the WebJar or
  `pac.process.web.bpmn-js-location` if you serve it from somewhere else.
- **A misspelled base path** (`pac.process.web.base-path=bpmncode/`). It does not start:
  `pac.process.web.base-path must start with '/' and must not end with it, e.g. /bpmncode`.
- **The heatmap of `COLLABORATION`** responds 404: that diagram does not draw a single version.

## Exercise

The business asks how many claims are waiting for a manager, adding up both versions of `EXP-CLAIM`. Add
`GET /statistics/pending-approvals` that returns it.

<details><summary>Solution</summary>

```java
	@GetMapping("/statistics/pending-approvals")
	long pendingApprovals() {
		return Stream.of(1, 2)
				.map(version -> statistics.waitingByNode(ProcessDefinitionRef.of("EXP-CLAIM", version)))
				.mapToLong(waiting -> waiting.getOrDefault("EXP-APPROVE", 0L))
				.sum();
	}
```

With the import `java.util.stream.Stream`. `waitingByNode` does not include nodes with no instances waiting, hence the
`getOrDefault`. To avoid fixing the versions by hand, you can iterate over them with
`registry.definitions().versions("EXP-CLAIM")` (each `ProcessDefinition` has its `ref()`).

</details>

## Learn more

- [Execution statistics](../statistics-web.md#execution-statistics),
  [`ExecutionHeatmap`](../statistics-web.md#executionheatmap) and [`InstancePath`](../statistics-web.md#instancepath).
- [Web module](../statistics-web.md#web-module): [enabling it](../statistics-web.md#enabling-it),
  [routes](../statistics-web.md#routes), [the viewer](../statistics-web.md#the-viewer) and [texts](../statistics-web.md#texts).

---

[← Previous: BPMN, traceability and i18n](10-bpmn-traceability-i18n.md) | [Index](README.md) | [Next: Testing and production →](12-testing-and-production.md)
