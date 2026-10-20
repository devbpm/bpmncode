# Lección 12. Pruebas y producción

> 🇬🇧 [English version](../../en/course/12-testing-and-production.md)

**Objetivo:** cerrar la aplicación con pruebas que protejan el modelo y los recorridos completos, y con una
configuración pensada para producción: almacén explícito, esquema por migraciones, mensajes fiables, visor protegido y
límites entre módulos verificados.

## Lo que vas a aprender

- Cómo usar el jar de pruebas de la librería y `ProcessAssertions`.
- Cómo escribir una prueba de punta a punta que cruza dos procesos.
- Qué configuración cambia entre desarrollo y producción, y por qué.
- Cómo entregar los mensajes de forma fiable con Spring Modulith y verificar que tus módulos solo usan la API pública
  de la librería.

## Punto de partida

El código de la [lección 11](11-estadisticas-y-visor-web.md): la aplicación completa, con el módulo web activado en
`application.properties` y las pruebas `ProcessModelTests` y `ExpenseClaimProcessTests` usando el fichero H2 de la
aplicación.

## Pasos

### 1. Pruebas que no dependen del entorno

Las pruebas no deben compartir la base de datos de tu aplicación en marcha. Dale a cada clase `@SpringBootTest` su
propia base de datos en memoria. En `ExpenseClaimProcessTests`:

```java
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:expenses-tests")
class ExpenseClaimProcessTests {
```

Recuerda lo que ya prueba cada nivel:

| Prueba | Qué detecta | Coste |
|---|---|---|
| `ProcessModelTests` (lección 3) | cualquier error de modelo, sin Spring | milisegundos |
| cualquier `@SpringBootTest` | lo mismo, porque el modelo se valida al crear el contexto | segundos |
| `ExpenseClaimProcessTests` (lecciones 5 a 9) | caminos y resultados de `EXP-CLAIM`, conformidad | segundos |

Si alguna prueba depende de la hora, define un bean `Clock` propio: la librería usa el que haya para fechar instancias
y evidencia.

### 2. El jar de pruebas de la librería

`bpmncode` publica un jar de pruebas (clasificador `tests`) con `ProcessAssertions` y los XSD oficiales de BPMN 2.0.
Añádelo junto con Awaitility, que usarás en el paso 6:

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

| Método | Comprueba o devuelve |
|---|---|
| `assertEveryDiagramIsValidBpmn(diagrams)` | que todos los diagramas son BPMN 2.0 válido según los XSD de OMG |
| `assertEveryDrawnElementIsDescribed(diagrams)` | que cada elemento dibujado tiene su ficha (un visor puede explicarlo todo) |
| `assertValidBpmn(xml)` | lo mismo para un documento suelto |
| `pathOf(history, instanceId)` | los ids de los nodos que registró una instancia, en orden |

Un fallo es un `AssertionError` con el detalle; no depende de ninguna librería de aserciones.

### 3. Mensajes fiables con Spring Modulith

Con `@EventListener`, el proceso de pago arranca en el mismo hilo y en la misma transacción que la solicitud. Si el
pago falla, se deshace también la solicitud; y si la aplicación cae después de confirmar, nada reintenta.

Spring Modulith resuelve las dos cosas: `@ApplicationModuleListener` entrega el mensaje **después** de confirmar la
transacción de quien lo publica, en una transacción propia, y su **registro de publicaciones** guarda cada envío hasta
que el listener termina, para reintentarlo si hace falta. Esa entrega es *al menos una vez*: por eso el pago se inicia
con `startProcessInstanceIfAbsent` desde la lección 7.

Añade el BOM de Spring Modulith y sus *starters*:

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

En `ExpensePaymentProcess`, cambia el listener. El procesador lo acepta porque `@ApplicationModuleListener` lleva
dentro `@EventListener`:

```java
import org.springframework.modulith.events.ApplicationModuleListener;
```

```java
	@ApplicationModuleListener
	@MessageStartEvent(id = ORDER_RECEIVED, name = "Payment order received", messageRef = PaymentOrdered.class,
			outgoing = @SequenceFlow(targetRef = TRANSFER))
	public void on(PaymentOrdered order) {
		// … sin cambios …
	}
```

(y borra el import de `org.springframework.context.event.EventListener`).

### 4. Ensancha la columna del registro

El registro de publicaciones JPA guarda cada mensaje serializado en JSON, en una columna que Hibernate crea de 255
caracteres. Con la cabecera, una orden de pago ocupa más, y la publicación falla:

```
Value too long for column "SERIALIZED_EVENT CHARACTER VARYING(255)"
```

(en un sistema en español, H2 dice `Valor demasiado largo para la columna…`). Ensánchala con un `orm.xml` en la
aplicación:

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

Si las tablas las crean tus migraciones (paso 7), usa directamente un tipo de texto largo para esa columna.

### 5. Verifica los límites entre módulos

Ya tienes dos módulos, `claim` y `payment`, y `payment` depende de `claim` (los contratos de mensaje están en `claim`),
nunca al revés. `bpmncode` declara sus paquetes como módulo de Spring Modulith, con su API pública en interfaces con
nombre (`annotation`, `model`, `runtime`, `statistics`, `trace`, `i18n`, `bpmn`). Añade su paquete como base adicional
y Modulith comprobará también que no usas paquetes internos de la librería:

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

Declara qué usa cada módulo:

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

`AnnotationProcessModelReader` (paquete `model.reader`) es interno: úsalo solo en pruebas sin Spring, como
`ProcessModelTests`, que no están sujetas a esta verificación.

### 6. Una prueba de punta a punta

Esta prueba cruza los dos procesos: solicitud, aprobación, pago y confirmación del banco. Como el pago arranca ahora
después de confirmar la transacción, y en otro hilo, la prueba **espera** a que exista su instancia antes de confirmar.

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

`history.instances(correlationKey)` las ordena por inicio: primero la solicitud, después el pago.

### 7. Configuración por entorno

Separa lo común de lo que depende del entorno: `spring.datasource.url`, `spring.jpa.hibernate.ddl-auto` y
`pac.process.web.enabled` salen de `application.properties` y pasan a un fichero por perfil.

`src/main/resources/application.properties` (común):

```properties
spring.application.name=expenses
spring.jpa.open-in-view=false
spring.messages.basename=messages,io/github/eldiegos/bpmncode/i18n/messages
spring.messages.fallback-to-system-locale=false
pac.process.store.type=jpa
```

`src/main/resources/application-dev.properties` (tu equipo: H2 en fichero, Hibernate crea tus tablas y el visor está
activo):

```properties
spring.datasource.url=jdbc:h2:file:./data/expenses
spring.jpa.hibernate.ddl-auto=update
pac.process.web.enabled=true
```

Arranca en desarrollo con `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. Sin perfil, la aplicación usa una
base de datos H2 en memoria y el visor no existe (`/bpmncode/viewer.html` da 404).

En producción (`application-prod.properties`, con la base de datos real):

```properties
spring.datasource.url=jdbc:postgresql://db.example.com/expenses
spring.jpa.hibernate.ddl-auto=none
pac.process.store.initialize-schema=never
```

- **`pac.process.store.type=jpa`** en lo común: si falta JPA, la aplicación no arranca en vez de caer en otro almacén.
- **Las tablas, por migraciones.** El script de la librería va dentro de su jar, en
  `io/github/eldiegos/bpmncode/runtime/schema.sql` (constante `JdbcProcessInstanceStore.SCHEMA`). Está escrito para H2
  y PostgreSQL. Copia su contenido a una migración de Flyway o Liquibase (adáptalo si usas otra base de datos), junto
  con tu tabla `expense_claim` y la `event_publication` de Modulith, y deja `initialize-schema=never`.
- **El visor, solo donde toca.** En producción, o no lo actives o protégelo: con Spring Security, sus rutas
  (`/bpmncode/**`) quedan sujetas a tus reglas como cualquier otra.
- **`pac/message-contracts.lock`** va al control de versiones y se revisa en cada cambio de un mensaje.

## Comprueba que funciona

```bash
./mvnw test
```

Pasan todas: `ProcessModelTests`, `ExpenseClaimProcessTests`, `ExpenseJourneyTests` y `ModularityTests`. Después:

- `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`: el log dice `Process instances are stored with
  JpaProcessInstanceStore` y el visor responde en `/bpmncode/viewer.html`.
- Envía y aprueba una solicitud grande: la instancia de `EXP-PAYMENT` aparece un instante después de que responda la
  aprobación, porque se inicia tras confirmar la transacción.

### Lista de comprobación para producción

| | Qué | Lección |
|---|---|---|
| ☐ | El procesador de anotaciones está en `annotationProcessors` (con los demás procesadores) | 1, 4 |
| ☐ | Hay una prueba del modelo y pruebas de los caminos principales | 4, 5, 12 |
| ☐ | La evidencia no lleva datos personales (claves de negocio y resultados con códigos y referencias) | 5 |
| ☐ | Las tareas que esperan comprueban el turno (`isWaitingAt`) antes de llamar al journal | 6, 7 |
| ☐ | Los inicios por mensaje son idempotentes (`startProcessInstanceIfAbsent`) | 7 |
| ☐ | `pac/message-contracts.lock` existe y está versionado | 7 |
| ☐ | Almacén explícito (`pac.process.store.type`) y transacciones en las entradas | 8 |
| ☐ | Tablas creadas por migraciones, `initialize-schema=never` | 8, 12 |
| ☐ | Cada versión vieja se retira solo cuando no quedan instancias activas suyas | 9 |
| ☐ | Mensajes con `@ApplicationModuleListener` y columna del registro ampliada | 12 |
| ☐ | El módulo web, apagado o protegido | 11, 12 |
| ☐ | `ApplicationModules.verify()` en las pruebas | 12 |

## Errores frecuentes

- **`Value too long for column "SERIALIZED_EVENT …"`** al publicar con el registro de Modulith: falta el paso 4.
- **La prueba de punta a punta falla con `No EXP-PAYMENT instance for correlation …`.** El pago aún no había
  arrancado: espera con Awaitility, como en el paso 6.
- **`ApplicationModules.verify()` falla** porque un módulo usa un paquete interno de la librería (`model.reader`,
  `runtime.jpa`, `web`, `processor`) o una interfaz que no declaró en `allowedDependencies`. Usa solo la API pública o
  declárala.
- **El jar de pruebas no se encuentra.** Revisa `<classifier>tests</classifier>` y `<type>test-jar</type>`.

## Ejercicio

Tu equipo quiere su propio almacén de instancias (por ejemplo, en MongoDB). Sin escribirlo entero: ¿qué hace falta para
que la librería lo use y cómo comprobarías que cumple el contrato?

<details><summary>Solución</summary>

- Implementar `ProcessInstanceStore` y declararlo como bean: la autoconfiguración deja de crear el suyo
  (`@ConditionalOnMissingBean`).
- Probarlo extendiendo `io.github.eldiegos.bpmncode.runtime.ProcessInstanceStoreContract`, del mismo jar de pruebas
  que `ProcessAssertions`, e
  implementando `newStore()` para que devuelva un almacén vacío. El contrato comprueba un proceso completo, una instancia
  por proceso y correlación, que no se guarda nada si un paso no es conforme y el bloqueo optimista.

```java
class MongoProcessInstanceStoreTests extends ProcessInstanceStoreContract {

	@Override
	protected ProcessInstanceStore newStore() {
		return new MongoProcessInstanceStore(…);
	}
}
```

Las consultas por versión (`instances(processDefinitionRef)`, `flowNodeInstances(processDefinitionRef)`) tienen
implementación por defecto, pero con muchas instancias conviene resolverlas con una sola consulta: las usan las
estadísticas.

</details>

## Para saber más

- [Configuración: pruebas de la aplicación](../configuracion.md#pruebas-de-la-aplicación) y
  [`ProcessAssertions`](../configuracion.md#processassertions).
- [Configuración: Spring Modulith](../configuracion.md#spring-modulith) y [propiedades](../configuracion.md#propiedades).
- [Almacenamiento: crear las tablas](../almacenamiento.md#crear-las-tablas) y
  [un almacén propio](../almacenamiento.md#un-almacén-propio).
- [Mensajes: persistencia del mensaje](../mensajes.md#persistencia-del-mensaje).

---

[← Anterior: Estadísticas y visor web](11-estadisticas-y-visor-web.md) | [Índice](README.md)

¡Enhorabuena! Has terminado el curso. A partir de aquí, el [manual de referencia](../primeros-pasos.md) tiene todos
los detalles.
