# Lesson 12. Testing and production

> 🇪🇸 [Versión en español](../../es/curso/12-pruebas-y-produccion.md)

**Goal:** finish the application with tests that protect the model and the complete journeys, and with a
configuration designed for production: an explicit store, schema through migrations, reliable messages, a protected
viewer and verified boundaries between modules.

## What you will learn

- How to use the library's test jar and `ProcessAssertions`.
- How to write an end-to-end test that spans two processes.
- What configuration changes between development and production, and why.
- How to deliver messages reliably with Spring Modulith and verify that your modules only use the library's public
  API.

## Starting point

The code from [lesson 11](11-statistics-and-web-viewer.md): the complete application, with the web module enabled in
`application.properties` and the `ProcessModelTests` and `ExpenseClaimProcessTests` tests using the application's H2
file.

## Steps

### 1. Tests that do not depend on the environment

Tests must not share the database of your running application. Give each `@SpringBootTest` class its own in-memory
database. In `ExpenseClaimProcessTests`:

```java
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expenses-tests")
class ExpenseClaimProcessTests {
```

Remember what each level already tests:

| Test | What it detects | Cost |
|---|---|---|
| `ProcessModelTests` (lesson 3) | any model error, without Spring | milliseconds |
| any `@SpringBootTest` | the same, because the model is validated when the context is created | seconds |
| `ExpenseClaimProcessTests` (lessons 5 to 9) | paths and outcomes of `EXP-CLAIM`, conformance | seconds |

If a test depends on the time, define your own `Clock` bean: the library uses whichever one exists to timestamp
instances and evidence.

### 2. The library's test jar

`bpmncode` publishes a test jar (classifier `tests`) with `ProcessAssertions` and the official BPMN 2.0 XSDs. Add it
together with Awaitility, which you will use in step 6:

```xml
		<dependency>
			<groupId>io.github.eldiegos</groupId>
			<artifactId>bpmncode</artifactId>
			<version>${bpmncode.version}</version>
			<classifier>tests</classifier>
			<type>test-jar</type>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.awaitility</groupId>
			<artifactId>awaitility</artifactId>
			<scope>test</scope>
		</dependency>
```

| Method | Checks or returns |
|---|---|
| `assertEveryDiagramIsValidBpmn(diagrams)` | that every diagram is valid BPMN 2.0 according to the OMG XSDs |
| `assertEveryDrawnElementIsDescribed(diagrams)` | that each drawn element has its details (a viewer can explain everything) |
| `assertValidBpmn(xml)` | the same for a single document |
| `pathOf(history, instanceId)` | the ids of the nodes an instance recorded, in order |

A failure is an `AssertionError` with the details; it does not depend on any assertion library.

### 3. Reliable messages with Spring Modulith

With `@EventListener`, the payment process starts in the same thread and in the same transaction as the claim. If the
payment fails, the claim is rolled back too; and if the application goes down after committing, nothing retries.

Spring Modulith solves both: `@ApplicationModuleListener` delivers the message **after** the publisher's transaction
commits, in a transaction of its own, and its **publication registry** keeps each delivery until the listener
finishes, to retry it if needed. That delivery is *at least once*: that is why the payment has been started with
`startProcessInstanceIfAbsent` since lesson 7.

Add the Spring Modulith BOM and its *starters*:

```xml
	<dependencyManagement>
		<dependencies>
			<dependency>
				<groupId>org.springframework.modulith</groupId>
				<artifactId>spring-modulith-bom</artifactId>
				<version>2.1.1</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
		</dependencies>
	</dependencyManagement>
```

```xml
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-starter-core</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-starter-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.modulith</groupId>
			<artifactId>spring-modulith-starter-test</artifactId>
			<scope>test</scope>
		</dependency>
```

In `ExpensePaymentProcess`, change the listener. The processor accepts it because `@ApplicationModuleListener`
carries `@EventListener` inside:

```java
import org.springframework.modulith.events.ApplicationModuleListener;
```

```java
	@ApplicationModuleListener
	@MessageStartEvent(id = ORDER_RECEIVED, name = "Payment order received", messageRef = PaymentOrdered.class,
			outgoing = @SequenceFlow(targetRef = TRANSFER))
	public void on(PaymentOrdered order) {
		// … unchanged …
	}
```

(and delete the `org.springframework.context.event.EventListener` import).

### 4. Widen the registry column

The JPA publication registry stores each message serialized as JSON, in a column that Hibernate creates with 255
characters. With the header, a payment order takes more, and the publication fails:

```
Value too long for column "SERIALIZED_EVENT CHARACTER VARYING(255)"
```

(on a system in Spanish, H2 says `Valor demasiado largo para la columna…`). Widen it with an `orm.xml` in the
application:

`src/main/resources/META-INF/orm.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence/orm https://jakarta.ee/xml/ns/persistence/orm/orm_3_1.xsd"
		version="3.1">
	<mapped-superclass class="org.springframework.modulith.events.jpa.JpaEventPublication" access="FIELD">
		<attributes>
			<basic name="serializedEvent">
				<column name="serialized_event" length="4000"/>
			</basic>
		</attributes>
	</mapped-superclass>
</entity-mappings>
```

If your migrations create the tables (step 7), use a long text type for that column directly.

### 5. Verify the boundaries between modules

You already have two modules, `claim` and `payment`, and `payment` depends on `claim` (the message contracts are in
`claim`), never the other way round. `bpmncode` declares its packages as a Spring Modulith module, with its public API
in named interfaces (`annotation`, `model`, `runtime`, `statistics`, `trace`, `i18n`, `bpmn`). Add its package as an
additional base and Modulith will also check that you do not use the library's internal packages:

`src/main/java/com/example/expenses/ExpensesApplication.java`:

```java
package com.example.expenses;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic(additionalPackages = "io.github.eldiegos")
public class ExpensesApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExpensesApplication.class, args);
	}
}
```

Declare what each module uses:

`src/main/java/com/example/expenses/claim/package-info.java`:

```java
@ApplicationModule(allowedDependencies = { "bpmncode :: annotation", "bpmncode :: model", "bpmncode :: runtime" })
package com.example.expenses.claim;

import org.springframework.modulith.ApplicationModule;
```

`src/main/java/com/example/expenses/payment/package-info.java`:

```java
@ApplicationModule(allowedDependencies = { "claim", "bpmncode :: annotation", "bpmncode :: model", "bpmncode :: runtime" })
package com.example.expenses.payment;

import org.springframework.modulith.ApplicationModule;
```

`src/test/java/com/example/expenses/ModularityTests.java`:

```java
package com.example.expenses;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

	@Test
	void modulesRespectTheirBoundaries() {
		ApplicationModules.of(ExpensesApplication.class).verify();
	}
}
```

`AnnotationProcessModelReader` (package `model.reader`) is internal: use it only in tests without Spring, such as
`ProcessModelTests`, which are not subject to this verification.

### 6. An end-to-end test

This test spans both processes: claim, approval, payment and confirmation from the bank. Since the payment now starts
after the transaction commits, and in another thread, the test **waits** for its instance to exist before confirming.

`src/test/java/com/example/expenses/ExpenseJourneyTests.java`:

```java
package com.example.expenses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.expenses.claim.ClaimApprovals;
import com.example.expenses.claim.ExpenseClaim;
import com.example.expenses.claim.ExpenseClaimProcess.Decision;
import com.example.expenses.claim.ExpenseClaimProcessV2;
import com.example.expenses.payment.ExpensePaymentProcess;

import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceSummary;
import io.github.eldiegos.bpmncode.test.ProcessAssertions;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expenses-tests")
class ExpenseJourneyTests {

	@Autowired
	BpmnDiagramService diagrams;

	@Autowired
	ProcessInstanceHistory history;

	@Autowired
	ExpenseClaimProcessV2 claims;

	@Autowired
	ClaimApprovals approvals;

	@Autowired
	ExpensePaymentProcess payments;

	@Test
	void everyDiagramIsValidBpmnAndFullyDescribed() {
		ProcessAssertions.assertEveryDiagramIsValidBpmn(diagrams);
		ProcessAssertions.assertEveryDrawnElementIsDescribed(diagrams);
	}

	@Test
	void anApprovedClaimIsPaidAndConfirmed() {
		var claim = new ExpenseClaim(UUID.randomUUID(), "E-1042", new BigDecimal("1800"), true);
		var correlation = CorrelationKey.of(claim.id());

		var instance = claims.submit(claim);
		approvals.approve(claim.id(), Decision.APPROVED);
		await().until(() -> history.instances(correlation).size() == 2);
		payments.confirm(claim.id(), "TRF-2026-0001");

		assertThat(ProcessAssertions.pathOf(history, instance.instanceId())).containsExactly("EXP-SUBMITTED",
				"EXP-CHECK", "EXP-ROUTE", "EXP-APPROVE", "EXP-DECISION", "EXP-PAY", "EXP-PAYMENT-ORDERED");
		var payment = history.instances(correlation).get(1);
		assertThat(payment.processId()).isEqualTo("EXP-PAYMENT");
		assertThat(ProcessAssertions.pathOf(history, payment.instanceId()))
				.containsExactly("PAY-ORDER-RECEIVED", "PAY-TRANSFER", "PAY-CONFIRMED");
		assertThat(history.instances(correlation)).allMatch(ProcessInstanceSummary.Completed.class::isInstance);
	}
}
```

`history.instances(correlationKey)` orders them by start: first the claim, then the payment.

### 7. Configuration per environment

Separate what is common from what depends on the environment: `spring.datasource.url`,
`spring.jpa.hibernate.ddl-auto` and `pac.process.web.enabled` leave `application.properties` and move to a file per
profile.

`src/main/resources/application.properties` (common):

```properties
spring.application.name=expenses
spring.jpa.open-in-view=false
spring.messages.basename=messages,io/github/eldiegos/bpmncode/i18n/messages
spring.messages.fallback-to-system-locale=false
pac.process.store.type=jpa
```

`src/main/resources/application-dev.properties` (your team: H2 in a file, Hibernate creates your tables and the viewer
is enabled):

```properties
spring.datasource.url=jdbc:h2:file:./data/expenses
spring.jpa.hibernate.ddl-auto=update
pac.process.web.enabled=true
```

Start in development with `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. Without a profile, the application
uses an in-memory H2 database and the viewer does not exist (`/bpmncode/viewer.html` returns 404).

In production (`application-prod.properties`, with the real database):

```properties
spring.datasource.url=jdbc:postgresql://db.example.com/expenses
spring.jpa.hibernate.ddl-auto=none
pac.process.store.initialize-schema=never
```

- **`pac.process.store.type=jpa`** in the common file: if JPA is missing, the application does not start instead of
  falling back to another store.
- **Tables, through migrations.** The library's script is inside its jar, at
  `io/github/eldiegos/bpmncode/runtime/schema.sql` (constant `JdbcProcessInstanceStore.SCHEMA`). It is written for H2
  and PostgreSQL. Copy its content into a Flyway or Liquibase migration (adapt it if you use another database), together
  with your `expense_claim` table and Modulith's `event_publication`, and leave `initialize-schema=never`.
- **The viewer, only where it belongs.** In production, either do not enable it or protect it: with Spring Security,
  its routes (`/bpmncode/**`) are subject to your rules like any other.
- **`pac/message-contracts.lock`** goes into version control and is reviewed on every change to a message.

## Check that it works

```bash
./mvnw test
```

They all pass: `ProcessModelTests`, `ExpenseClaimProcessTests`, `ExpenseJourneyTests` and `ModularityTests`. Then:

- `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`: the log says `Process instances are stored with
  JpaProcessInstanceStore` and the viewer responds at `/bpmncode/viewer.html`.
- Submit and approve a large claim: the `EXP-PAYMENT` instance appears an instant after the approval responds,
  because it starts after the transaction commits.

### Production checklist

| | What | Lesson |
|---|---|---|
| ☐ | The annotation processor is in `annotationProcessors` (with the other processors) | 1, 4 |
| ☐ | There is a model test and tests for the main paths | 4, 5, 12 |
| ☐ | The evidence carries no personal data (business keys and outcomes with codes and references) | 5 |
| ☐ | Waiting tasks check whose turn it is (`isWaitingAt`) before calling the journal | 6, 7 |
| ☐ | Message starts are idempotent (`startProcessInstanceIfAbsent`) | 7 |
| ☐ | `pac/message-contracts.lock` exists and is under version control | 7 |
| ☐ | Explicit store (`pac.process.store.type`) and transactions at the entry points | 8 |
| ☐ | Tables created by migrations, `initialize-schema=never` | 8, 12 |
| ☐ | Each old version is retired only when it has no active instances left | 9 |
| ☐ | Messages with `@ApplicationModuleListener` and a widened registry column | 12 |
| ☐ | The web module, off or protected | 11, 12 |
| ☐ | `ApplicationModules.verify()` in the tests | 12 |

## Common mistakes

- **`Value too long for column "SERIALIZED_EVENT …"`** when publishing with the Modulith registry: step 4 is missing.
- **The end-to-end test fails with `No EXP-PAYMENT instance for correlation …`.** The payment had not started yet:
  wait with Awaitility, as in step 6.
- **`ApplicationModules.verify()` fails** because a module uses an internal package of the library (`model.reader`,
  `runtime.jpa`, `web`, `processor`) or an interface it did not declare in `allowedDependencies`. Use only the public
  API or declare it.
- **The test jar cannot be found.** Check `<classifier>tests</classifier>` and `<type>test-jar</type>`.

## Exercise

Your team wants its own instance store (for example, in MongoDB). Without writing all of it: what is needed for the
library to use it, and how would you check that it meets the contract?

<details><summary>Solution</summary>

- Implement `ProcessInstanceStore` and declare it as a bean: the auto-configuration stops creating its own
  (`@ConditionalOnMissingBean`).
- Test it by extending `io.github.eldiegos.bpmncode.runtime.ProcessInstanceStoreContract`, from the same test jar as
  `ProcessAssertions`, and
  implementing `newStore()` so that it returns an empty store. The contract checks a complete process, one instance
  per process and correlation, that nothing is saved if a step is not conformant, and optimistic locking.

```java
class MongoProcessInstanceStoreTests extends ProcessInstanceStoreContract {

	@Override
	protected ProcessInstanceStore newStore() {
		return new MongoProcessInstanceStore(…);
	}
}
```

The per-version queries (`instances(processDefinitionRef)`, `flowNodeInstances(processDefinitionRef)`) have a default
implementation, but with many instances it pays to resolve them with a single query: the statistics use them.

</details>

## Learn more

- [Configuration: application tests](../configuration.md#application-tests) and
  [`ProcessAssertions`](../configuration.md#processassertions).
- [Configuration: Spring Modulith](../configuration.md#spring-modulith) and [properties](../configuration.md#properties).
- [Storage: creating the tables](../storage.md#creating-the-tables) and
  [a custom store](../storage.md#a-custom-store).
- [Messages: message persistence](../messages.md#message-persistence).

---

[← Previous: Statistics and web viewer](11-statistics-and-web-viewer.md) | [Index](README.md)

Congratulations! You have finished the course. From here on, the [reference manual](../getting-started.md) has all
the details.
