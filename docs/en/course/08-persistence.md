# Lesson 8. Persistence: memory, JDBC and JPA

> 🇪🇸 [Versión en español](../../es/curso/08-persistencia.md)

**Goal:** make instances, their evidence and the claims survive a restart and be saved in the same transaction.

## What you will learn

- Which instance store the library chooses depending on what your application has, and how to force one.
- Which tables it uses and when it creates them.
- Why the evidence and your business data must be committed or rolled back together, and how to achieve it with
  `@Transactional`.

## Starting point

The code from [lesson 7](07-messages-between-processes.md): the processes `EXP-CLAIM` and `EXP-PAYMENT` communicate
through messages, claims are stored in an in-memory map (`ExpenseClaims`) and there is no database. At startup you see
`Process instances are stored with InMemoryProcessInstanceStore`.

## Steps

### 1. What fails today

Submit a large claim, stop the application and start it again. The instance is gone: the in-memory store loses
everything when it stops. Moreover, it **is not transactional**: if your code fails after a journal step, that step
stays recorded. It is fine for tests and demos, not for production.

The library ships three stores and chooses one at startup (`pac.process.store.type=auto`, the default):

| The application has… | Store |
|---|---|
| JPA (an `EntityManagerFactory` that manages the library's entities) | `JpaProcessInstanceStore` |
| a `DataSource` and `spring-jdbc`, without JPA | `JdbcProcessInstanceStore` |
| nothing | `InMemoryProcessInstanceStore` |

The JDBC and JPA stores use the same two tables, `pac_process_instance` and `pac_flow_node_instance`, so you can switch
from one to the other without migrating data.

### 2. First, JDBC

Add an H2 database and the JDBC *starter* to the `pom.xml`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-jdbc</artifactId>
		</dependency>
		<dependency>
			<groupId>com.h2database</groupId>
			<artifactId>h2</artifactId>
			<scope>runtime</scope>
		</dependency>
```

And store the database in a file, so that it survives restarts.

`src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:h2:file:./data/expenses
```

Nothing else is needed. At startup:

```
Process instances are stored with JdbcProcessInstanceStore
```

The library has created the tables: with `pac.process.store.initialize-schema=embedded` (the default) it runs its
script only if the database is H2. With another database, the tables are up to your migrations (lesson 12).

Repeat the test from step 1: submit a claim of 1,800 EUR, restart and query `GET /claims/{claimId}/instances`. The
instance is still there, waiting at `EXP-APPROVE`. But if you approve it, it responds `500`:
`claims.find(claimId).orElseThrow()` does not find the claim, because `ExpenseClaims` is still in memory. The evidence
has survived; your data has not. You have to store it in the same database.

### 3. Then, JPA

Your business data will go into a JPA entity. Replace `spring-boot-starter-jdbc` with the JPA *starter* (which already
includes JDBC):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
```

With JPA present, the library adds its two entities (package `io.github.eldiegos.bpmncode.runtime.jpa`) to your
persistence unit and uses the JPA store: same provider, same dialect and same transaction as your entities.

Complete `application.properties`:

```properties
spring.datasource.url=jdbc:h2:file:./data/expenses
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
```

`ddl-auto=update` lets Hibernate create your `expense_claim` table in development. `open-in-view=false` avoids a
Spring Boot warning and keeps transactions where you put them.

### 4. Claims as an entity

`src/main/java/com/example/expenses/claim/ExpenseClaimEntity.java`:

```java
package com.example.expenses.claim;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "expense_claim")
class ExpenseClaimEntity {

	@Id
	private UUID id;
	private String employee;
	private BigDecimal amount;
	private boolean receiptsAttached;

	protected ExpenseClaimEntity() {
	}

	ExpenseClaimEntity(ExpenseClaim claim) {
		this.id = claim.id();
		this.employee = claim.employee();
		this.amount = claim.amount();
		this.receiptsAttached = claim.receiptsAttached();
	}

	ExpenseClaim toClaim() {
		return new ExpenseClaim(id, employee, amount, receiptsAttached);
	}
}
```

`src/main/java/com/example/expenses/claim/ExpenseClaimRepository.java`:

```java
package com.example.expenses.claim;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ExpenseClaimRepository extends JpaRepository<ExpenseClaimEntity, UUID> {
}
```

`ExpenseClaims` keeps its interface, so the process does not change because of this.

`src/main/java/com/example/expenses/claim/ExpenseClaims.java`:

```java
package com.example.expenses.claim;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
class ExpenseClaims {

	private final ExpenseClaimRepository repository;

	ExpenseClaims(ExpenseClaimRepository repository) {
		this.repository = repository;
	}

	void save(ExpenseClaim claim) {
		repository.save(new ExpenseClaimEntity(claim));
	}

	Optional<ExpenseClaim> find(UUID id) {
		return repository.findById(id).map(ExpenseClaimEntity::toClaim);
	}
}
```

### 5. One transaction per business operation

Every call to `ProcessJournal` is annotated with `@Transactional`: it joins the caller's transaction or opens its own.
If you open none, `submit` makes several small transactions (saving the claim, starting, completing `EXP-CHECK`…). If
something fails halfway, you are left with the claim and half the evidence.

Put `@Transactional` on the methods through which work **enters**: `submit` and `approve` (called by the controller)
and `confirm` (called by the webhook). Everything they do, including your data, the evidence and the start of the
payment process (the `@EventListener` listener runs on the same thread and in the same transaction), is committed or
rolled back together.

In `ExpenseClaimProcess`, add the import and annotate the two public methods:

```java
import org.springframework.transaction.annotation.Transactional;
```

```java
	@Transactional
	@StartEvent(id = SUBMITTED, name = "Claim submitted", outgoing = @SequenceFlow(targetRef = CHECK))
	public ProcessInstanceRef submit(ExpenseClaim claim) {
		// … unchanged …
	}

	@Transactional
	@UserTask(id = APPROVE, name = "Approve claim", outgoing = @SequenceFlow(targetRef = DECISION))
	public void approve(UUID claimId, Decision decision) {
		// … unchanged …
	}
```

In `ExpensePaymentProcess`, the same with `confirm`:

```java
	@Transactional
	@MessageEndEvent(id = CONFIRMED, name = "Payment confirmed", messageRef = PaymentConfirmed.class)
	public void confirm(UUID claimId, String bankReference) {
		// … unchanged …
	}
```

`@Transactional` works because Spring calls these methods through the bean's *proxy*. Internal calls
(`submit` → `check`) do not open another transaction, nor do they need to: they are already inside the one from
`submit`.

Now conformance protects your data too: if the journal rejects a step, the exception rolls back the whole transaction,
claim included.

### 6. Choosing the store by hand

`auto` is convenient, but in production it is better to say it explicitly: if something is missing, the application
does not start instead of falling back to another store.

```properties
# auto (default) | memory | jdbc | jpa
pac.process.store.type=jpa
# embedded (default: H2 only) | always | never
pac.process.store.initialize-schema=embedded
```

## Check that it works

If you want to start clean, delete the `data/` directory first. Start the application:

```
Process instances are stored with JpaProcessInstanceStore
```

1. Submit a claim of 1,800 EUR and note its `claimId`.
2. Stop the application and start it again.
3. Approve it: `POST /claims/{claimId}/approval` with `{"decision":"APPROVED"}` responds `204`.
4. `GET /claims/{claimId}/instances` returns the `EXP-CLAIM` instance completed at `EXP-PAYMENT-ORDERED` and the
   `EXP-PAYMENT` instance active at `PAY-TRANSFER`, waiting for the bank's confirmation (`nextFlowNodeIds`
   `["PAY-CONFIRMED"]`).

The tests still pass with `./mvnw test`, but now they use the same H2 file as the application: stop the application
before running them, because file-based H2 does not allow two processes at once. In lesson 12 you will make the tests
independent of that file.

## Common mistakes

- **`pac.process.store.type=jdbc` without a database.** The application does not start:
  `pac.process.store.type=jdbc needs a DataSource and spring-jdbc`. With `jpa`:
  `pac.process.store.type=jpa needs JPA (an EntityManagerFactory and spring-orm)`.
- **Your application declares `@EntityScan`.** It replaces the default entity scan and leaves out the library's
  entities. With `auto` you will see a warning (`The persistence unit does not manage the bpmncode entities (an
  @EntityScan without the package io.github.eldiegos.bpmncode.runtime.jpa?): process instances are not stored with
  JPA`) and JDBC will be used; with `jpa`, it does not start. Include the package:
  `@EntityScan({ "com.example.expenses", "io.github.eldiegos.bpmncode.runtime.jpa" })`.
- **Writing with the JPA store outside a transaction** fails with `TransactionRequiredException` instead of silently
  losing the change. The journal opens its own; you will only see it if you call the store directly.
- **`HHH100030: Called joinTransaction() on a non-JTA EntityManager (ignoring)` warnings** at every step, with
  Hibernate 7. The JPA store causes them when checking that there is a transaction; they are harmless. If they bother
  you, lower the log level of `org.hibernate.orm.jdbc` to `ERROR`.
- **Relying on the in-memory store to roll back steps.** It is not transactional: it rolls back nothing.

## Exercise

Your entities stay in JPA, but the database team prefers the process tables to be written with JDBC. Change it without
touching code. Do you need to migrate data?

<details><summary>Solution</summary>

```properties
pac.process.store.type=jdbc
```

At startup you will see `Process instances are stored with JdbcProcessInstanceStore`. There is nothing to migrate:
both stores use the same tables. The JDBC store writes with the connection of the current transaction, so
`@Transactional` still groups your JPA entities and the evidence. Go back to `auto` (or `jpa`) to continue the course.

</details>

## Learn more

- [Storage](../storage.md): [choosing the store](../storage.md#choosing-the-store),
  [with JPA](../storage.md#with-jpa), [tables](../storage.md#tables),
  [creating the tables](../storage.md#creating-the-tables) and [transactions](../storage.md#transactions).
- [Transactions and idempotency](../execution.md#transactions-and-idempotency).
- [A custom store](../storage.md#a-custom-store), if you do not use a relational database.

---

[← Previous: Messages between processes](07-messages-between-processes.md) | [Index](README.md) | [Next: Process and rule versions →](09-versions.md)
