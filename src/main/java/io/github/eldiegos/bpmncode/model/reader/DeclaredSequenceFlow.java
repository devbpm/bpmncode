package io.github.eldiegos.bpmncode.model.reader;

/**
 * Flujo de secuencia tal y como lo declara un {@code @SequenceFlow} en {@code outgoing}, antes de comprobar su destino.
 * <p>
 * Responsabilidad: conservar lo declarado hasta construir el grafo.
 * <p>
 * Invariantes: solo los flujos que salen de un gateway llevan nombre, condición y constante.
 *
 * @see ProcessGraphBuilder
 */
sealed interface DeclaredSequenceFlow permits DeclaredSequenceFlow.Unconditional, DeclaredSequenceFlow.Conditional {

	/** Identificador declarado del flujo, posiblemente vacío (se genera {@code Flow_<origen>_<destino>}). */
	String id();

	/** Identificador del nodo destino declarado (puede no existir). */
	String targetRef();

	/**
	 * Flujo sin condición, el único que sale de un evento de inicio o de una tarea.
	 * <p>
	 * Responsabilidad: apuntar al nodo siguiente.
	 * <p>
	 * Invariantes: el destino no está vacío.
	 *
	 * @param id identificador declarado, posiblemente vacío
	 * @param targetRef nodo destino declarado
	 * @see DeclaredSequenceFlow
	 */
	record Unconditional(String id, String targetRef) implements DeclaredSequenceFlow {
	}

	/**
	 * Flujo que sale de un gateway exclusivo.
	 * <p>
	 * Responsabilidad: apuntar al nodo que sigue cuando el gateway devuelve {@code when}.
	 * <p>
	 * Invariantes: nombre y condición tienen valor (por defecto, la constante).
	 *
	 * @param id identificador declarado, posiblemente vacío
	 * @param targetRef nodo destino declarado
	 * @param name etiqueta canónica
	 * @param conditionExpression condición canónica
	 * @param when constante que selecciona la rama
	 * @see DeclaredSequenceFlow
	 */
	record Conditional(String id, String targetRef, String name, String conditionExpression, String when)
			implements DeclaredSequenceFlow {
	}
}
