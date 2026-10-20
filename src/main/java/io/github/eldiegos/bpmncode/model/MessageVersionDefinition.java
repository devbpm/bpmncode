package io.github.eldiegos.bpmncode.model;

import java.util.Objects;

/**
 * Versión de un {@link Message}: un record anotado con {@link io.github.eldiegos.bpmncode.annotation.MessageVersion}.
 * <p>
 * Responsabilidad: ligar el número de versión con el tipo Java que la transporta y con el esquema congelado de su
 * carga.
 * <p>
 * Invariantes: la versión es mayor que cero; el {@link ItemDefinition} apunta al record de la versión.
 *
 * @param version número de versión
 * @param itemDefinition estructura de la versión: el record Java
 * @param schema esquema JSON de la carga y su huella
 * @see Message#versions()
 */
public record MessageVersionDefinition(int version, ItemDefinition itemDefinition, MessageSchema schema) {

	public MessageVersionDefinition {
		if (version < 1) {
			throw new IllegalArgumentException("A message version is greater than zero");
		}
		Objects.requireNonNull(itemDefinition, "itemDefinition");
		Objects.requireNonNull(schema, "schema");
	}

	/**
	 * Clave de la versión en {@code pac/message-contracts.lock}.
	 *
	 * @param messageId identificador del mensaje
	 * @return {@code <messageId>@<version>}
	 */
	public String lockKey(String messageId) {
		return messageId + "@" + version;
	}
}
