package io.github.eldiegos.bpmncode.model;

/**
 * Referencia a una versión concreta de un mensaje.
 * <p>
 * Responsabilidad: nombrar una versión de mensaje en evidencias, consultas y en {@code pac/message-contracts.lock}.
 * <p>
 * Invariantes: el identificador nunca está vacío y la versión es mayor que cero; se escribe {@code <messageId>@<version>}.
 *
 * @param messageId identificador del mensaje
 * @param version número de versión
 * @see Definitions#messageVersionOf(Class)
 */
public record MessageVersionRef(String messageId, int version) {

	public MessageVersionRef {
		if (messageId == null || messageId.isBlank() || version < 1) {
			throw new IllegalArgumentException("A message version reference needs the message id and a version above zero");
		}
	}

	@Override
	public String toString() {
		return messageId + "@" + version;
	}
}
