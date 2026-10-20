# Almacenamiento

> 🇬🇧 [English version](../en/storage.md)

`ProcessJournal` y `ProcessInstanceHistory` guardan y leen las instancias a través de un `ProcessInstanceStore`. La
librería trae tres implementaciones y elige una sola según lo que tenga la aplicación. La aplicación puede aportar la
suya.

| Almacén | Cuándo se usa | Necesita |
|---|---|---|
| `JpaProcessInstanceStore` | la aplicación tiene JPA (un `EntityManagerFactory` que gestiona las entidades de la librería), o lo pide `pac.process.store.type=jpa` | un `EntityManagerFactory` y `spring-orm`; ambos vienen con `spring-boot-starter-data-jpa` |
| `JdbcProcessInstanceStore` | la aplicación tiene un `DataSource` y `spring-jdbc` en el classpath, pero no JPA, o lo pide `pac.process.store.type=jdbc` | un `DataSource`; `spring-jdbc` ya viene con `spring-boot-starter-jdbc` o `spring-boot-starter-data-jpa` |
| `InMemoryProcessInstanceStore` | la aplicación no tiene base de datos, o lo pide `pac.process.store.type=memory` | nada |
| el tuyo | defines un bean `ProcessInstanceStore` | lo que use tu implementación |

Sea cual sea el almacén, el journal aplica las mismas reglas: una instancia por proceso y correlación, bloqueo
optimista y evidencia que solo se añade.

## Elegir el almacén

```properties
# auto (por defecto): JPA si hay EntityManagerFactory y gestiona las entidades; si no, JDBC si hay DataSource y spring-jdbc; si no, memoria
pac.process.store.type=auto
# memory: siempre en memoria, aunque haya base de datos
# jdbc: siempre JDBC; la aplicación no arranca si no hay DataSource o falta spring-jdbc
# jpa: siempre JPA; la aplicación no arranca si no hay EntityManagerFactory o falta spring-orm
```

Al arrancar se registra cuál se usa:

```
Process instances are stored with JpaProcessInstanceStore
```

## En memoria

Pensado para aplicaciones sin base de datos, pruebas, demostraciones o herramientas que solo ejecutan procesos
mientras están en marcha.

- No necesita ninguna dependencia: ni base de datos, ni JPA, ni Spring Transaction.
- Es seguro entre hilos.
- **Todo se pierde al parar la aplicación.**
- **No es transaccional**: si tu código lanza una excepción después de un paso del journal, ese paso queda registrado
  aunque tu transacción (si la tienes) se deshaga. Si necesitas que la evidencia y tus datos se confirmen o deshagan
  juntos, usa un almacén transaccional.

## En una base de datos

Los almacenes JPA y JDBC usan las mismas dos tablas, así que una aplicación puede pasar de uno a otro sin migrar
datos.

### Con JPA

`JpaProcessInstanceStore` (paquete `io.github.eldiegos.bpmncode.runtime.jpa`) guarda las instancias y la evidencia
como dos entidades JPA de la librería, en la unidad de persistencia de la aplicación: mismo proveedor, mismo dialecto y
mismo contexto de persistencia que tus entidades.

- **Entidades.** La autoconfiguración añade el paquete `io.github.eldiegos.bpmncode.runtime.jpa` a los paquetes de
  autoconfiguración de la aplicación, así que JPA encuentra las entidades sin configurar nada. Solo lo hace si el
  almacén es `auto` o `jpa`; con `memory` o `jdbc` las entidades no forman parte de la unidad de persistencia. Si la
  aplicación declara `@EntityScan`, este sustituye la búsqueda por defecto: incluye el paquete de la librería.

  ```java
  @EntityScan({ "com.example.orders", "io.github.eldiegos.bpmncode.runtime.jpa" })
  ```

  Si la unidad de persistencia no gestiona las entidades, con `auto` se registra un aviso y se usa el almacén JDBC (o
  la memoria); con `jpa` la aplicación no arranca.
- **Transacción obligatoria para escribir.** El journal la abre con `@Transactional` (Spring Transaction viene con
  `spring-boot-starter-data-jpa`). Escribir sin transacción falla con `TransactionRequiredException` en lugar de
  perder el cambio en silencio.
- **Bloqueo optimista** con `@Version` sobre la columna `version`.
- **Esquema.** Si Hibernate genera el esquema (`spring.jpa.hibernate.ddl-auto`), crea también estas tablas, porque las
  entidades forman parte de la unidad de persistencia. Si no, se crean con el script, como con JDBC (véase
  [Crear las tablas](#crear-las-tablas)).

### Con JDBC

`JdbcProcessInstanceStore` usa `JdbcClient` de Spring con el `DataSource` de la aplicación. No necesita JPA, pero
convive con ella: si eliges `pac.process.store.type=jdbc` en una aplicación con JPA, el almacén escribe con la misma
conexión de la transacción en curso.

### Tablas

| Tabla | Contenido |
|---|---|
| `pac_process_instance` | una fila por instancia: proceso, versión, correlación, estado (`ACTIVE`/`COMPLETED`), nodo actual, `loopCounter`, flujo seleccionado, mensajes de inicio y de fin, fechas y `version` para el bloqueo optimista. Restricción única `uk_pac_process_instance_correlation` sobre `(process_id, correlation_key)` e índice `ix_pac_process_instance_process_version` por `(process_id, process_version)` |
| `pac_flow_node_instance` | una fila por paso: instancia, número de orden, proceso, versión, nodo, tipo, `loopCounter`, momento y resultado. Índices `ix_pac_flow_node_instance_process_instance` por `process_instance_id` e `ix_pac_flow_node_instance_process_version` por `(process_id, process_version)` |

Las referencias a mensajes y a versiones de proceso se guardan como texto: `EV-PERSON-REGISTERED@2` y
`PER-REGISTRATION:1` (`StoredReferences`).

### Crear las tablas

El script va dentro del jar, en `io/github/eldiegos/bpmncode/runtime/schema.sql` (`JdbcProcessInstanceStore.SCHEMA`).
Usa `create table if not exists`, así que se puede ejecutar en cada arranque. Está escrito para H2 y PostgreSQL (tipos
`uuid` y `timestamp with time zone`). Sirve a los almacenes JDBC y JPA; la librería lo ejecuta si hay un `DataSource`
y `spring-jdbc` en el classpath, según esta propiedad:

```properties
# embedded (por defecto): crea las tablas solo si la base de datos es H2
pac.process.store.initialize-schema=embedded
# always: las crea siempre al arrancar
# never: no las crea; lo hace tu herramienta de migraciones
```

Con Flyway o Liquibase, copia el contenido del script a una migración tuya, adáptalo a tu base de datos si no es H2 ni
PostgreSQL y pon `initialize-schema=never`. También puedes crearlas desde código con
`JdbcProcessInstanceStore.initializeSchema(dataSource)`.

```sql
create table if not exists pac_process_instance (
    id                            uuid                        not null primary key,
    process_id                    varchar(80)                 not null,
    process_version               integer                     not null,
    correlation_key               uuid                        not null,
    business_key                  varchar(80),
    state                         varchar(20)                 not null,
    current_flow_node_id          varchar(80)                 not null,
    loop_counter                  integer                     not null,
    selected_sequence_flow_id     varchar(160),
    triggering_message_id         uuid,
    triggering_message            varchar(120),
    triggering_source             varchar(120),
    triggering_source_instance_id uuid,
    triggering_occurred_at        timestamp(6) with time zone,
    published_message_id          uuid,
    published_message             varchar(120),
    started_at                    timestamp(6) with time zone not null,
    ended_at                      timestamp(6) with time zone,
    version                       bigint                      not null,
    constraint uk_pac_process_instance_correlation unique (process_id, correlation_key)
);

create table if not exists pac_flow_node_instance (
    id                  uuid                        not null primary key,
    process_instance_id uuid                        not null,
    sequence            integer                     not null,
    process_id          varchar(80)                 not null,
    process_version     integer                     not null,
    correlation_key     uuid                        not null,
    business_key        varchar(80),
    flow_node_id        varchar(80)                 not null,
    element_type        varchar(30)                 not null,
    loop_counter        integer                     not null,
    occurred_at         timestamp(6) with time zone not null,
    outcome             varchar(500)                not null
);

create index if not exists ix_pac_flow_node_instance_process_instance on pac_flow_node_instance (process_instance_id);
create index if not exists ix_pac_flow_node_instance_process_version on pac_flow_node_instance (process_id, process_version);
create index if not exists ix_pac_process_instance_process_version on pac_process_instance (process_id, process_version);
```

No hay clave ajena de `pac_flow_node_instance.process_instance_id` a `pac_process_instance.id`. Puedes añadirla en tu
migración si quieres.

### Transacciones

`ProcessJournal` está anotado con `@Transactional`. Si la aplicación tiene Spring Transaction y un gestor de
transacciones (lo normal con `spring-boot-starter-jdbc` o `-data-jpa`), cada llamada se une a la transacción de quien
llama. La evidencia y los datos de negocio se confirman o se deshacen juntos, también si tus datos van por JPA. Sin
Spring Transaction la anotación se ignora.

## Un almacén propio

Para guardar en otro sitio (MongoDB, un servicio, ficheros…) implementa `ProcessInstanceStore` y decláralo como bean.
La autoconfiguración no crea entonces ninguno de los suyos.

```java
@Bean
ProcessInstanceStore processInstanceStore(MongoTemplate mongo) {
	return new MongoProcessInstanceStore(mongo);
}
```

El contrato:

| Método | Debe |
|---|---|
| `insert(instance)` | guardar una instancia nueva; lanzar `DuplicateProcessInstanceException` si ya hay una del mismo proceso con la misma correlación |
| `update(instance)` | guardar solo si la versión guardada es `instance.version()` y guardarla incrementada; si no, o si la instancia no existe, lanzar `ProcessInstanceConflictException` |
| `instance(id)`, `instance(processId, correlationKey)` | devolver la instancia guardada, con su versión |
| `instances()`, `instances(correlationKey)` | devolver las instancias, en cualquier orden |
| `instances(processDefinitionRef)` | opcional: las instancias de una versión de un proceso; por defecto filtra `instances()` |
| `append(flowNodeInstance)` | añadir la evidencia; nunca se modifica |
| `flowNodeInstances(processInstanceId)` | devolver la evidencia de una instancia, en cualquier orden |
| `flowNodeInstances(processDefinitionRef)` | opcional: la evidencia de todas las instancias de una versión; por defecto reúne la de cada instancia de `instances(processDefinitionRef)` |
| `flowNodeInstanceCount(processInstanceId)` | opcional: contar la evidencia sin cargarla |

Los métodos opcionales son métodos por defecto de la interfaz, así que un almacén propio funciona sin ellos. Los tres
almacenes de la librería resuelven las consultas por versión con una sola consulta, apoyada en los índices por
`(process_id, process_version)`; un almacén propio con muchas instancias debería hacer lo mismo, porque las usan las
[estadísticas de ejecución](estadisticas-web.md#estadísticas-de-ejecución).

`ProcessInstanceRecord` y `FlowNodeInstanceRecord` son records inmutables con todo lo que hay que guardar.
`StoredReferences` da la forma de texto de las referencias a mensajes y a procesos que usan los almacenes de la
librería, por si quieres guardarlas igual.

### Probar el almacén

`ProcessInstanceStoreContract` comprueba el contrato a través del journal y del historial (un proceso completo, una
instancia por proceso y correlación, nada guardado si un paso no es conforme, bloqueo optimista). Se publica en el jar
de pruebas de la librería (clasificador `tests`):

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

Extiende la clase e implementa `newStore()`, que debe devolver un almacén vacío:

```java
class MongoProcessInstanceStoreTests extends ProcessInstanceStoreContract {

	@Override
	protected ProcessInstanceStore newStore() {
		return new MongoProcessInstanceStore(…);
	}
}
```

El contrato usa el almacén sin transacción; un almacén que la necesite (como el JPA) se prueba dentro de un contexto
de Spring.

## Excepciones

| Excepción | Cuándo |
|---|---|
| `DuplicateProcessInstanceException` (una `ProcessConformanceException`) | se inicia una segunda instancia del mismo proceso con la misma correlación |
| `ProcessInstanceConflictException` | dos avances concurrentes de la misma instancia: el segundo se basó en un estado que ya no es el guardado |

Con mensajes que pueden llegar más de una vez, inicia con `journal.startProcessInstanceIfAbsent` (véase
[Transacciones e idempotencia](ejecucion.md#transacciones-e-idempotencia)) o trata `DuplicateProcessInstanceException`
como un duplicado.
