# Storage

> 🇪🇸 [Versión en español](../es/almacenamiento.md)

`ProcessJournal` and `ProcessInstanceHistory` save and read instances through a `ProcessInstanceStore`. The library
ships three implementations and picks exactly one depending on what the application has. The application can provide
its own.

| Store | When it is used | Needs |
|---|---|---|
| `JpaProcessInstanceStore` | the application has JPA (an `EntityManagerFactory` that manages the library's entities), or `pac.process.store.type=jpa` asks for it | an `EntityManagerFactory` and `spring-orm`; both come with `spring-boot-starter-data-jpa` |
| `JdbcProcessInstanceStore` | the application has a `DataSource` and `spring-jdbc` on the classpath, but not JPA, or `pac.process.store.type=jdbc` asks for it | a `DataSource`; `spring-jdbc` already comes with `spring-boot-starter-jdbc` or `spring-boot-starter-data-jpa` |
| `InMemoryProcessInstanceStore` | the application has no database, or `pac.process.store.type=memory` asks for it | nothing |
| your own | you define a `ProcessInstanceStore` bean | whatever your implementation uses |

Whatever the store, the journal applies the same rules: one instance per process and correlation, optimistic locking
and append-only evidence, with each position of an instance's history recorded only once.

## Choosing the store

```properties
# auto (default): JPA if there is an EntityManagerFactory and it manages the entities; otherwise JDBC if there is a DataSource and spring-jdbc; otherwise memory
pac.process.store.type=auto
# memory: always in memory, even if there is a database
# jdbc: always JDBC; the application does not start without a DataSource or spring-jdbc
# jpa: always JPA; the application does not start without an EntityManagerFactory or spring-orm
```

The store in use is logged at startup:

```
Process instances are stored with JpaProcessInstanceStore
```

## In memory

Meant for applications without a database, tests, demos or tools that only run processes while they are up.

- It needs no dependency at all: no database, no JPA, no Spring Transaction.
- It is thread-safe.
- **Everything is lost when the application stops.**
- **It is not transactional**: if your code throws an exception after a journal step, that step stays recorded even
  if your transaction (if you have one) rolls back. If you need the evidence and your data to commit or roll back
  together, use a transactional store.

## In a database

The JPA and JDBC stores use the same two tables, so an application can switch from one to the other without
migrating data.

### With JPA

`JpaProcessInstanceStore` (package `io.github.eldiegos.bpmncode.runtime.jpa`) stores instances and evidence as two
JPA entities of the library, in the application's persistence unit: same provider, same dialect and same persistence
context as your entities.

- **Entities.** The auto-configuration adds the package `io.github.eldiegos.bpmncode.runtime.jpa` to the
  application's auto-configuration packages, so JPA finds the entities without any configuration. It only does so if
  the store is `auto` or `jpa`; with `memory` or `jdbc` the entities are not part of the persistence unit. If the
  application declares `@EntityScan`, it replaces the default scan: include the library's package.

  ```java
  @EntityScan({ "com.example.orders", "io.github.eldiegos.bpmncode.runtime.jpa" })
  ```

  If the persistence unit does not manage the entities, `auto` logs a warning and uses the JDBC store (or memory);
  with `jpa` the application does not start.
- **A transaction is required to write.** The journal opens one with `@Transactional` (Spring Transaction comes with
  `spring-boot-starter-data-jpa`). Writing without a transaction fails with `TransactionRequiredException` instead of
  silently losing the change. Queries (`instances()`, `flowNodeInstances(…)`…) also work outside a transaction.
- **Optimistic locking** with `@Version` on the `version` column.
- **Each position of the history, only once.** The entity declares the unique constraint
  `uk_pac_flow_node_instance_sequence` on `(process_instance_id, sequence)` and `append` checks first whether the
  position is already taken.
- **Schema.** If Hibernate generates the schema (`spring.jpa.hibernate.ddl-auto`), it also creates these tables,
  because the entities are part of the persistence unit. Otherwise they are created with the script, as with JDBC (see
  [Creating the tables](#creating-the-tables)).

### With JDBC

`JdbcProcessInstanceStore` uses Spring's `JdbcClient` with the application's `DataSource`. It does not need JPA, but
it coexists with it: if you choose `pac.process.store.type=jdbc` in an application with JPA, the store writes with the
same connection as the current transaction. If the unique index `uk_pac_flow_node_instance_sequence` rejects a record
(`DuplicateKeyException`), it translates it into `ProcessInstanceConflictException`.

### Tables

| Table | Content |
|---|---|
| `pac_process_instance` | one row per instance: process, version, correlation, state (`ACTIVE`/`COMPLETED`), current node, `loopCounter`, selected flow, start and end messages, timestamps and `version` for optimistic locking. Unique constraint `uk_pac_process_instance_correlation` on `(process_id, correlation_key)` and index `ix_pac_process_instance_process_version` on `(process_id, process_version)` |
| `pac_flow_node_instance` | one row per step: instance, sequence number, process, version, node, type, `loopCounter`, timestamp and outcome. Unique index `uk_pac_flow_node_instance_sequence` on `(process_instance_id, sequence)` and indexes `ix_pac_flow_node_instance_process_instance` on `process_instance_id` and `ix_pac_flow_node_instance_process_version` on `(process_id, process_version)` |

References to messages and process versions are stored as text: `EV-PERSON-REGISTERED@2` and `PER-REGISTRATION:1`
(`StoredReferences`).

### Creating the tables

The script ships inside the jar, at `io/github/eldiegos/bpmncode/runtime/schema.sql`
(`JdbcProcessInstanceStore.SCHEMA`). It uses `create table if not exists`, so it can run on every startup. It is
written for H2 and PostgreSQL (`uuid` and `timestamp with time zone` types). It serves both the JDBC and JPA stores;
the library runs it if there is a `DataSource` and `spring-jdbc` on the classpath, according to this property:

```properties
# embedded (default): creates the tables only if the database is H2
pac.process.store.initialize-schema=embedded
# always: always creates them at startup
# never: does not create them; your migration tool does
```

With Flyway or Liquibase, copy the script into one of your migrations, adapt it to your database if it is neither H2
nor PostgreSQL and set `initialize-schema=never`. You can also create them from code with
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

create unique index if not exists uk_pac_flow_node_instance_sequence on pac_flow_node_instance (process_instance_id, sequence);
create index if not exists ix_pac_flow_node_instance_process_instance on pac_flow_node_instance (process_instance_id);
create index if not exists ix_pac_flow_node_instance_process_version on pac_flow_node_instance (process_id, process_version);
create index if not exists ix_pac_process_instance_process_version on pac_process_instance (process_id, process_version);
```

There is no foreign key from `pac_flow_node_instance.process_instance_id` to `pac_process_instance.id`. You can add it
in your migration if you want.

The uniqueness of `(process_instance_id, sequence)` is an index (`create unique index if not exists`), not a table
constraint, so that the script also adds it to tables created by earlier versions. If an existing database already
had repeated positions (only possible if two steps of the same instance were recorded at the same time without a
transaction), creating the index fails: clean up those rows first. With Flyway or Liquibase, add the index in a new
migration.

### Transactions

`ProcessJournal` is annotated with `@Transactional`. If the application has Spring Transaction and a transaction
manager (the usual case with `spring-boot-starter-jdbc` or `-data-jpa`), each call joins the caller's transaction. The
evidence and the business data commit or roll back together, also if your data goes through JPA. Without Spring
Transaction the annotation is ignored.

With a transaction, two concurrent steps of the same instance are serialised. Without one, the store still prevents
them from leaving the same position repeated in the history: the second fails with
`ProcessInstanceConflictException` and saves nothing.

## A custom store

To store somewhere else (MongoDB, a service, files…) implement `ProcessInstanceStore` and declare it as a bean. The
auto-configuration then creates none of its own.

```java
@Bean
ProcessInstanceStore processInstanceStore(MongoTemplate mongo) {
	return new MongoProcessInstanceStore(mongo);
}
```

The contract:

| Method | Must |
|---|---|
| `insert(instance)` | save a new instance; throw `DuplicateProcessInstanceException` if there is already one of the same process with the same correlation |
| `update(instance)` | save only if the stored version is `instance.version()` and store it incremented; otherwise, or if the instance does not exist, throw `ProcessInstanceConflictException` |
| `instance(id)`, `instance(processId, correlationKey)` | return the stored instance, with its version |
| `instances()`, `instances(correlationKey)` | return the instances, in any order |
| `instances(processDefinitionRef)` | optional: the instances of a version of a process; by default it filters `instances()` |
| `append(flowNodeInstance)` | append the evidence, which is never modified; if the instance already has a record at that position (`sequence`), throw `ProcessInstanceConflictException.sequenceTaken(instanceId, sequence)` without saving anything |
| `flowNodeInstances(processInstanceId)` | return the evidence of an instance, in any order |
| `flowNodeInstances(processDefinitionRef)` | optional: the evidence of every instance of a version; by default it gathers that of each instance of `instances(processDefinitionRef)` |
| `flowNodeInstanceCount(processInstanceId)` | optional: count the evidence without loading it |
| `activeVersions()` | optional: the process versions with some active instance, sorted and without repetitions; by default computed with `instances()`. Used by the startup check (see [Retiring a version](versions.md#retiring-a-version)) |

The optional methods are default methods of the interface, so a custom store works without them. The library's three
stores answer the queries by version with a single query, backed by the indexes on `(process_id, process_version)`, and
`activeVersions()` with a `select distinct`; a custom store with many instances should do the same, because the
[execution statistics](statistics-web.md#execution-statistics) use them.

`ProcessInstanceRecord` and `FlowNodeInstanceRecord` are immutable records with everything that must be stored. The
outcome of each step takes at most `FlowNodeOutcome.MAX_LENGTH` (500) characters in its text form, the size of the
`outcome` column.
`StoredReferences` provides the text form of message and process references used by the library's stores, in case you
want to store them the same way.

### Testing the store

`ProcessInstanceStoreContract` checks the contract through the journal and the history (a complete process, one
instance per process and correlation, nothing stored when a step is not conformant, optimistic locking, each position of
the history only once and the versions with active instances). It is published in the library's test jar (classifier `tests`):

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

Extend the class and implement `newStore()`, which must return an empty store:

```java
class MongoProcessInstanceStoreTests extends ProcessInstanceStoreContract {

	@Override
	protected ProcessInstanceStore newStore() {
		return new MongoProcessInstanceStore(…);
	}
}
```

The contract uses the store without a transaction; a store that needs one (such as the JPA store) is tested inside a
Spring context.

## Exceptions

| Exception | When |
|---|---|
| `DuplicateProcessInstanceException` (a `ProcessConformanceException`) | a second instance of the same process is started with the same correlation |
| `ProcessInstanceConflictException` | two concurrent advances of the same instance: the second was based on a state that is no longer the stored one (`Process instance <id> changed after version <n> was read, or no longer exists`) or tried to record a position of the history that is already taken (`Flow node instance <n> of process instance <id> is already recorded: another step of the same instance was recorded concurrently`) |

With messages that may arrive more than once, start with `journal.startProcessInstanceIfAbsent` (see
[Transactions and idempotency](execution.md#transactions-and-idempotency)) or treat
`DuplicateProcessInstanceException` as a duplicate.
