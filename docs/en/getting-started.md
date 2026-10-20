# Getting started

> 🇪🇸 [Versión en español](../es/primeros-pasos.md)

This guide takes you from an empty Spring Boot application to a process that is validated at startup, runs with
conformance checking and is exported as BPMN 2.0.

Prefer to learn step by step, with an example that grows? Follow the [course](course/README.md).

## 1. Dependencies

```xml
<dependency>
    <groupId>io.github.eldiegos</groupId>
    <artifactId>bpmncode</artifactId>
    <version>0.1.0</version>
</dependency>
```

Nothing else is needed: without a database, `bpmncode` keeps instances in memory. If you want them to survive a
restart, add a database (any `spring-boot-starter-jdbc` or `-data-jpa` with its driver) and the library will store
them there with no further configuration; see step 3.

## 2. Annotation processor

Since JDK 23, `javac` no longer runs on its own the processors it finds on the classpath (JDK 21 and 22 still do, but
warn that they will stop). Always name it:

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

It is not mandatory, but with it the IDE and `javac` flag in place the errors that, without it, would only show up at
startup (see [Model validation](validation.md)). If the application already uses other processors (Lombok,
MapStruct…), list them all in `annotationProcessors`.

## 3. Where instances are stored

| The application has… | Instances are stored… |
|---|---|
| nothing | in memory (lost on shutdown) |
| a `DataSource` and `spring-jdbc` | in the tables `pac_process_instance` and `pac_flow_node_instance`, with JDBC; with H2 the library creates them itself |
| JPA (e.g. `spring-boot-starter-data-jpa`) | in the same tables, as entities of the application's persistence unit |

For production with another database, create the tables with your migration tool (or let Hibernate generate them, if
you use JPA). The details, the script and how to provide a custom store are in [Storage](storage.md).

## 4. A process

A process is a class with `@Process`. Each flow node is an annotated method, and sequence flows are declared by the id
of the target node (`targetRef`), so the order of the methods does not matter.

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
		// … business logic …
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

The rule referenced by the gateway is declared where its code is:

```java
public final class CreditRule {

	public static final String ID = "R-CREDIT-001";

	@BusinessRule(id = ID, name = "Order within credit limit")
	public static boolean allows(Order order) { … }
}
```

Two ideas worth having clear from the start:

- **Annotations describe; the journal records.** The annotations declare the model (what is validated and exported).
  The calls to `ProcessJournal` state what has happened in each instance, and the journal checks that it fits the
  model. An annotated method that does not call the journal leaves no evidence.
- **The gateway returns an `enum`** with one constant per outgoing flow (`when`). That way the branch taken is bound
  to the code and not to a string.

## 5. Starting up

At startup, `bpmncode`:

1. looks for classes with process annotations in the application's packages (those of `@SpringBootApplication`);
2. builds and validates the full model; if there are errors, it throws `ProcessModelException` with **all** of them
   and the application does not start;
3. registers the beans `ProcessDefinitionRegistry`, `ProcessJournal`, `ProcessInstanceHistory`, `TraceabilityReport`,
   `MessageContractCatalog`, `ProcessExecutionStatistics` and `BpmnDiagramService`.

## 6. Viewing the diagram

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

The XML opens as is in [bpmn.io](https://demo.bpmn.io) or with `bpmn-js` in the application itself. See
[BPMN, traceability and i18n](bpmn-traceability-i18n.md).

If you would rather not write the controller, the library's web module ships a ready-made one, with a bpmn-js viewer,
the card of each element, the path of each instance and the heatmap of each version. It is off by default: enable it
with `pac.process.web.enabled=true`, add the WebJar `org.webjars.npm:bpmn-js` and open `/bpmncode/viewer.html`. Since
it exposes the code and the instances' data, protect it with your application's security. See
[Statistics and web module](statistics-web.md#web-module).

## 7. Querying instances

```java
history.instances();                       // all of them, active and completed
history.flowNodeInstances(instanceId);     // the evidence, step by step
```

See [Execution and conformance](execution.md).

## Next steps

- Start a process with a message from another one or with a timer: [Annotations](annotations.md) and
  [Messages between processes](messages.md).
- Human tasks that wait for days: [Execution](execution.md#waiting-tasks).
- Publish version 2 of the process without breaking running instances: [Versions](versions.md).
