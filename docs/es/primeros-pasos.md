# Primeros pasos

> 🇬🇧 [English version](../en/getting-started.md)

Esta guía lleva de una aplicación Spring Boot vacía a un proceso que se valida al arrancar, se ejecuta con conformidad
y se exporta como BPMN 2.0.

¿Prefieres aprender paso a paso, con un ejemplo que crece? Sigue el [curso](curso/README.md).

## 1. Dependencias

```xml
<dependency>
    <groupId>io.github.eldiegos</groupId>
    <artifactId>bpmncode</artifactId>
    <version>0.1.0</version>
</dependency>
```

No hace falta nada más: sin base de datos, `bpmncode` guarda las instancias en memoria. Si quieres que sobrevivan a un
reinicio, añade una base de datos (cualquier `spring-boot-starter-jdbc` o `-data-jpa` con su driver) y la librería las
guardará en ella sin más configuración; véase el paso 3.

## 2. Procesador de anotaciones

Desde JDK 23, `javac` ya no ejecuta por su cuenta los procesadores que encuentra en el classpath (JDK 21 y 22 aún lo
hacen, pero avisan de que dejará de hacerlo). Nómbralo siempre:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessors>
            <annotationProcessor>io.github.eldiegos.bpmncode.processor.ProcessAnnotationProcessor</annotationProcessor>
        </annotationProcessors>
    </configuration>
</plugin>
```

No es obligatorio, pero con él el IDE y `javac` marcan en su sitio los errores que, sin él, solo saldrían al arrancar
(véase [Validación del modelo](validacion.md)). Si la aplicación ya usa otros procesadores (Lombok, MapStruct…), hay
que listarlos todos en `annotationProcessors`.

## 3. Dónde se guardan las instancias

| La aplicación tiene… | Las instancias se guardan… |
|---|---|
| nada | en memoria (se pierden al parar) |
| un `DataSource` y `spring-jdbc` | en las tablas `pac_process_instance` y `pac_flow_node_instance`, con JDBC; con H2 la librería las crea sola |
| JPA (p. ej. `spring-boot-starter-data-jpa`) | en las mismas tablas, como entidades de la unidad de persistencia de la aplicación |

Para producción con otra base de datos, crea las tablas con tu herramienta de migraciones (o deja que las genere
Hibernate, si usas JPA). Los detalles, el script y
cómo aportar un almacén propio están en [Almacenamiento](almacenamiento.md).

## 4. Un proceso

Un proceso es una clase con `@Process`. Cada nodo de flujo es un método anotado, y los flujos de secuencia se declaran
por el id del nodo de destino (`targetRef`), así que el orden de los métodos no importa.

```java
@Service
@Process(id = "ORDER", name = "Order handling", version = 1,
		documentation = "Accepts an order if the customer has credit and ships it.")
public class OrderHandling {

	static final ProcessDefinitionRef KEY = ProcessDefinitionRef.of("ORDER", 1);

	enum Credit { ENOUGH, NOT_ENOUGH }

	private final ProcessJournal journal;

	OrderHandling(ProcessJournal journal) {
		this.journal = journal;
	}

	@StartEvent(id = "ORDER-RECEIVED", name = "Order received",
			outgoing = @SequenceFlow(targetRef = "CHECK-CREDIT"))
	public void receive(Order order) {
		var instance = journal.startProcessInstance(KEY, CorrelationKey.newKey(), FlowNodeOutcome.of("RECEIVED"));
		journal.setBusinessKey(instance, "order:" + order.id());
		switch (checkCredit(instance, order)) {
			case ENOUGH -> {
				ship(instance, order);
				journal.reachEndEvent(instance, "SHIPPED", FlowNodeOutcome.of("SHIPPED"));
			}
			case NOT_ENOUGH -> journal.reachEndEvent(instance, "REJECTED", FlowNodeOutcome.of("NO_CREDIT"));
		}
	}

	@ExclusiveGateway(id = "CHECK-CREDIT", name = "Enough credit?", businessRuleRefs = CreditRule.ID,
			outgoing = {
					@SequenceFlow(when = "ENOUGH", targetRef = "SHIP", name = "Yes"),
					@SequenceFlow(when = "NOT_ENOUGH", targetRef = "REJECTED", name = "No") })
	Credit checkCredit(ProcessInstanceRef instance, Order order) {
		var credit = CreditRule.allows(order) ? Credit.ENOUGH : Credit.NOT_ENOUGH;
		journal.selectSequenceFlow(instance, "CHECK-CREDIT", credit);
		return credit;
	}

	@ServiceTask(id = "SHIP", name = "Ship order", outgoing = @SequenceFlow(targetRef = "SHIPPED"))
	void ship(ProcessInstanceRef instance, Order order) {
		// … lógica de negocio …
		journal.completeActivity(instance, "SHIP", FlowNodeOutcome.of("SHIPPED"));
	}

	@EndEvent(id = "SHIPPED", name = "Order shipped")
	void shipped() {
	}

	@EndEvent(id = "REJECTED", name = "Order rejected")
	void rejected() {
	}
}
```

La regla que referencia el gateway se declara donde está su código:

```java
public final class CreditRule {

	public static final String ID = "R-CREDIT-001";

	@BusinessRule(id = ID, name = "Order within credit limit")
	public static boolean allows(Order order) { … }
}
```

Dos ideas que conviene tener claras desde el principio:

- **Las anotaciones describen; el journal registra.** Las anotaciones declaran el modelo (lo que se valida y se exporta).
  Las llamadas a `ProcessJournal` dicen qué ha pasado en cada instancia, y el journal comprueba que encaja con el
  modelo. Un método anotado que no llama al journal no deja evidencia.
- **El gateway devuelve un `enum`** con una constante por flujo de salida (`when`). Así la rama que se toma está ligada
  al código y no a un texto.

## 5. Arrancar

Al arrancar, `bpmncode`:

1. busca las clases con anotaciones de proceso en los paquetes de la aplicación (los de `@SpringBootApplication`);
2. construye y valida el modelo completo; si hay errores, lanza `ProcessModelException` con **todos** ellos y la
   aplicación no arranca;
3. registra los beans `ProcessDefinitionRegistry`, `ProcessJournal`, `ProcessInstanceHistory`, `TraceabilityReport`,
   `MessageContractCatalog`, `ProcessExecutionStatistics` y `BpmnDiagramService`.

## 6. Ver el diagrama

```java
@RestController
class DiagramController {

	private final BpmnDiagramService diagrams;

	DiagramController(BpmnDiagramService diagrams) {
		this.diagrams = diagrams;
	}

	@GetMapping(value = "/bpmn/{id}", produces = MediaType.APPLICATION_XML_VALUE)
	String bpmn(@PathVariable String id) {
		var diagram = diagrams.diagram(id).orElseThrow();
		return diagrams.xml(diagram, "en", LabelResolver.CANONICAL);
	}
}
```

El XML se abre tal cual en [bpmn.io](https://demo.bpmn.io) o con `bpmn-js` en la propia aplicación. Véase
[BPMN, trazabilidad e i18n](bpmn-trazabilidad-i18n.md).

Si no quieres escribir el controlador, el módulo web de la librería trae uno listo, con un visor bpmn-js, la ficha de
cada elemento, el camino de cada instancia y el mapa de calor de cada versión. Está apagado por defecto: actívalo con
`pac.process.web.enabled=true`, añade el WebJar `org.webjars.npm:bpmn-js` y abre `/bpmncode/viewer.html`. Como expone
el código y los datos de las instancias, protégelo con la seguridad de tu aplicación. Véase
[Estadísticas y módulo web](estadisticas-web.md#módulo-web).

## 7. Consultar las instancias

```java
history.instances();                       // todas, activas y completadas
history.flowNodeInstances(instanceId);     // la evidencia, paso a paso
```

Véase [Ejecución y conformidad](ejecucion.md).

## Siguientes pasos

- Iniciar un proceso con un mensaje de otro o con un temporizador: [Anotaciones](anotaciones.md) y
  [Mensajes entre procesos](mensajes.md).
- Tareas humanas que esperan días: [Ejecución](ejecucion.md#tareas-que-esperan).
- Publicar una versión 2 del proceso sin romper las instancias en curso: [Versiones](versiones.md).
