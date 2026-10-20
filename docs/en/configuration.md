# Configuration and integration

> 🇪🇸 [Versión en español](../es/configuracion.md)

## Auto-configuration

`bpmncode` activates itself just by being on the classpath. It registers five auto-configurations in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

| Auto-configuration | Beans |
|---|---|
| `ProcessModelAutoConfiguration` | `ProcessDefinitionRegistry`, `MessageContractCatalog`, `TraceabilityReport` |
| `ProcessRuntimeAutoConfiguration` | `ProcessInstanceStore`, `ProcessJournal`, `ProcessInstanceHistory`, `Clock`, `ProcessStoreProperties` |
| `BpmnAutoConfiguration` | `BpmnDiagramService`, `BpmnProperties` |
| `ProcessStatisticsAutoConfiguration` | `ProcessExecutionStatistics` |
| `ProcessWebAutoConfiguration` | optional web module, off by default: viewer and API controllers, `ProcessWebProperties` |

- All API beans are declared with `@Bean` and `@ConditionalOnMissingBean`, without component scanning: if the
  application defines its own, the application's is used. Those of the web module are internal and not replaceable.
- `ProcessWebAutoConfiguration` only applies in a servlet web application with `DispatcherServlet` on the classpath
  and `pac.process.web.enabled=true` (see [Statistics and web module](statistics-web.md#enabling-it)).
- `ProcessRuntimeAutoConfiguration` chooses the instance store: JPA if the application has an
  `EntityManagerFactory` that manages the library's entities, JDBC if it has a `DataSource` and `spring-jdbc`, memory
  otherwise. The JPA and JDBC classes
  are only loaded if those technologies are present, so the library works without them (see
  [Storage](storage.md)).
- With JPA, `ProcessRuntimeAutoConfiguration` runs before Spring Boot's JPA auto-configuration and adds the package
  of its entities (`io.github.eldiegos.bpmncode.runtime.jpa`) to the auto-configuration packages, so that the
  application's persistence unit manages them.

### Where processes are looked up

In the application's auto-configuration packages (`AutoConfigurationPackages`), that is, the package of the
`@SpringBootApplication` class and its subpackages, plus any added with `@AutoConfigurationPackage`. To read processes
from other packages, register them that way or define your own `ProcessDefinitionRegistry`:

```java
@Bean
ProcessDefinitionRegistry processDefinitionRegistry(Environment environment, ResourceLoader resources) {
	return new ProcessDefinitionRegistry(AnnotationProcessModelReader.scan(environment::resolvePlaceholders,
			MessageContractLock.fromClasspath(resources), "com.example.orders", "com.example.billing"));
}
```

### Clock

The timestamps of instances and evidence come from a `Clock`. By default it is `Clock.systemDefaultZone()`; define
your own `Clock` bean to fix the time zone or to control time in tests.

## Properties

| Property | Default | Use |
|---|---|---|
| `pac.process.bpmn.target-namespace` | `https://pac.dev/process` | `targetNamespace` of the exported BPMN (no trailing slash) |
| `pac.process.store.type` | `auto` | instance store: `auto`, `memory`, `jdbc` or `jpa` |
| `pac.process.store.initialize-schema` | `embedded` | creation of the tables of the JDBC and JPA stores: `embedded` (H2 only), `always` or `never` |
| `pac.process.web.enabled` | `false` | publishes the web module (viewer and read-only API) |
| `pac.process.web.base-path` | `/bpmncode` | path of the web module; starts with `/` and does not end with `/` |
| `pac.process.web.bpmn-js-location` | `/webjars/bpmn-js/18.10.1/dist` | bpmn-js `dist` folder, provided by the application |

The `${…}` placeholders in the `@Scheduled` of `@TimerStartEvent` methods are resolved with the application's
`Environment`, so the diagram shows the configured schedule.

## Files provided by the application

| File | Purpose |
|---|---|
| `pac/message-contracts.lock` | frozen fingerprints of the message contracts (see [Messages](messages.md#2-frozen-schema)) |
| `messages*.properties` (or whichever bundle you use) | translations of the `pac.*` keys (see [i18n](bpmn-traceability-i18n.md#internationalization)); they also replace the `pac.web.*` texts of the web module |
| WebJar `org.webjars.npm:bpmn-js` | bpmn-js for the web module's viewer, if you enable it |

## Optional dependencies

The library only requires `spring-boot-autoconfigure` and `slf4j-api`. The others are optional: they are used if the
application already has them.

| Dependency | Purpose |
|---|---|
| `spring-jdbc` | JDBC store |
| `jakarta.persistence-api`, `spring-orm` | JPA store |
| `spring-tx` | `@Transactional` on the journal, the history and the statistics |
| `spring-webmvc`, `jakarta.servlet-api` | web module |
| `spring-modulith-api` | annotations with which Spring Modulith verifies the library's boundaries |

## Spring Modulith

`bpmncode` does not need Spring Modulith, but takes advantage of it if your application uses it. It declares its
packages as a module (`@ApplicationModule`) with named interfaces (`@NamedInterface`): `annotation`, `model`,
`runtime`, `statistics`, `trace`, `i18n` and `bpmn`, which are its public API. `model.reader`, `runtime.jpa`, `web` and
`processor` are not named interfaces: they are internal and may change between `0.x` versions.

The `spring-modulith-api` dependency is optional: it does not reach applications that do not use Modulith, and in
them the JVM ignores those annotations. If your application uses it and you want it to also verify that your modules
only use the library's public API, add its package as an additional base:

```java
@SpringBootApplication
@Modulithic(additionalPackages = "io.github.eldiegos")
public class MyApplication { … }
```

The library is then the `bpmncode` module, and your modules declare which interfaces they use:

```java
@ApplicationModule(allowedDependencies = { "bpmncode :: annotation", "bpmncode :: model", "bpmncode :: runtime" })
package com.example.orders;
```

If a module uses an internal package, or an interface it does not declare, `ApplicationModules.verify()` fails.

In addition, `@ApplicationModuleListener` works as the listener of a `@MessageStartEvent`, and Modulith's event
publication registry provides reliable delivery for messages between processes (see [Messages](messages.md)).

## Database schema

Only needed with the JDBC and JPA stores, which use the same tables. The script, when the library runs it and how to
move it into your migrations are described in [Storage](storage.md#creating-the-tables).

## Application tests

- Any `@SpringBootTest` validates the full model when the context is created.
- With `pac.process.store.type=memory` tests need no database.
- To test a process end to end, run it and check its evidence with `ProcessInstanceHistory`:

```java
var instances = history.instances(correlationKey);
assertThat(instances).singleElement().isInstanceOf(ProcessInstanceSummary.Completed.class);
assertThat(history.flowNodeInstances(instances.getFirst().instanceId()))
		.extracting(HistoricFlowNodeInstance::flowNodeId)
		.containsExactly("ORDER-RECEIVED", "CHECK-CREDIT", "SHIP", "SHIPPED");
```

### `ProcessAssertions`

The library's test jar (classifier `tests`) ships `io.github.eldiegos.bpmncode.test.ProcessAssertions`, with
ready-made checks for the application's tests, and the official OMG BPMN 2.0 XSDs under `/bpmn/xsd`. It depends on no
assertion library: a failure is an `AssertionError` with the details.

```xml
<dependency>
    <groupId>io.github.eldiegos</groupId>
    <artifactId>bpmncode</artifactId>
    <version>0.1.0</version>
    <classifier>tests</classifier>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

| Method | Checks or returns |
|---|---|
| `assertValidBpmn(xml)` | that a document is valid BPMN 2.0 according to the OMG XSDs |
| `assertEveryDiagramIsValidBpmn(diagrams)` | that every diagram of `BpmnDiagramService` is (fails with the first one that is not) |
| `assertEveryDrawnElementIsDescribed(diagrams)` | that every element drawn in every diagram has its `DiagramElement`, that is, that a viewer can explain everything it shows |
| `pathOf(history, instanceId)` | the ids of the nodes recorded by an instance, in order (repeated if a loop or a cycle repeated them) |

```java
@SpringBootTest
class ProcessModelTests {

	@Autowired BpmnDiagramService diagrams;
	@Autowired ProcessInstanceHistory history;

	@Test
	void theDiagramsAreValidBpmn() {
		ProcessAssertions.assertEveryDiagramIsValidBpmn(diagrams);
		ProcessAssertions.assertEveryDrawnElementIsDescribed(diagrams);
	}

	@Test
	void anOrderWithCreditIsShipped() {
		var instance = …;   // run the process
		assertThat(ProcessAssertions.pathOf(history, instance.instanceId()))
				.containsExactly("ORDER-RECEIVED", "CHECK-CREDIT", "SHIP", "SHIPPED");
	}
}
```
