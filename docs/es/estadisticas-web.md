# Estadísticas y módulo web

> 🇬🇧 [English version](../en/statistics-web.md)

La evidencia que guarda el journal sirve para algo más que auditar: `ProcessExecutionStatistics` la agrega en mapas de
calor, caminos de instancia y recuentos, y el módulo web opcional la pinta sobre el diagrama BPMN en un visor bpmn-js.

## Estadísticas de ejecución

`ProcessExecutionStatistics` (paquete `statistics`) es un bean que registra `ProcessStatisticsAutoConfiguration` en
cualquier aplicación; si no lo inyectas, no se usa. Solo lee, a través de `ProcessInstanceHistory`, y devuelve
identificadores y cifras, nunca textos: cada aplicación los presenta y traduce a su manera (por ejemplo con
[`LabelResolver`](bpmn-trazabilidad-i18n.md#internacionalización)).

| Método | Devuelve |
|---|---|
| `heatmap(ref)` | `Optional<ExecutionHeatmap>`: el mapa de calor de una versión desplegada; vacío si esa versión no lo está |
| `path(instanceId)` | `Optional<InstancePath>`: el camino seguido por una instancia; vacío si no existe |
| `waitingByNode(ref)` | `Map<String, Long>`: instancias activas que esperan en cada nodo de una versión (los nodos sin ninguna no aparecen) |
| `countsByVersion()` | `Map<ProcessDefinitionRef, InstanceCounts>`: activas y completadas de cada versión, ordenadas por proceso y versión; las versiones sin instancias no aparecen (usa `InstanceCounts.NONE`) |

Una versión se calcula con dos consultas al almacén (sus instancias y toda su evidencia, véase
[consultas por versión](almacenamiento.md#un-almacén-propio)), nunca con una por instancia.

```java
var heatmap = statistics.heatmap(ProcessDefinitionRef.of("ORDER", 2)).orElseThrow();
heatmap.instances();                                  // running + completed
heatmap.node("CHECK-CREDIT").map(NodeStatistics::executions);
statistics.path(instanceId).flatMap(InstancePath::waitingFlowNodeId);
```

### `ExecutionHeatmap`

| Componente | Significado |
|---|---|
| `process` | proceso y versión |
| `instances`, `running`, `completed` | instancias de la versión, activas y completadas (`instances = running + completed`) |
| `maxExecutions` | ejecuciones del nodo más ejecutado (0 si no hay ninguna) |
| `nodes` | un `NodeStatistics` por nodo del modelo, en su orden, también los nunca ejecutados |
| `flows` | un `FlowStatistics` por flujo de secuencia del modelo, en su orden |

`NodeStatistics` lleva `flowNodeId`, `executions` (veces que se ejecutó: un bucle o un ciclo lo cuentan varias veces),
`instances` (instancias distintas que pasaron), `share` (porcentaje de instancias de la versión que pasaron, de 0 a
100) y `waiting` (instancias activas que esperan en él). `FlowStatistics` lleva `sequenceFlowId` y `traversals`: un
paso entre dos nodos consecutivos de la evidencia cuenta como recorrido solo si el modelo declara ese flujo, así que
otra vuelta de un bucle estándar no recorre ninguno.

### `InstancePath`

| Componente | Significado |
|---|---|
| `instanceId`, `process` | instancia y versión que ejecuta |
| `running` | si sigue activa |
| `steps` | `PathStep` en orden de secuencia: `order` (desde 1), `flowNodeId`, `loopCounter` y `occurredAt`; un nodo aparece varias veces si un bucle o un ciclo lo repitieron |
| `sequenceFlowIds` | flujos recorridos, en orden |
| `waitingFlowNodeId` | nodo en el que espera si está activa; vacío si está completada |

`InstanceCounts` lleva `running`, `completed` y `total()`.

## Módulo web

Un módulo opcional publica una API de solo lectura y un visor bpmn-js: diagramas, la ficha de cada elemento (qué es, a
qué versión pertenece, qué código y qué reglas lo implementan, qué mensaje consume, publica o envía, con qué error
termina y, en una tarea humana, sus roles), el camino de una instancia y el mapa de calor de una versión, en el idioma
de cada petición. La aplicación no usa sus clases (el paquete `web` no es una interfaz con nombre), solo sus rutas.

### Activarlo

**Está apagado por defecto**, porque expone el código que implementa cada paso y los datos de las instancias.
`ProcessWebAutoConfiguration` solo se aplica si se cumplen las tres condiciones:

- la aplicación es web de tipo servlet;
- `DispatcherServlet` (Spring MVC) está en el classpath;
- `pac.process.web.enabled=true`.

`spring-webmvc` y `jakarta.servlet-api` son dependencias opcionales de la librería: los trae la aplicación (p. ej. con
`spring-boot-starter-webmvc`). bpmn-js también lo aporta la aplicación; por defecto se carga del WebJar:

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

La exclusión es necesaria: solo hace falta la carpeta `dist` que incluye el jar, y sin ella Maven no resuelve las
dependencias transitivas del WebJar (`Could not resolve version conflict among [...] tiny-svg ...`).

```properties
pac.process.web.enabled=true
# opcionales
pac.process.web.base-path=/bpmncode
pac.process.web.bpmn-js-location=/webjars/bpmn-js/18.10.1/dist
```

| Propiedad | Por defecto | Uso |
|---|---|---|
| `pac.process.web.enabled` | `false` | publica el visor y la API |
| `pac.process.web.base-path` | `/bpmncode` | ruta bajo la que se publican; empieza por `/` y no termina en `/` (si no, la aplicación no arranca) |
| `pac.process.web.bpmn-js-location` | `/webjars/bpmn-js/18.10.1/dist` | carpeta `dist` de bpmn-js, absoluta o relativa a la ruta de contexto (no puede estar vacía; se quita la barra final) |

**No añade seguridad.** Si la aplicación tiene Spring Security, estas rutas quedan protegidas por sus reglas como
cualquier otra; si no la tiene, son públicas. Protégelas o limita la activación a los entornos que corresponda.

### Rutas

Todas bajo `<base-path>` (por defecto `/bpmncode`) y de solo lectura (`GET`).

| Ruta | Devuelve |
|---|---|
| `/viewer.html` | el visor |
| `/static/**` | recursos del visor (`viewer.js`, `viewer.css`) |
| `/api/views` | diagramas disponibles (`id` y nombre traducido) |
| `/api/views/{id}` | el BPMN 2.0 XML de un diagrama, con los textos traducidos |
| `/api/views/{id}/elements` | la ficha de cada elemento del diagrama |
| `/api/views/{id}/heatmap` | el mapa de calor de la versión que dibuja el diagrama; 404 en el de colaboración |
| `/api/instances/{id}/path` | el camino de una instancia, sobre el diagrama de su versión |
| `/api/i18n/viewer` | los textos del visor |
| `/api/processes` | `Definitions`: el modelo completo |
| `/api/processes/messages` | los mensajes y sus versiones |
| `/api/processes/traceability` | las entradas de `TraceabilityReport` |
| `/api/processes/instances` | todas las instancias (`ProcessInstanceSummary`) |
| `/api/processes/instances/{id}/evidence` | la evidencia de una instancia (`HistoricFlowNodeInstance`) |

Un diagrama o una instancia desconocidos son 404. Las rutas `/api/views…`, `/api/instances…` y `/api/i18n…` aceptan
`?lang=` y responden con `Content-Language`; las de `/api/processes` son técnicas: identificadores, nombres canónicos y
códigos, sin traducir.

La ficha de una tarea humana lleva sus roles en la lista `roles`, aparte de `events` (en el resto de elementos está
vacía). Cada `@PotentialOwner` y cada `@HumanPerformer` es una entrada, en el orden declarado (primero los propietarios
potenciales):

| Campo | Recurso (`resourceRef`) | Expresión (`expression`) |
|---|---|---|
| `kind` | rol traducido: `pac.web.diagramElement.potentialOwner` o `.humanPerformer` | igual |
| `resourceId` | id del recurso | `null` |
| `name` | nombre traducido del recurso (`pac.resource.<id>.name`) | texto de la expresión |
| `type` | clase Java que declara el recurso | `null` |

El visor los muestra en su propia sección (`pac.web.viewer.details.roles`). Es la descripción del modelo, no la persona
a la que la aplicación ha asignado la tarea.

### El visor

`<base-path>/viewer.html` acepta estos parámetros:

| Parámetro | Efecto |
|---|---|
| `view` | diagrama que se abre, p. ej. `ORDER` o `ORDER-v1` |
| `element` | elemento seleccionado al abrir, con su ficha |
| `instance` | pinta el camino de esa instancia: pasos numerados, flujos recorridos y paso en espera |
| `heatmap=1` | pinta el mapa de calor de la versión (no se combina con `instance`) |
| `lang` | idioma de los textos; si falta, el de la petición |

```
/bpmncode/viewer.html?view=ORDER&heatmap=1&lang=es
/bpmncode/viewer.html?instance=3f2c…&element=CHECK-CREDIT
```

### Textos

Cada texto se busca primero en el `MessageSource` de la aplicación, que así puede sustituir cualquiera, y después en
los bundles de la librería (inglés por defecto y español):

| Bundle | Claves |
|---|---|
| `io/github/eldiegos/bpmncode/web/messages` | `pac.web.*`: visor y fichas, p. ej. `pac.web.diagramElement.publishes`, `.sends`, `.throws` (*Ends with error* / *Termina con error*), `.potentialOwner` (*Potential owner* / *Propietario potencial*) o `.humanPerformer` (*Human performer* / *Persona que la realiza*), y `pac.web.viewer.details.roles` (*Who does it* / *Quién la hace*) |
| `io/github/eldiegos/bpmncode/i18n/messages` | `pac.bpmn.*`: tipos de elemento BPMN (véase [i18n](bpmn-trazabilidad-i18n.md#textos-por-defecto)) |

Los textos del modelo (`pac.process.*`, `pac.flowNode.*`…) salen de la aplicación como en el BPMN exportado, así que
el visor muestra los mismos nombres que el diagrama. Una clave sin traducción en ninguno devuelve el texto canónico.
