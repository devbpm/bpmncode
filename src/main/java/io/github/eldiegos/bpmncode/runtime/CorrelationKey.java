package io.github.eldiegos.bpmncode.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Clave de correlación de negocio (BPMN {@code correlationKey}) que comparten todas las instancias de proceso nacidas
 * de un mismo hecho.
 * <p>
 * Responsabilidad: unir, por ejemplo, el alta de una persona con la verificación que provoca, sin que ningún proceso
 * conozca los identificadores internos del otro.
 * <p>
 * Invariantes: siempre tiene valor; solo se crea con {@link #newKey()} o {@link #of(UUID)}.
 *
 * @param value valor del identificador
 * @see ProcessInstanceRef
 */
public record CorrelationKey(UUID value) {

	public CorrelationKey {
		Objects.requireNonNull(value, "value");
	}

	/** Crea una correlación nueva para un hecho de negocio que empieza. */
	public static CorrelationKey newKey() {
		return new CorrelationKey(UUID.randomUUID());
	}

	/** Reconstruye una correlación conocida, p. ej. leída de la base de datos. */
	public static CorrelationKey of(UUID value) {
		return new CorrelationKey(value);
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
