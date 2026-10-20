package io.github.eldiegos.bpmncode.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador único de un mensaje publicado.
 * <p>
 * Responsabilidad: distinguir cada envío de un mensaje, aunque dos envíos tengan la misma correlación, para detectar
 * entregas repetidas y trazar qué mensaje inició cada instancia.
 * <p>
 * Invariantes: siempre tiene valor; solo se crea con {@link #newId()} o {@link #of(UUID)}.
 *
 * @param value valor del identificador
 * @see MessageHeader
 */
public record MessageId(UUID value) {

	public MessageId {
		Objects.requireNonNull(value, "value");
	}

	/** Crea el identificador de un mensaje nuevo. */
	public static MessageId newId() {
		return new MessageId(UUID.randomUUID());
	}

	/** Reconstruye un identificador conocido. */
	public static MessageId of(UUID value) {
		return new MessageId(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
