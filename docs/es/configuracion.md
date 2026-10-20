# Configuración e integración

> 🇬🇧 [English version](../en/configuration.md)

## Autoconfiguración

`bpmncode` se activa solo al estar en el classpath. Registra cinco autoconfiguraciones en
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

| Autoconfiguración | Beans |
|---|---|
| `ProcessModelAutoConfiguration` | `ProcessDefinitionRegistry`, `MessageContractCatalog`, `TraceabilityReport` |
| `ProcessRuntimeAutoConfiguration` | `ProcessInstanceStore`, `ProcessJournal`, `ProcessInstanceHistory`, `Clock`, `ProcessStoreProperties` |
| `BpmnAutoConfiguration` | `BpmnDiagramService`, `BpmnProperties` |
| `ProcessStatisticsAutoConfiguration` | `ProcessExecutionStatistics` |
| `ProcessWebAutoConfiguration` | módulo web opcional, apagado por defecto: controladores del visor y de la API, `ProcessWebProperties` |

- Todos los beans de la API se declaran con `@Bean` y `@ConditionalOnMissingBean`, sin escaneo de componentes: si la
  aplicación define el suyo, se usa el de la aplicación. Los del módulo web son internos y no se sustituyen.
- `ProcessWebAutoConfiguration` solo se aplica en una aplicación web servlet con `DispatcherServlet` en el classpath y
  `pac.process.web.enabled=true` (véase [Estadísticas y módulo web](estadisticas-web.md#activarlo)).
- `ProcessRuntimeAutoConfiguration` elige el almacén de instancias: JPA si la aplicación tiene un
  `EntityManagerFactory` que gestiona las entidades de la librería, JDBC si tiene un `DataSource` y `spring-jdbc`,
  memoria si no. Las clases JPA y JDBC solo se
  cargan si esas tecnologías están presentes, así que la librería funciona sin ellas (véase
  [Almacenamiento](almacenamiento.md)).
- Con JPA, `ProcessRuntimeAutoConfiguration` se aplica antes que la autoconfiguración JPA de Spring Boot y añade el
  paquete de sus entidades (`io.github.eldiegos.bpmncode.runtime.jpa`) a los paquetes de autoconfiguración, para que
  la unidad de persistencia de la aplicación las gestione.

### Dónde se buscan los procesos

En los paquetes de autoconfiguración de la aplicación (`AutoConfigurationPackages`), es decir, el paquete de la clase
`@SpringBootApplication` y sus subpaquetes, más los que se añadan con `@AutoConfigurationPackage`. Para leer procesos
de otros paquetes, regístralos así o define tu propio `ProcessDefinitionRegistry`:

```java
@Bean
ProcessDefinitionRegistry processDefinitionRegistry(Environment environment, ResourceLoader resources) {
	return new ProcessDefinitionRegistry(AnnotationProcessModelReader.scan(environment::resolvePlaceholders,
			MessageContractLock.fromClasspath(resources), "com.example.orders", "com.example.billing"));
}
```

### Reloj

Las marcas de tiempo de las instancias y la evidencia salen de un `Clock`. Por defecto es
`Clock.systemDefaultZone()`; define tu propio bean `Clock` para fijar la zona o para controlar el tiempo en las
pruebas.

## Propiedades

| Propiedad | Por defecto | Uso |
|---|---|---|
| `pac.process.bpmn.target-namespace` | `https://pac.dev/process` | `targetNamespace` de los BPMN exportados (sin barra final) |
| `pac.process.store.type` | `auto` | almacén de instancias: `auto`, `memory`, `jdbc` o `jpa` |
| `pac.process.store.initialize-schema` | `embedded` | creación de las tablas de los almacenes JDBC y JPA: `embedded` (solo H2), `always` o `never` |
| `pac.process.web.enabled` | `false` | publica el módulo web (visor y API de solo lectura) |
| `pac.process.web.base-path` | `/bpmncode` | ruta del módulo web; empieza por `/` y no termina en `/` |
| `pac.process.web.bpmn-js-location` | `/webjars/bpmn-js/18.10.1/dist` | carpeta `dist` de bpmn-js, que aporta la aplicación |

Los `${…}` de los `@Scheduled` de los `@TimerStartEvent` se resuelven con el `Environment` de la aplicación, así que
el diagrama muestra la planificación configurada.

## Ficheros que aporta la aplicación

| Fichero | Para qué |
|---|---|
| `pac/message-contracts.lock` | huellas congeladas de los contratos de mensaje (véase [Mensajes](mensajes.md#2-esquema-congelado)) |
| `messages*.properties` (o el bundle que uses) | traducciones de las claves `pac.*` (véase [i18n](bpmn-trazabilidad-i18n.md#internacionalización)); también sustituyen los textos `pac.web.*` del módulo web |
| WebJar `org.webjars.npm:bpmn-js` | bpmn-js para el visor del módulo web, si lo activas |

## Dependencias opcionales

La librería solo exige `spring-boot-autoconfigure` y `slf4j-api`. Las demás son opcionales: se usan si la aplicación
ya las tiene.

| Dependencia | Para qué |
|---|---|
| `spring-jdbc` | almacén JDBC |
| `jakarta.persistence-api`, `spring-orm` | almacén JPA |
| `spring-tx` | `@Transactional` en el journal, el historial y las estadísticas |
| `spring-webmvc`, `jakarta.servlet-api` | módulo web |
| `spring-modulith-api` | anotaciones con las que Spring Modulith verifica los límites de la librería |

## Spring Modulith

`bpmncode` no necesita Spring Modulith, pero lo aprovecha si tu aplicación lo usa. Declara sus paquetes como un módulo
(`@ApplicationModule`) con interfaces con nombre (`@NamedInterface`): `annotation`, `model`, `runtime`, `statistics`,
`trace`, `i18n` y `bpmn`, que son su API pública. `model.reader`, `runtime.jpa`, `web` y `processor` no son interfaces
con nombre: son internos y pueden cambiar entre versiones `0.x`.

La dependencia `spring-modulith-api` es opcional: no llega a las aplicaciones que no usan Modulith, y en ellas la JVM
ignora esas anotaciones. Si tu aplicación lo usa y quieres que verifique también que tus módulos solo usan la API
pública de la librería, añade su paquete como base adicional:

```java
@SpringBootApplication
@Modulithic(additionalPackages = "io.github.eldiegos")
public class MyApplication { … }
```

La librería es entonces el módulo `bpmncode`, y tus módulos declaran qué interfaces usan:

```java
@ApplicationModule(allowedDependencies = { "bpmncode :: annotation", "bpmncode :: model", "bpmncode :: runtime" })
package com.example.orders;
```

Si un módulo usa un paquete interno, o una interfaz que no declara, `ApplicationModules.verify()` falla.

Además, `@ApplicationModuleListener` sirve como listener de un `@MessageStartEvent`, y el registro de publicaciones de
Modulith da entrega fiable a los mensajes entre procesos (véase [Mensajes](mensajes.md)).

## Esquema de base de datos

Solo hace falta con los almacenes JDBC y JPA, que usan las mismas tablas. El script, cuándo lo ejecuta la librería y
cómo llevarlo a tus migraciones están en [Almacenamiento](almacenamiento.md#crear-las-tablas).

## Pruebas de la aplicación

- Cualquier `@SpringBootTest` valida el modelo completo al crear el contexto.
- Con `pac.process.store.type=memory` las pruebas no necesitan base de datos.
- Para probar un proceso de punta a punta, ejecútalo y comprueba su evidencia con `ProcessInstanceHistory`:

```java
var instances = history.instances(correlationKey);
assertThat(instances).singleElement().isInstanceOf(ProcessInstanceSummary.Completed.class);
assertThat(history.flowNodeInstances(instances.getFirst().instanceId()))
		.extracting(HistoricFlowNodeInstance::flowNodeId)
		.containsExactly("ORDER-RECEIVED", "CHECK-CREDIT", "SHIP", "SHIPPED");
```

### `ProcessAssertions`

El jar de pruebas de la librería (clasificador `tests`) trae `io.github.eldiegos.bpmncode.test.ProcessAssertions`, con
comprobaciones listas para las pruebas de la aplicación, y los XSD oficiales de OMG de BPMN 2.0 en `/bpmn/xsd`. No
depende de ninguna librería de aserciones: un fallo es un `AssertionError` con el detalle.

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

| Método | Comprueba o devuelve |
|---|---|
| `assertValidBpmn(xml)` | que un documento es BPMN 2.0 válido según los XSD de OMG |
| `assertEveryDiagramIsValidBpmn(diagrams)` | que lo son todos los diagramas de `BpmnDiagramService` (falla con el primero que no) |
| `assertEveryDrawnElementIsDescribed(diagrams)` | que cada elemento dibujado en cada diagrama tiene su `DiagramElement`, es decir, que un visor puede explicar todo lo que se ve |
| `pathOf(history, instanceId)` | los ids de los nodos registrados por una instancia, en orden (repetidos si un bucle o un ciclo los repitió) |

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
		var instance = …;   // ejecuta el proceso
		assertThat(ProcessAssertions.pathOf(history, instance.instanceId()))
				.containsExactly("ORDER-RECEIVED", "CHECK-CREDIT", "SHIP", "SHIPPED");
	}
}
```
