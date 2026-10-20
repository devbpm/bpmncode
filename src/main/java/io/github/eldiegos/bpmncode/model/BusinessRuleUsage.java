package io.github.eldiegos.bpmncode.model;

import java.util.Objects;

/**
 * Nodo de flujo que aplica una versión de una regla de negocio.
 * <p>
 * Responsabilidad: decir dónde se usa una regla: qué versión de proceso y qué nodo la referencian.
 * <p>
 * Invariantes: el nodo pertenece a esa versión del proceso y referencia la regla en sus {@code businessRuleRefs}.
 *
 * @param process proceso y versión
 * @param flowNode nodo que la referencia
 * @see Definitions#usagesOf(BusinessRuleRef)
 */
public record BusinessRuleUsage(ProcessDefinitionRef process, FlowNode flowNode) {

	public BusinessRuleUsage {
		Objects.requireNonNull(process, "process");
		Objects.requireNonNull(flowNode, "flowNode");
	}
}
