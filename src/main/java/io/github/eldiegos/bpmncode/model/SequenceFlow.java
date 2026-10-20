package io.github.eldiegos.bpmncode.model;

import java.util.Objects;

/**
 * Flujo de secuencia BPMN ({@code bpmn:sequenceFlow}) entre dos nodos de la misma versión de un proceso.
 * <p>
 * Responsabilidad: indicar qué nodo puede seguir a otro; la conformidad en ejecución solo admite avances por flujos
 * declarados.
 * <p>
 * Invariantes: identificador, origen y destino nunca están vacíos; un flujo condicional siempre sale de un gateway
 * exclusivo y lleva su etiqueta, su condición y la constante que lo selecciona.
 *
 * @see ProcessDefinition#sequenceFlows()
 */
public sealed interface SequenceFlow permits SequenceFlow.Unconditional, SequenceFlow.Conditional {

	/** Identificador estable del flujo (atributo {@code id}). */
	String id();

	/** Proceso al que pertenece. */
	String processId();

	/** Nodo de origen (atributo {@code sourceRef}). */
	String sourceRef();

	/** Nodo de destino (atributo {@code targetRef}). */
	String targetRef();

	/**
	 * Flujo sin condición.
	 * <p>
	 * Responsabilidad: encadenar dos nodos sin condición.
	 * <p>
	 * Invariantes: todos sus campos tienen valor.
	 *
	 * @param id identificador estable del flujo
	 * @param processId proceso al que pertenece
	 * @param sourceRef nodo de origen
	 * @param targetRef nodo de destino
	 * @see SequenceFlow
	 */
	record Unconditional(String id, String processId, String sourceRef, String targetRef) implements SequenceFlow {

		public Unconditional {
			requireAll(id, processId, sourceRef, targetRef);
		}
	}

	/**
	 * Flujo condicional: una rama de un gateway exclusivo.
	 * <p>
	 * Responsabilidad: unir el gateway con el nodo que sigue cuando su método devuelve {@code when}.
	 * <p>
	 * Invariantes: todos sus campos tienen valor.
	 *
	 * @param id identificador estable del flujo
	 * @param processId proceso al que pertenece
	 * @param sourceRef gateway de origen
	 * @param targetRef nodo de destino
	 * @param name etiqueta canónica, p. ej. {@code Yes}
	 * @param conditionExpression condición legible canónica (elemento {@code bpmn:conditionExpression})
	 * @param when constante del enum del gateway que selecciona esta rama (extensión {@code pac:when})
	 * @see SequenceFlow
	 */
	record Conditional(String id, String processId, String sourceRef, String targetRef, String name,
			String conditionExpression, String when) implements SequenceFlow {

		public Conditional {
			requireAll(id, processId, sourceRef, targetRef, name, conditionExpression, when);
		}
	}

	private static void requireAll(String... values) {
		for (var value : values) {
			if (Objects.requireNonNull(value, "sequence flow field").isBlank()) {
				throw new IllegalArgumentException("Sequence flow fields must not be blank");
			}
		}
	}
}
