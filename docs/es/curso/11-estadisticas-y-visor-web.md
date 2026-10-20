# Lección 11. Estadísticas y visor web

> 🇬🇧 [English version](../../en/course/11-statistics-and-web-viewer.md)

**Objetivo:** convertir la evidencia en información útil (cuántas solicitudes esperan y dónde, qué camino siguió cada
una) y verla pintada sobre el diagrama con el visor que trae la librería.

## Lo que vas a aprender

- Qué calcula `ProcessExecutionStatistics`: mapa de calor, camino de una instancia, instancias en espera y recuentos.
- Cómo activar el módulo web opcional y aportar bpmn-js.
- Cómo abrir el visor con un diagrama, el mapa de calor o el camino de una instancia.
- Por qué el módulo web está apagado por defecto y cómo protegerlo.

## Punto de partida

El código de la [lección 10](10-bpmn-trazabilidad-i18n.md): dos procesos con mensajes, dos versiones de `EXP-CLAIM`,
datos en H2 con JPA, `DiagramController` traducido y `TraceabilityController`.

## Pasos

### 1. Las estadísticas

`ProcessExecutionStatistics` es un bean de solo lectura que agrega la evidencia **por versión**: cada cálculo hace dos
consultas al almacén (las instancias de la versión y toda su evidencia), nunca una por instancia. Devuelve ids y
cifras, sin textos: tú decides cómo presentarlos.

| Método | Devuelve |
|---|---|
| `heatmap(ref)` | `Optional<ExecutionHeatmap>`: por cada nodo, ejecuciones, instancias, porcentaje y cuántas esperan en él |
| `path(instanceId)` | `Optional<InstancePath>`: los pasos de una instancia en orden y, si sigue activa, dónde espera |
| `waitingByNode(ref)` | `Map<String, Long>`: instancias activas que esperan en cada nodo |
| `countsByVersion()` | `Map<ProcessDefinitionRef, InstanceCounts>`: activas y completadas por versión |

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

`ProcessDefinitionRef.toString()` escribe `EXP-CLAIM v2`, que sirve de clave legible.

### 2. Activa el módulo web

La librería trae un visor bpmn-js y una API de solo lectura. **Está apagado por defecto**, porque muestra qué código
implementa cada paso y los datos de las instancias. Se activa solo en una aplicación web servlet con Spring MVC (ya la
tienes) y con esta propiedad:

```properties
pac.process.web.enabled=true
```

El visor carga bpmn-js del WebJar, que aportas tú. Añade la dependencia **con la exclusión**: el WebJar declara sus
dependencias npm con rangos de versión que Maven no consigue conciliar, y solo necesitas su carpeta `dist`, que ya va
dentro del propio jar.

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

Las rutas se publican bajo `pac.process.web.base-path` (por defecto `/bpmncode`). Las más útiles:

| Ruta | Devuelve |
|---|---|
| `/bpmncode/viewer.html` | el visor |
| `/bpmncode/api/views` | los diagramas disponibles, con su nombre traducido |
| `/bpmncode/api/views/{id}/heatmap` | el mapa de calor de la versión que dibuja el diagrama |
| `/bpmncode/api/instances/{id}/path` | el camino de una instancia |
| `/bpmncode/api/processes/traceability` | la trazabilidad completa |

Las rutas `/api/views…` e `/api/instances…` aceptan `?lang=`; sin él, usan el idioma de la petición.

### 3. Abre el visor

Arranca con una base de datos limpia (borra `data/`), genera algo de actividad (cinco solicitudes con justificantes, de
120, 700, 1.800, 40 y 900 EUR) y abre en el navegador:

| URL | Qué ves |
|---|---|
| `/bpmncode/viewer.html?view=EXP-CLAIM&lang=es` | el diagrama de la v2; al hacer clic en un nodo, su ficha: tipo, versión, clase y método, reglas (y, en `EXP-APPROVE`, sus roles en la sección «Quién la hace» si añadiste el paso 7 de la [lección 6](06-tareas-que-esperan.md)) |
| `/bpmncode/viewer.html?view=EXP-CLAIM&heatmap=1&lang=es` | el mapa de calor de la v2: los nodos más recorridos, más intensos, y cuántas esperan en `EXP-APPROVE` |
| `/bpmncode/viewer.html?view=EXP-CLAIM-v1` | la versión anterior, con sus propias cifras si pides `heatmap=1` |
| `/bpmncode/viewer.html?instance=<instanceId>` | el camino de una solicitud: pasos numerados, flujos recorridos y el paso en espera |

Los nombres del visor son los mismos que los del BPMN exportado, porque salen de las mismas claves (`pac.process.*`,
`pac.flowNode.*`…) de tus `messages*.properties`. Los textos propios del visor vienen de la librería, en inglés y en
español.

### 4. Protégelo

El módulo **no añade seguridad**. Si tu aplicación tiene Spring Security, las rutas `/bpmncode/**` quedan sujetas a sus
reglas como cualquier otra; si no, son públicas. Antes de desplegar, o lo proteges o lo activas solo donde toque. Lo
dejarás resuelto en la [lección 12](12-pruebas-y-produccion.md).

## Comprueba que funciona

Con las cinco solicitudes del paso 3 (si enviaste otras, las cifras cambian):

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

Con la v2, 700, 1.800 y 900 EUR esperan aprobación; 120 y 40 se pagaron directamente y sus pagos esperan al banco.
Fíjate en que las primeras cuentan en `EXP-APPROVE` (el nodo al que pueden avanzar), y los pagos, en `PAY-CONFIRMED`. Con el `instanceId` de una solicitud que espera:

```bash
curl -s localhost:8080/statistics/instances/<instanceId>/path
```

```
["1. EXP-SUBMITTED","2. EXP-CHECK","3. EXP-ROUTE"]
```

`/bpmncode/api/instances/<instanceId>/path?lang=en` da lo mismo para el visor, con `"waitingNodeId":"EXP-APPROVE"`.

## Errores frecuentes

- **Maven no resuelve el WebJar** sin la exclusión:

  ```
  Could not resolve version conflict among [org.webjars.npm:bpmn-js:jar:18.10.1 -> org.webjars.npm:diagram-js:jar:[15.5.0,16.0.0-0) -> org.webjars.npm:tiny-svg:jar:[4.1.4,5.0.0-0), org.webjars.npm:bpmn-js:jar:18.10.1 -> org.webjars.npm:tiny-svg:jar:[3.1.3,4.0.0-0)]
  ```

- **`/bpmncode/viewer.html` da 404.** Falta `pac.process.web.enabled=true` (o la aplicación no es web servlet).
- **El visor abre, pero el diagrama no se dibuja.** No se encuentra bpmn-js: comprueba el WebJar o
  `pac.process.web.bpmn-js-location` si lo sirves desde otro sitio.
- **Una ruta base mal escrita** (`pac.process.web.base-path=bpmncode/`). No arranca:
  `pac.process.web.base-path must start with '/' and must not end with it, e.g. /bpmncode`.
- **El mapa de calor de `COLLABORATION`** responde 404: ese diagrama no dibuja una única versión.

## Ejercicio

Negocio pregunta cuántas solicitudes esperan a un responsable, sumando las dos versiones de `EXP-CLAIM`. Añade
`GET /statistics/pending-approvals` que lo devuelva.

<details><summary>Solución</summary>

```java
	@GetMapping("/statistics/pending-approvals")
	long pendingApprovals() {
		return Stream.of(1, 2)
				.map(version -> statistics.waitingByNode(ProcessDefinitionRef.of("EXP-CLAIM", version)))
				.mapToLong(waiting -> waiting.getOrDefault("EXP-APPROVE", 0L))
				.sum();
	}
```

Con el import `java.util.stream.Stream`. `waitingByNode` no incluye los nodos sin instancias esperando, de ahí el
`getOrDefault`. Para no fijar las versiones a mano, puedes recorrerlas con
`registry.definitions().versions("EXP-CLAIM")` (cada `ProcessDefinition` tiene su `ref()`).

</details>

## Para saber más

- [Estadísticas de ejecución](../estadisticas-web.md#estadísticas-de-ejecución),
  [`ExecutionHeatmap`](../estadisticas-web.md#executionheatmap) e [`InstancePath`](../estadisticas-web.md#instancepath).
- [Módulo web](../estadisticas-web.md#módulo-web): [activarlo](../estadisticas-web.md#activarlo),
  [rutas](../estadisticas-web.md#rutas), [el visor](../estadisticas-web.md#el-visor) y [textos](../estadisticas-web.md#textos).

---

[← Anterior: BPMN, trazabilidad e i18n](10-bpmn-trazabilidad-i18n.md) | [Índice](README.md) | [Siguiente: Pruebas y producción →](12-pruebas-y-produccion.md)
