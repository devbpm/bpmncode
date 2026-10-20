# Lección 8. Persistencia: memoria, JDBC y JPA

> 🇬🇧 [English version](../../en/course/08-persistence.md)

**Objetivo:** que las instancias, su evidencia y las solicitudes sobrevivan a un reinicio y se guarden en la misma
transacción.

## Lo que vas a aprender

- Qué almacén de instancias elige la librería según lo que tenga tu aplicación, y cómo forzar uno.
- Qué tablas usa y cuándo las crea.
- Por qué la evidencia y tus datos de negocio deben confirmarse o deshacerse juntos, y cómo conseguirlo con
  `@Transactional`.

## Punto de partida

El código de la [lección 7](07-mensajes-entre-procesos.md): los procesos `EXP-CLAIM` y `EXP-PAYMENT` se comunican por
mensajes, las solicitudes se guardan en un mapa en memoria (`ExpenseClaims`) y no hay base de datos. Al arrancar ves
`Process instances are stored with InMemoryProcessInstanceStore`.

## Pasos

### 1. Lo que falla hoy

Envía una solicitud grande, para la aplicación y vuelve a arrancarla. La instancia ha desaparecido: el almacén en
memoria lo pierde todo al parar. Además **no es transaccional**: si tu código falla después de un paso del journal,
ese paso queda registrado. Sirve para pruebas y demostraciones, no para producción.

La librería trae tres almacenes y elige uno al arrancar (`pac.process.store.type=auto`, el valor por defecto):

| La aplicación tiene… | Almacén |
|---|---|
| JPA (un `EntityManagerFactory` que gestiona las entidades de la librería) | `JpaProcessInstanceStore` |
| un `DataSource` y `spring-jdbc`, sin JPA | `JdbcProcessInstanceStore` |
| nada | `InMemoryProcessInstanceStore` |

Los almacenes JDBC y JPA usan las mismas dos tablas, `pac_process_instance` y `pac_flow_node_instance`, así que puedes
pasar de uno a otro sin migrar datos.

### 2. Primero, JDBC

Añade al `pom.xml` una base de datos H2 y el *starter* de JDBC:

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

Y guarda la base de datos en un fichero, para que sobreviva a los reinicios.

`src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:h2:file:./data/expenses
```

No hace falta nada más. Al arrancar:

```
Process instances are stored with JdbcProcessInstanceStore
```

La librería ha creado las tablas: con `pac.process.store.initialize-schema=embedded` (el valor por defecto) ejecuta su
script solo si la base de datos es H2. Con otra base de datos, las tablas son cosa de tus migraciones (lección 12).

Repite la prueba del paso 1: envía una solicitud de 1.800 EUR, reinicia y consulta
`GET /claims/{claimId}/instances`. La instancia sigue ahí, esperando en `EXP-APPROVE`. Pero si la apruebas, responde
`500`: `claims.find(claimId).orElseThrow()` no encuentra la solicitud, porque `ExpenseClaims` sigue en memoria. La
evidencia ha sobrevivido; tus datos, no. Hay que guardarlos en la misma base de datos.

### 3. Después, JPA

Tus datos de negocio irán en una entidad JPA. Sustituye `spring-boot-starter-jdbc` por el *starter* de JPA (que ya
incluye JDBC):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
```

Con JPA presente, la librería añade sus dos entidades (paquete `io.github.eldiegos.bpmncode.runtime.jpa`) a tu unidad
de persistencia y usa el almacén JPA: mismo proveedor, mismo dialecto y misma transacción que tus entidades.

Completa `application.properties`:

```properties
spring.datasource.url=jdbc:h2:file:./data/expenses
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
```

`ddl-auto=update` deja que Hibernate cree tu tabla `expense_claim` en desarrollo. `open-in-view=false` evita un aviso
de Spring Boot y mantiene las transacciones donde tú las pones.

### 4. Las solicitudes, como entidad

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

`ExpenseClaims` conserva su interfaz, así que el proceso no cambia por esto.

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

### 5. Una transacción por operación de negocio

Cada llamada a `ProcessJournal` está anotada con `@Transactional`: se une a la transacción de quien llama o abre una
propia. Si no abres ninguna, `submit` hace varias transacciones pequeñas (guardar la solicitud, iniciar, completar
`EXP-CHECK`…). Si algo falla a mitad, quedan la solicitud y media evidencia.

Pon `@Transactional` en los métodos por los que **entra** el trabajo: `submit` y `approve` (los llama el controlador)
y `confirm` (lo llama el webhook). Todo lo que hacen, incluidos tus datos, la evidencia y el inicio del proceso de pago
(el listener `@EventListener` se ejecuta en el mismo hilo y en la misma transacción), se confirma o se deshace junto.

En `ExpenseClaimProcess`, añade el import y anota los dos métodos públicos:

```java
import org.springframework.transaction.annotation.Transactional;
```

```java
	@Transactional
	@StartEvent(id = SUBMITTED, name = "Claim submitted", outgoing = @SequenceFlow(targetRef = CHECK))
	public ProcessInstanceRef submit(ExpenseClaim claim) {
		// … sin cambios …
	}

	@Transactional
	@UserTask(id = APPROVE, name = "Approve claim", outgoing = @SequenceFlow(targetRef = DECISION))
	public void approve(UUID claimId, Decision decision) {
		// … sin cambios …
	}
```

En `ExpensePaymentProcess`, igual con `confirm`:

```java
	@Transactional
	@MessageEndEvent(id = CONFIRMED, name = "Payment confirmed", messageRef = PaymentConfirmed.class)
	public void confirm(UUID claimId, String bankReference) {
		// … sin cambios …
	}
```

`@Transactional` funciona porque Spring llama a estos métodos a través del *proxy* del bean. Las llamadas internas
(`submit` → `check`) no abren otra transacción, ni falta que hace: ya están dentro de la de `submit`.

Ahora la conformidad protege también tus datos: si el journal rechaza un paso, la excepción deshace la transacción
entera, solicitud incluida.

### 6. Elegir el almacén a mano

`auto` es cómodo, pero en producción conviene decirlo explícitamente: si falta algo, la aplicación no arranca en lugar
de caer en otro almacén.

```properties
# auto (por defecto) | memory | jdbc | jpa
pac.process.store.type=jpa
# embedded (por defecto: solo H2) | always | never
pac.process.store.initialize-schema=embedded
```

## Comprueba que funciona

Si quieres empezar limpio, borra antes el directorio `data/`. Arranca:

```
Process instances are stored with JpaProcessInstanceStore
```

1. Envía una solicitud de 1.800 EUR y apunta su `claimId`.
2. Para la aplicación y vuelve a arrancarla.
3. Apruébala: `POST /claims/{claimId}/approval` con `{"decision":"APPROVED"}` responde `204`.
4. `GET /claims/{claimId}/instances` devuelve la instancia de `EXP-CLAIM` completada en `EXP-PAYMENT-ORDERED` y la de
   `EXP-PAYMENT` activa en `PAY-TRANSFER`, esperando la confirmación del banco (`nextFlowNodeIds` `["PAY-CONFIRMED"]`).

Las pruebas siguen pasando con `./mvnw test`, pero ahora usan el mismo fichero H2 que la aplicación: para la
aplicación antes de lanzarlas, porque H2 en fichero no admite dos procesos a la vez. En la lección 12 harás que las
pruebas no dependan de ese fichero.

## Errores frecuentes

- **`pac.process.store.type=jdbc` sin base de datos.** La aplicación no arranca:
  `pac.process.store.type=jdbc needs a DataSource and spring-jdbc`. Con `jpa`:
  `pac.process.store.type=jpa needs JPA (an EntityManagerFactory and spring-orm)`.
- **Tu aplicación declara `@EntityScan`.** Sustituye la búsqueda de entidades por defecto y deja fuera las de la
  librería. Con `auto` verás un aviso (`The persistence unit does not manage the bpmncode entities (an @EntityScan
  without the package io.github.eldiegos.bpmncode.runtime.jpa?): process instances are not stored with JPA`) y se usará
  JDBC; con `jpa`, no arranca. Incluye el paquete:
  `@EntityScan({ "com.example.expenses", "io.github.eldiegos.bpmncode.runtime.jpa" })`.
- **Escribir con el almacén JPA fuera de una transacción** falla con `TransactionRequiredException` en vez de perder el
  cambio en silencio. El journal abre la suya; solo lo verás si llamas al almacén directamente.
- **Avisos `HHH100030: Called joinTransaction() on a non-JTA EntityManager (ignoring)`** en cada paso, con Hibernate 7.
  Los provoca el almacén JPA al comprobar que hay transacción; son inofensivos. Si te molestan, baja el nivel de log de
  `org.hibernate.orm.jdbc` a `ERROR`.
- **Contar con el almacén en memoria para deshacer pasos.** No es transaccional: no deshace nada.

## Ejercicio

Tus entidades siguen en JPA, pero el equipo de base de datos prefiere que las tablas de procesos se escriban con JDBC.
Cámbialo sin tocar código. ¿Hay que migrar datos?

<details><summary>Solución</summary>

```properties
pac.process.store.type=jdbc
```

Al arrancar verás `Process instances are stored with JdbcProcessInstanceStore`. No hay que migrar nada: los dos
almacenes usan las mismas tablas. El almacén JDBC escribe con la conexión de la transacción en curso, así que
`@Transactional` sigue agrupando tus entidades JPA y la evidencia. Vuelve a `auto` (o `jpa`) para seguir el curso.

</details>

## Para saber más

- [Almacenamiento](../almacenamiento.md): [elegir el almacén](../almacenamiento.md#elegir-el-almacén),
  [con JPA](../almacenamiento.md#con-jpa), [tablas](../almacenamiento.md#tablas),
  [crear las tablas](../almacenamiento.md#crear-las-tablas) y [transacciones](../almacenamiento.md#transacciones).
- [Transacciones e idempotencia](../ejecucion.md#transacciones-e-idempotencia).
- [Un almacén propio](../almacenamiento.md#un-almacén-propio), si no usas una base de datos relacional.

---

[← Anterior: Mensajes entre procesos](07-mensajes-entre-procesos.md) | [Índice](README.md) | [Siguiente: Versiones de procesos y reglas →](09-versiones.md)
