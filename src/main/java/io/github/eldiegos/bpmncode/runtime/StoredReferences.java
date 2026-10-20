package io.github.eldiegos.bpmncode.runtime;

import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Forma de texto con la que los almacenes guardan las referencias a mensajes y a versiones de proceso.
 * <p>
 * Responsabilidad: que todos los almacenes (los de la librería y los de la aplicación) escriban y lean las referencias
 * igual: {@code EV-PERSON-REGISTERED@2} para un mensaje y {@code PER-REGISTRATION:1} para un proceso.
 * <p>
 * Invariantes: cada {@code parse} es la inversa exacta de su {@code format}.
 *
 * @see ProcessInstanceStore
 */
public final class StoredReferences {

	private StoredReferences() {
	}

	/**
	 * Texto de una versión de mensaje.
	 *
	 * @param message mensaje y versión
	 * @return p. ej. {@code EV-PERSON-REGISTERED@2}
	 */
	public static String format(MessageVersionRef message) {
		return message.messageId() + "@" + message.version();
	}

	/**
	 * Lee una versión de mensaje.
	 *
	 * @param stored texto escrito por {@link #format(MessageVersionRef)}
	 * @return el mensaje y su versión
	 */
	public static MessageVersionRef parseMessage(String stored) {
		var separator = stored.lastIndexOf('@');
		return new MessageVersionRef(stored.substring(0, separator), Integer.parseInt(stored.substring(separator + 1)));
	}

	/**
	 * Texto de una versión de proceso.
	 *
	 * @param process proceso y versión
	 * @return p. ej. {@code PER-REGISTRATION:1}
	 */
	public static String format(ProcessDefinitionRef process) {
		return process.processId() + ":" + process.version();
	}

	/**
	 * Lee una versión de proceso.
	 *
	 * @param stored texto escrito por {@link #format(ProcessDefinitionRef)}
	 * @return el proceso y su versión
	 */
	public static ProcessDefinitionRef parseProcess(String stored) {
		var separator = stored.lastIndexOf(':');
		return ProcessDefinitionRef.of(stored.substring(0, separator), Integer.parseInt(stored.substring(separator + 1)));
	}
}
