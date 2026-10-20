package io.github.eldiegos.bpmncode.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.eldiegos.bpmncode.model.MessageVersionRef;

/**
 * Resultado de un nodo de flujo registrado en la evidencia de una instancia, neutro respecto al idioma.
 * <p>
 * Responsabilidad: dar forma única al resultado (un código y un detalle opcional) para que la interfaz pueda traducirlo
 * con la clave {@code evidence.<código>} y para que se guarde siempre igual.
 * <p>
 * Invariantes: el código está en mayúsculas ({@code [A-Z][A-Z0-9_]*}); el detalle, si existe, no está vacío. El formato
 * persistido es {@code CÓDIGO} o {@code CÓDIGO:detalle} y {@link #parse(String)} es su inversa exacta.
 *
 * @param code código del resultado, p. ej. {@code RULES_VIOLATED}
 * @param detail detalle no traducible, p. ej. {@code R-PER-001}
 * @see HistoricFlowNodeInstance
 */
public record FlowNodeOutcome(String code, Optional<String> detail) {

	/** Código que registra el journal para un gateway: la constante devuelta y el nodo al que lleva, p. ej. {@code ACCEPT → ST-WORK}. */
	public static final String SEQUENCE_FLOW_SELECTED = "SEQUENCE_FLOW_SELECTED";

	/**
	 * Código que registra el journal al publicar un mensaje: en un evento de fin, con la versión ({@code EV-ORDERED@2});
	 * en una tarea de envío, con la versión y el identificador del envío ({@code EV-ORDERED@2 <messageId>}).
	 */
	public static final String MESSAGE_PUBLISHED = "MESSAGE_PUBLISHED";

	/** Código que registra el journal al terminar en un evento de fin de error, con el código del error. */
	public static final String ERROR_THROWN = "ERROR_THROWN";

	/** Código de un evento de inicio que recibe un mensaje, con la versión; lo usa {@link #messageReceived(MessageVersionRef)}. */
	public static final String MESSAGE_RECEIVED = "MESSAGE_RECEIVED";

	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
	private static final char SEPARATOR = ':';

	public FlowNodeOutcome {
		if (code == null || !CODE.matcher(code).matches()) {
			throw new IllegalArgumentException("Invalid outcome code: " + code);
		}
		Objects.requireNonNull(detail, "detail");
		if (detail.filter(String::isBlank).isPresent()) {
			throw new IllegalArgumentException("Outcome detail must not be blank");
		}
	}

	/**
	 * Crea un resultado sin detalle.
	 *
	 * @param code código del resultado
	 * @return el resultado
	 */
	public static FlowNodeOutcome of(String code) {
		return new FlowNodeOutcome(code, Optional.empty());
	}

	/**
	 * Crea un resultado con detalle.
	 *
	 * @param code código del resultado
	 * @param detail detalle no vacío
	 * @return el resultado
	 */
	public static FlowNodeOutcome of(String code, String detail) {
		return new FlowNodeOutcome(code, Optional.of(detail));
	}

	/**
	 * Resultado de un evento de inicio que recibe un mensaje.
	 *
	 * @param message mensaje y versión recibidos
	 * @return {@code MESSAGE_RECEIVED} con detalle {@code <messageId>@<versión>}
	 */
	public static FlowNodeOutcome messageReceived(MessageVersionRef message) {
		return of(MESSAGE_RECEIVED, message.toString());
	}

	/**
	 * Convierte la forma persistida en un resultado.
	 *
	 * @param stored texto con el formato {@code CÓDIGO} o {@code CÓDIGO:detalle}
	 * @return el resultado
	 * @throws IllegalArgumentException si el texto no tiene ese formato
	 */
	public static FlowNodeOutcome parse(String stored) {
		Objects.requireNonNull(stored, "stored");
		var separator = stored.indexOf(SEPARATOR);
		return separator < 0 ? of(stored) : of(stored.substring(0, separator), stored.substring(separator + 1));
	}

	@Override
	public String toString() {
		return detail.map(value -> code + SEPARATOR + value).orElse(code);
	}
}
