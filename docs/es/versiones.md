# Versiones

> 🇬🇧 [English version](../en/versions.md)

`bpmncode` versiona por separado tres cosas que evolucionan a ritmos distintos:

| Qué | Cómo se declara | Referencia |
|---|---|---|
| Proceso | `@Process(version = n)`, una clase por versión | `ProcessDefinitionRef("ORDER", 2)` |
| Regla de negocio | `@BusinessRule(version = n)`, un tipo o método por versión | `BusinessRuleRef`, en texto `R-PER-001@2` |
| Mensaje entre procesos | `@MessageVersion(n)`, un record por versión | `MessageVersionRef`, en texto `EV-PERSON-REGISTERED@2` |

Los mensajes tienen su propia guía: [Mensajes entre procesos](mensajes.md).

## Versiones de un proceso

Cada versión es una clase `@Process` con el mismo `id` y distinta `version`. Las versiones conviven en la misma
aplicación:

```java
@Process(id = "PER-REGISTRATION", name = "Person registration", version = 1)
public class PersonRegistration { … }          // exige 16 años (R-PER-001@1)

@Process(id = "PER-REGISTRATION", name = "Person registration", version = 2)
public class PersonRegistrationV2 { … }        // exige 18 años y documento (R-PER-001@2, R-PER-003)
```

- **Quien inicia elige la versión.** `journal.startProcessInstance(ProcessDefinitionRef.of("PER-REGISTRATION", 2), …)`.
  Lo natural es que cada versión tenga su propio contrato de inicio (el parámetro del método `@StartEvent`) y que cada
  versión de la API de la aplicación inicie una versión del proceso.
- **La instancia queda ligada a su versión.** La conformidad se comprueba siempre contra la definición con la que
  empezó, así que las instancias v1 en curso terminan como v1 aunque ya exista la v2.
- **Los ids de nodo pueden repetirse entre versiones** del mismo proceso (y es lo habitual), pero no entre procesos
  distintos.
- **`@ProcessPart(processRef, version)`** indica a qué versión contribuye una clase auxiliar. Si el proceso tiene
  varias versiones, la versión es obligatoria.
- **Versión vigente**: la mayor. `ProcessDefinitionRegistry.processDefinition("PER-REGISTRATION")` y
  `Definitions.process(id)` la devuelven; `Definitions.versions(id)` devuelve todas, de la más antigua a la vigente, y
  `isCurrent(ref)` dice si una versión es la vigente.
- **Diagramas**: el de la versión vigente tiene el id del proceso (`PER-REGISTRATION`) y cada versión anterior, el suyo
  (`PER-REGISTRATION-v1`). Cada diagrama dibuja una sola versión de cada proceso. En BPMN, la versión se exporta como
  `pac:version`.

### Cuándo crear una versión

| Cambio | ¿Versión nueva? |
|---|---|
| Refactorizar sin cambiar el flujo ni las reglas | no |
| Cambiar textos (`name`, `documentation`) | no |
| Añadir, quitar o reordenar nodos o flujos | sí: las instancias en curso deben seguir el flujo con el que empezaron |
| Aplicar otra versión de una regla | sí, si las instancias deben poder decir qué regla se les aplicó |
| Cambiar el contrato de inicio de forma incompatible | sí, y la versión anterior convive mientras haya quien la use |

### Retirar una versión

Para retirar una versión, deja de iniciarla y borra su clase `@Process` cuando no queden instancias activas suyas
(`ProcessInstanceHistory.instances(ProcessDefinitionRef.of("PER-REGISTRATION", 1))` filtrando las
`ProcessInstanceSummary.Active`).

Si se retira antes de tiempo, las instancias activas de esa versión no pueden avanzar: su siguiente paso lanza
`ProcessConformanceException` (`Process instance <id> runs PER-REGISTRATION v1, which is no longer deployed: keep the
@Process class of every version that still has active instances`). El historial y las vistas las siguen listando: las
completadas, como siempre; las activas, con `nextFlowNodeIds()` vacío. Para descubrirlo antes del primer paso, la
librería comprueba al arrancar si hay instancias activas de versiones que ya no están desplegadas. Consulta solo las
versiones con instancias activas (`ProcessInstanceStore#activeVersions()`), no las instancias. Qué hacer si las hay es
una decisión de la aplicación, con `pac.process.store.undeployed-versions`:

| Valor | Qué hace |
|---|---|
| `warn` (por defecto) | registra un aviso con las versiones afectadas y arranca |
| `fail` | no arranca |
| `ignore` | no lo comprueba (p. ej. si la aplicación lo vigila por su cuenta) |

```
Active process instances belong to versions that are no longer deployed: [PER-REGISTRATION v1]. They cannot continue until the @Process class of each version is deployed again (set pac.process.store.undeployed-versions=fail to stop the start-up instead)
```

Con `fail`, el arranque falla con una `IllegalStateException` con el mismo mensaje, terminado en
`(pac.process.store.undeployed-versions=fail)`. Si el almacén no puede consultarse al arrancar, con `warn` solo se
registra el aviso `Could not check whether active process instances belong to deployed versions: …` y la aplicación
arranca; con `fail`, no arranca.

Para recuperar esas instancias, vuelve a desplegar la clase `@Process` de su versión.

## Versiones de las reglas

Una regla evoluciona con independencia de los procesos que la usan, así que tiene su propia versión. Cada versión es
un tipo o un método distinto con `@BusinessRule`, y las anteriores no se tocan:

```java
public final class MinimumAgeRule {

	public static final String ID = "R-PER-001";
	public static final String REFERENCE_V1 = ID + BusinessRuleRef.SEPARATOR + 1;   // "R-PER-001@1"
	public static final String REFERENCE_V2 = ID + BusinessRuleRef.SEPARATOR + 2;   // "R-PER-001@2"

	@BusinessRule(id = ID, version = 1, name = "Minimum age of 16")
	public static Optional<Violation> check(BirthDate birthDate, LocalDate referenceDate) { … }

	@BusinessRule(id = ID, version = 2, name = "Minimum age of 18")
	public static Optional<Violation> checkAdult(BirthDate birthDate, LocalDate referenceDate) { … }
}
```

- **Referenciar**: `businessRuleRefs = MinimumAgeRule.REFERENCE_V2`. Sin versión (`"R-PER-001"`) solo vale si la regla
  tiene una; con varias, la aplicación no arranca (`AMBIGUOUS_REFERENCE`). Una versión inexistente es
  `UNKNOWN_BUSINESS_RULE_REF`, y una repetida, `DUPLICATE_ID`.
- **Registrar**: guarda la referencia con versión en el resultado del nodo
  (`FlowNodeOutcome.of("RULES_VIOLATED", "R-PER-001@2")`), así el historial dice siempre qué versión se aplicó a cada
  caso.
- **Consultar**: `Definitions.businessRule(ref)` y `businessRuleVersions(id)`; la trazabilidad lista las versiones que
  aplica cada nodo.

| Cambio | Qué hacer |
|---|---|
| Cambia el umbral o la lógica | versión nueva |
| Cambia el significado | regla nueva, con otro id |
| Refactorización sin cambio de comportamiento | misma versión |
