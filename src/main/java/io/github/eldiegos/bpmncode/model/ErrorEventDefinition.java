package io.github.eldiegos.bpmncode.model;

/**
 * Definición de evento de error BPMN ({@code bpmn:errorEventDefinition}).
 * <p>
 * Responsabilidad: indicar con qué {@link ErrorDefinition error} termina un evento de fin de error.
 * <p>
 * Invariantes: {@code errorRef} es el identificador de un error declarado y nunca está vacío.
 *
 * @param errorRef identificador del error (atributo {@code errorRef})
 * @see FlowNode.EndEvent#error()
 */
public record ErrorEventDefinition(String errorRef) implements EventDefinition {

	public ErrorEventDefinition {
		if (errorRef == null || errorRef.isBlank()) {
			throw new IllegalArgumentException("An error event definition needs an errorRef");
		}
	}
}
