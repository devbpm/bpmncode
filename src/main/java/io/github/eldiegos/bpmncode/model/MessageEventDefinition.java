package io.github.eldiegos.bpmncode.model;

import java.util.List;

/**
 * Definición de evento de mensaje BPMN ({@code bpmn:messageEventDefinition}).
 * <p>
 * Responsabilidad: indicar qué {@link Message} recibe un evento de inicio o publica un evento de fin, y en qué
 * versiones.
 * <p>
 * Invariantes: {@code messageRef} es el identificador de un mensaje declarado y nunca está vacío; hay al menos una
 * versión, sin repetir y en orden; un evento de fin publica exactamente una, un evento de inicio acepta una o varias.
 *
 * @param messageRef identificador del mensaje (atributo {@code messageRef})
 * @param versions versión publicada (evento de fin) o versiones aceptadas (evento de inicio)
 * @see FlowNode.Event#eventDefinition()
 */
public record MessageEventDefinition(String messageRef, List<Integer> versions) implements EventDefinition {

	public MessageEventDefinition {
		if (messageRef == null || messageRef.isBlank()) {
			throw new IllegalArgumentException("A message event definition needs a messageRef");
		}
		versions = versions.stream().distinct().sorted().toList();
		if (versions.isEmpty()) {
			throw new IllegalArgumentException("A message event definition needs at least one version");
		}
	}

	/**
	 * Indica si acepta una versión.
	 *
	 * @param version número de versión
	 * @return {@code true} si está entre las versiones
	 */
	public boolean accepts(int version) {
		return versions.contains(version);
	}

	/**
	 * Versión que publica un evento de fin.
	 *
	 * @return la primera (y, en un evento de fin, única) versión
	 */
	public int publishedVersion() {
		return versions.getFirst();
	}
}
