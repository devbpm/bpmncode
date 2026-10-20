# Versions

> 🇪🇸 [Versión en español](../es/versiones.md)

`bpmncode` versions separately three things that evolve at different paces:

| What | How it is declared | Reference |
|---|---|---|
| Process | `@Process(version = n)`, one class per version | `ProcessDefinitionRef("ORDER", 2)` |
| Business rule | `@BusinessRule(version = n)`, one type or method per version | `BusinessRuleRef`, as text `R-PER-001@2` |
| Message between processes | `@MessageVersion(n)`, one record per version | `MessageVersionRef`, as text `EV-PERSON-REGISTERED@2` |

Messages have their own guide: [Messages between processes](messages.md).

## Process versions

Each version is a `@Process` class with the same `id` and a different `version`. The versions coexist in the same
application:

```java
@Process(id = "PER-REGISTRATION", name = "Person registration", version = 1)
public class PersonRegistration { … }          // requires age 16 (R-PER-001@1)

@Process(id = "PER-REGISTRATION", name = "Person registration", version = 2)
public class PersonRegistrationV2 { … }        // requires age 18 and a document (R-PER-001@2, R-PER-003)
```

- **Whoever starts chooses the version.** `journal.startProcessInstance(ProcessDefinitionRef.of("PER-REGISTRATION", 2), …)`.
  Naturally, each version has its own start contract (the parameter of the `@StartEvent` method) and each version of
  the application's API starts one version of the process.
- **The instance stays bound to its version.** Conformance is always checked against the definition it started with,
  so running v1 instances finish as v1 even when v2 already exists.
- **Node ids may repeat across versions** of the same process (and usually do), but not across different processes.
- **`@ProcessPart(processRef, version)`** states which version a helper class contributes to. If the process has
  several versions, the version is mandatory.
- **Current version**: the highest. `ProcessDefinitionRegistry.processDefinition("PER-REGISTRATION")` and
  `Definitions.process(id)` return it; `Definitions.versions(id)` returns all of them, from oldest to current, and
  `isCurrent(ref)` tells whether a version is the current one.
- **Diagrams**: the one of the current version has the process id (`PER-REGISTRATION`) and each earlier version its
  own (`PER-REGISTRATION-v1`). Each diagram draws a single version of each process. In BPMN, the version is exported
  as `pac:version`.

### When to create a version

| Change | New version? |
|---|---|
| Refactoring without changing the flow or the rules | no |
| Changing texts (`name`, `documentation`) | no |
| Adding, removing or reordering nodes or flows | yes: running instances must follow the flow they started with |
| Applying another version of a rule | yes, if instances must be able to tell which rule was applied to them |
| Changing the start contract in an incompatible way | yes, and the previous version coexists while someone uses it |

To retire a version, stop starting it and delete it when no active instances of it remain
(`ProcessInstanceHistory.instances()` filtered by `processVersion()`).

## Business rule versions

A rule evolves independently of the processes using it, so it has its own version. Each version is a different type
or method with `@BusinessRule`, and earlier ones are not touched:

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

- **Referencing**: `businessRuleRefs = MinimumAgeRule.REFERENCE_V2`. Without a version (`"R-PER-001"`) it is only
  valid if the rule has one; with several, the application does not start (`AMBIGUOUS_REFERENCE`). A non-existent
  version is `UNKNOWN_BUSINESS_RULE_REF`, and a repeated one, `DUPLICATE_ID`.
- **Recording**: store the versioned reference in the node outcome
  (`FlowNodeOutcome.of("RULES_VIOLATED", "R-PER-001@2")`), so the history always tells which version was applied to
  each case.
- **Querying**: `Definitions.businessRule(ref)` and `businessRuleVersions(id)`; traceability lists the versions each
  node applies.

| Change | What to do |
|---|---|
| The threshold or the logic changes | new version |
| The meaning changes | new rule, with another id |
| Refactoring without behaviour change | same version |
