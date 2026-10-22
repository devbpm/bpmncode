# bpmncode

> 🇬🇧 [English version](README.en.md)

**Procesos BPMN 2.0 escritos en Java: el código anotado es la única fuente de verdad.**

`bpmncode` es una librería para aplicaciones Spring Boot que convierte métodos Java anotados en procesos BPMN 2.0. No
hay ficheros `.bpmn` mantenidos a mano ni motor de procesos: el diagrama se deriva del código, el modelo se valida al
compilar y al arrancar, y en ejecución se comprueba que el código sigue el flujo declarado y se guarda la evidencia de
cada paso.

```java
@Process(id = "PER-REGISTRATION", name = "Person registration", version = 1)
public class PersonRegistration {

	@StartEvent(id = "REQUEST-RECEIVED", name = "Request received",
			outgoing = @SequenceFlow(targetRef = "ELIGIBILITY"))
	public Result register(Request request) { … }

	@ExclusiveGateway(id = "ELIGIBILITY", name = "Meets the rules?", businessRuleRefs = "R-PER-001",
			outgoing = { @SequenceFlow(when = "ELIGIBLE", targetRef = "CREATE-PERSON", name = "Yes"),
					@SequenceFlow(when = "NOT_ELIGIBLE", targetRef = "REJECTED", name = "No") })
	Eligibility assess(…) { … }

	@ServiceTask(id = "CREATE-PERSON", name = "Register person", outgoing = @SequenceFlow(targetRef = "COMPLETED"))
	Person create(…) { … }

	@EndEvent(id = "COMPLETED", name = "Registration completed")
	Result complete(…) { … }

	@EndEvent(id = "REJECTED", name = "Registration rejected")
	Result reject(…) { … }
}
```

## Qué ofrece

- **Anotaciones con el vocabulario de BPMN 2.0.2**: `@Process`, `@StartEvent`, `@MessageStartEvent`,
  `@TimerStartEvent`, `@ServiceTask`, `@UserTask`, `@BusinessRuleTask`, `@SendTask`, `@ExclusiveGateway`,
  `@SequenceFlow`, `@EndEvent`, `@MessageEndEvent`, `@ErrorEndEvent`, `@Message`, `@ProcessError`, `@ProcessResource`,
  `@PotentialOwner`, `@HumanPerformer`, `@BusinessRule`…
- **Validación en dos momentos**: un procesador de anotaciones hace fallar `javac` con los errores locales a cada
  elemento, y al arrancar se valida el modelo completo (alcanzabilidad, ciclos sin salida, referencias, ids únicos…).
  Un modelo inválido impide arrancar la aplicación.
- **Ejecución con conformidad, sin motor**: `ProcessJournal` registra cada paso y rechaza los que no siguen un flujo de
  secuencia declarado. Las instancias y su historial se guardan en memoria, sin base de datos, o en la base de datos
  de la aplicación, con JPA o con JDBC, si tiene una.
- **Versiones**: de procesos (conviven varias; cada instancia sigue la suya), de reglas de negocio y de los mensajes
  entre procesos, con contratos congelados por huella de su esquema JSON.
- **Exportación BPMN 2.0 XML** (modelo semántico y BPMN DI con disposición automática), válida contra los XSD de OMG,
  lista para [bpmn.io](https://bpmn.io).
- **Trazabilidad** Proceso → Nodo de flujo → Regla → Código → Mensaje, con catálogos de dónde se usa cada regla y de
  los contratos de mensaje, e **internacionalización** de todos los textos del modelo.
- **Estadísticas de ejecución**: mapa de calor de cada versión de un proceso, camino seguido por cada instancia,
  instancias en espera por nodo y recuentos por versión.
- **Módulo web opcional**, apagado por defecto: visor bpmn-js con la ficha de cada elemento, el camino de una instancia
  y el mapa de calor, más una API de solo lectura.
- **Comprobaciones para tus pruebas** (`ProcessAssertions`, en el jar de pruebas): BPMN válido según los XSD de OMG,
  cada elemento dibujado descrito y camino de una instancia.

## Requisitos

| | Versión |
|---|---|
| Java | 21 o superior (compilada para 21; probada también en 25) |
| Spring Boot | 4.1 |
| Base de datos | opcional: sin ella, las instancias se guardan en memoria; con JPA, o con un `DataSource` y `spring-jdbc`, en dos tablas |

## Instalación

```xml
<dependency>
    <groupId>io.github.eldiegos</groupId>
    <artifactId>bpmncode</artifactId>
    <version>0.1.0</version>
</dependency>
```

Y, para las comprobaciones al compilar, el procesador de anotaciones en el `maven-compiler-plugin`:

```xml
<annotationProcessors>
    <annotationProcessor>io.github.eldiegos.bpmncode.processor.ProcessAnnotationProcessor</annotationProcessor>
</annotationProcessors>
```

La librería se configura sola por autoconfiguración de Spring Boot: descubre los procesos en los paquetes de la
aplicación y ofrece `ProcessDefinitionRegistry`, `ProcessJournal`, `ProcessInstanceHistory`, `TraceabilityReport`,
`MessageContractCatalog`, `ProcessExecutionStatistics` y `BpmnDiagramService` como beans. Solo depende de `spring-boot-autoconfigure` y `slf4j-api`: funciona en una aplicación
sin base de datos, sin JPA y sin Spring Modulith. Si la aplicación ya tiene JPA, o `spring-jdbc` y un `DataSource`,
guarda las instancias en su base de datos ([Almacenamiento](docs/es/almacenamiento.md)). Si usa Spring Modulith,
puede verificar que solo usa la API pública de la librería ([Configuración](docs/es/configuracion.md#spring-modulith)).

## Documentación

¿Empiezas de cero? Sigue el [curso paso a paso](docs/es/curso/README.md), que construye una aplicación completa.

| Guía | Contenido |
|---|---|
| [Primeros pasos](docs/es/primeros-pasos.md) | dependencias, procesador de anotaciones y un proceso completo paso a paso |
| [Anotaciones](docs/es/anotaciones.md) | cada anotación, su elemento BPMN, sus atributos y las reglas que la ligan al código |
| [Validación del modelo](docs/es/validacion.md) | comprobaciones al compilar y al arrancar, y todos los tipos de error |
| [Ejecución y conformidad](docs/es/ejecucion.md) | `ProcessJournal`, instancias, evidencias, bucles, historial, transacciones e idempotencia |
| [Almacenamiento](docs/es/almacenamiento.md) | en memoria, en base de datos (JPA o JDBC) o propio; tablas, esquema, transacciones y cómo probar un almacén propio |
| [Mensajes entre procesos](docs/es/mensajes.md) | versiones selladas, contratos congelados y la cabecera de los mensajes |
| [Versiones](docs/es/versiones.md) | versiones de procesos y de reglas de negocio |
| [BPMN, trazabilidad e i18n](docs/es/bpmn-trazabilidad-i18n.md) | exportación BPMN 2.0, extensiones `pac:`, informe de trazabilidad y traducción de textos |
| [Estadísticas y módulo web](docs/es/estadisticas-web.md) | mapa de calor, camino de una instancia y recuentos; visor bpmn-js y API de solo lectura opcionales |
| [Configuración e integración](docs/es/configuracion.md) | beans y cómo sustituirlos, propiedades, dependencias opcionales, Spring Modulith y pruebas de la aplicación |
| [Arquitectura interna](docs/es/arquitectura.md) | paquetes, API pública e interna, cómo se lee el modelo |
| [Publicación](docs/es/publicacion.md) | cómo se publica una versión en Maven Central |
| [Calidad del código](docs/es/calidad.md) | cobertura con JaCoCo y análisis con SonarQube en un contenedor |

## Construir

```bash
./mvnw verify                              # compila y ejecuta las pruebas
./mvnw -Prelease verify -Dgpg.skip         # además, jar de fuentes y Javadoc
./mvnw -Psonar verify                      # además, análisis con SonarQube (necesita Docker)
```

## Estado

Versión `0.x`: la API puede cambiar entre versiones menores hasta la 1.0.

## Licencia y atribución

Distribuido con la [Licencia Apache 2.0](LICENSE). Puedes usar, modificar y redistribuir `bpmncode`, también en
productos comerciales, siempre que conserves el aviso de copyright y el fichero [`NOTICE`](NOTICE), que atribuye el
proyecto original. Ambos van también dentro del jar, en `META-INF/`. Si lo citas en un trabajo, usa los datos de
[`CITATION.cff`](CITATION.cff) (GitHub los ofrece en «Cite this repository»).
