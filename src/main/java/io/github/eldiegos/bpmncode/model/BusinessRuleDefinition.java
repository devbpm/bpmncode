package io.github.eldiegos.bpmncode.model;

import java.util.Objects;

/**
 * Versión de una regla de negocio descubierta a partir de {@code @BusinessRule}.
 * <p>
 * Responsabilidad: identificar una versión de una regla y el código que la implementa para la trazabilidad. BPMN no
 * modela las reglas: los nodos las referencian con la extensión {@code pac:businessRuleRef}.
 * <p>
 * Invariantes: identificador y nombre nunca están vacíos, la versión es mayor que cero y siempre hay una referencia al
 * código; dos versiones de la misma regla son definiciones distintas, cada una con su código.
 *
 * @param id identificador estable, p. ej. {@code R-PER-001}
 * @param version versión de la regla
 * @param name nombre canónico
 * @param documentation texto de negocio, posiblemente vacío
 * @param implementation tipo o método que implementa esta versión de la regla
 * @see FlowNode#businessRuleRefs()
 */
public record BusinessRuleDefinition(String id, int version, String name, String documentation,
		CodeReference implementation) {

	public BusinessRuleDefinition {
		if (id == null || id.isBlank() || name == null || name.isBlank()) {
			throw new IllegalArgumentException("Business rule id and name are mandatory");
		}
		if (version < 1) {
			throw new IllegalArgumentException("Business rule " + id + " must have a version >= 1");
		}
		Objects.requireNonNull(implementation, "implementation");
		documentation = documentation == null ? "" : documentation;
	}

	/**
	 * Referencia a esta versión de la regla.
	 *
	 * @return identificador y versión
	 */
	public BusinessRuleRef ref() {
		return BusinessRuleRef.of(id, version);
	}
}
