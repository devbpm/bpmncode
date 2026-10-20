# Arquitectura interna

> 🇬🇧 [English version](../en/architecture.md)

## Paquetes

Todo vive bajo `io.github.eldiegos.bpmncode`. Los paquetes marcados como públicos forman la API y cada uno es una
interfaz con nombre del módulo de Spring Modulith `bpmncode`; los internos pueden cambiar entre versiones `0.x`.

| Paquete | API | Contenido |
|---|---|---|
| `annotation` | pública | las anotaciones: `@Process`, `@ProcessPart`, `@StartEvent`, `@MessageStartEvent`, `@TimerStartEvent`, `@ServiceTask`, `@UserTask`, `@BusinessRuleTask`, `@SendTask`, `@StandardLoop`, `@PotentialOwner`, `@HumanPerformer`, `@ExclusiveGateway`, `@SequenceFlow`, `@EndEvent`, `@MessageEndEvent`, `@ErrorEndEvent`, `@Message`, `@MessageVersion`, `@ProcessError`, `@ProcessResource`, `@BusinessRule` |
| `model` | pública | modelo semántico validado e inmutable: `Definitions` (≈ `bpmn:definitions`), `ProcessDefinition`, `ProcessDefinitionRef`, `FlowNode` (`Event` → `StartEvent`/`EndEvent`, `Activity` → `Task` → `ServiceTask`/`UserTask`/`BusinessRuleTask`/`SendTask`, `Gateway` → `ExclusiveGateway`), `SequenceFlow`, `StandardLoopCharacteristics`, `EventDefinition` (`MessageEventDefinition`, `TimerEventDefinition`, `ErrorEventDefinition`), `MessageFlow`, `Message`, `MessageVersionDefinition`, `MessageSchema`, `MessageContractLock`, `MessageContractCatalog`, `ErrorDefinition`, `ResourceDefinition`, `ResourceRole` (`Reference`, `Expression`), `BusinessRuleDefinition`, `BusinessRuleRef`, `BusinessRuleUsage`, `ModelViolation`, `ViolationKind`, `ProcessDefinitionRegistry` |
| `model.reader` | interna | `AnnotationProcessModelReader` y sus fases |
| `runtime` | pública | `ProcessJournal`, `ProcessInstanceHistory`, `ProcessInstanceRef`, `CorrelationKey`, `ProcessMessage`, `MessageHeader`, `MessageId`, `ExchangedMessage`, `FlowNodeOutcome`, `HistoricFlowNodeInstance`, `ProcessInstanceSummary`, `ProcessStart`; el almacén: `ProcessInstanceStore`, `ProcessInstanceRecord`, `FlowNodeInstanceRecord`, `InMemoryProcessInstanceStore`, `JdbcProcessInstanceStore`, `ProcessStoreProperties`, `StoredReferences`; excepciones `ProcessConformanceException`, `DuplicateProcessInstanceException`, `ProcessInstanceConflictException` |
| `runtime.jpa` | interna | `JpaProcessInstanceStore` y sus dos entidades; solo se carga si la aplicación tiene JPA |
| `statistics` | pública | `ProcessExecutionStatistics`, `ExecutionHeatmap`, `InstancePath`, `InstanceCounts`, `ProcessStatisticsAutoConfiguration` |
| `trace` | pública | `TraceabilityReport`, `TraceabilityEntry` |
| `i18n` | pública | `LabelResolver`, `MessageSourceLabelResolver` |
| `bpmn` | pública | `BpmnDiagramService`, `BpmnDiagram`, `DiagramElement`, `BpmnXmlExporter`, `BpmnProperties`; escritores y disposición internos |
| `web` | interna | módulo web opcional: `ProcessWebAutoConfiguration`, `ProcessWebProperties`, los controladores del visor y de la API y el visor (`viewer.html`, `static/`); solo se carga si la aplicación es web con Spring MVC y lo activa |
| `processor` | la usa `javac` | `ProcessAnnotationProcessor` y sus reglas |

Dependencias entre paquetes:

- `annotation` no depende de nada;
- `model.reader` lee `annotation` y produce `model`;
- `runtime`, `trace`, `i18n` y `bpmn` usan `model`, y `bpmn` también usa `i18n`;
- `runtime.jpa` implementa el almacén de `runtime`;
- `statistics` agrega la evidencia de `runtime` sobre `model`;
- `web` solo usa la API pública: `model`, `runtime`, `statistics`, `trace`, `i18n` y `bpmn`;
- la autoconfiguración del modelo (`ProcessModelAutoConfiguration`) une `model.reader` y `trace`;
- `processor` no depende de ninguno: reconoce las anotaciones por su nombre.

## Del código al modelo

`ProcessModelAutoConfiguration` crea `ProcessDefinitionRegistry` con `AnnotationProcessModelReader.scan`, que:

1. **Busca las clases** (`AnnotatedClassScanner`): carga sin inicializar todos los tipos de los paquetes de la
   aplicación y se queda con los que tienen anotaciones del metamodelo, también en métodos privados y en clases que
   olvidaron su `@Process`, para poder avisar.
2. **Lee las declaraciones**, cada una con su propio componente: mensajes y sus versiones, con el esquema JSON y la
   comprobación del fichero de contratos (`DeclaredMessages`, `MessageSchemaWriter`); errores (`DeclaredErrors`);
   recursos y sus parámetros (`DeclaredResources`); reglas (`DeclaredBusinessRules`); procesos y partes
   (`DeclaredProcesses`, `ProcessMembership`).
3. **Convierte cada método anotado en un nodo** (`FlowNodeParser`), con el principio *parse, don't validate*: o
   produce un `FlowNode` válido o informa del problema.
4. **Construye el grafo** de cada versión de cada proceso (`ProcessGraphBuilder`): un evento de inicio, destinos que
   existen, alcanzabilidad y camino hasta un evento de fin.
5. **Enlaza los mensajes** publicados con los procesos que inician (`MessageFlowLinker`).
6. Si `ModelDiagnostics` ha reunido algún problema, lanza `ProcessModelException` con todos. Si no, devuelve
   `Definitions`.

Las clases se procesan ordenadas por nombre y todas las listas del modelo están ordenadas, así que el modelo, el BPMN
y las huellas son deterministas.

## Del modelo al BPMN

`BpmnXmlExporter` calcula el alcance del diagrama (`DiagramScope`: qué procesos y versiones, qué piscinas colapsadas),
escribe el modelo semántico (`SemanticModelWriter`) y después el intercambio de diagramas (`DiagramInterchangeWriter`).
La disposición asigna capas de izquierda a derecha (`LayerAssignment`), calcula rectángulos con el tamaño BPMN de cada
tipo de nodo (`ProcessLayout`) y traza los flujos con segmentos ortogonales (`EdgeRouting`).

## Ejecución

`ProcessJournal` carga la instancia del almacén (`ProcessInstanceRecord`) en un `ProcessInstance` interno, que guarda el
token y el estado y comprueba cada avance (`ProcessInstance.checkAdvance`) contra la `ProcessDefinition` de su versión.
Si el avance es válido, el journal guarda el nuevo estado con bloqueo optimista y añade un `FlowNodeInstanceRecord`; si
no, no guarda nada. La lectura pasa siempre por `ProcessInstanceHistory`, que ordena y devuelve records inmutables.

El almacén (`ProcessInstanceStore`) no sabe nada de conformidad: solo guarda y lee. Así la misma lógica funciona en
memoria, con JDBC, con JPA o con un almacén de la aplicación. La autoconfiguración solo carga las clases JDBC si
`spring-jdbc` está en el classpath, y las JPA si lo están JPA y `spring-orm`. `JpaEntityPackageRegistrar` añade el
paquete `runtime.jpa` a los paquetes de autoconfiguración, sin cargar ninguna clase JPA, para que la unidad de
persistencia de la aplicación gestione las entidades del almacén.

## Decisiones de diseño

- **Sin motor**: el control de flujo es Java; la librería registra y comprueba. No hay tokens paralelos, eventos
  intermedios ni temporizadores propios.
- **El código es la única fuente**: nada se declara dos veces. El BPMN, la trazabilidad y la documentación se derivan
  de las anotaciones.
- **Fallar pronto**: mejor en `javac` que al arrancar, y mejor al arrancar que en ejecución.
- **Vocabulario BPMN**: los nombres de anotaciones, atributos y tipos del modelo son los de BPMN 2.0.2; lo que BPMN no
  define lleva el prefijo `pac:`.
- **Dependencias mínimas**: solo `spring-boot-autoconfigure` y `slf4j-api`. `spring-jdbc`, `jakarta.persistence-api`,
  `spring-orm`, `spring-tx`, `spring-webmvc` y `jakarta.servlet-api` son opcionales: se usan si la aplicación ya los
  tiene. `spring-modulith-api` también es
  opcional: solo aporta las anotaciones con las que Modulith verifica los límites de la librería. Ni JPA ni base de
  datos obligatorias.

## Pruebas de la librería

Las pruebas usan sus propios procesos de ejemplo (`src/test/java/fixture/process`): `ValidProcesses`,
`BrokenProcesses` (errores deliberados, por eso la librería compila sus fuentes con `<proc>none</proc>`) y
`VersionedMessages`. El BPMN exportado se valida contra los XSD de OMG (`src/test/resources/bpmn/xsd`), y el
procesador de anotaciones se prueba compilando fuentes con la API `javax.tools`. `ProcessInstanceStoreContract` reúne
las pruebas del journal que debe pasar cualquier almacén; la extienden `InMemoryProcessInstanceStoreTests` y
`JdbcProcessInstanceStoreTests` (sobre H2), y se publica en el jar de pruebas (clasificador `tests`) para probar
almacenes propios. `JpaProcessInstanceStoreTests` prueba el almacén JPA con Hibernate y H2 en un contexto de Spring, y
`ProcessRuntimeAutoConfigurationTests` comprueba qué almacén se elige con y sin `DataSource`, sin `spring-jdbc`, con un
almacén propio y con `pac.process.store.type`. `ProcessExecutionStatisticsTests` prueba las estadísticas y
`ProcessWebAutoConfigurationTests`, cuándo se activa el módulo web y sus rutas. El jar de pruebas lleva también
`ProcessAssertions` y los XSD de OMG, para las pruebas de las aplicaciones (véase
[Configuración](configuracion.md#processassertions)).
