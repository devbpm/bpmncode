package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Mensaje que una instancia recibió al iniciarse o publicó al terminar, tal como quedó registrado.
 * <p>
 * Responsabilidad: trazar, en las vistas de gobierno, qué envío concreto (y de qué versión del contrato) inició o
 * cerró cada instancia y qué instancia lo publicó.
 * <p>
 * Invariantes: ningún componente es nulo; en un mensaje publicado, el origen es la propia instancia.
 *
 * @param messageId identificador del envío
 * @param message mensaje y versión
 * @param source versión del proceso que lo publicó
 * @param sourceInstanceId instancia que lo publicó
 * @param occurredAt momento de la publicación
 * @see ProcessInstanceSummary#triggeringMessage()
 */
public record ExchangedMessage(MessageId messageId, MessageVersionRef message, ProcessDefinitionRef source,
		UUID sourceInstanceId, Instant occurredAt) {

	public ExchangedMessage {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
		Objects.requireNonNull(occurredAt, "occurredAt");
	}

	/**
	 * Registra un mensaje a partir de su cabecera.
	 *
	 * @param message mensaje y versión
	 * @param header cabecera del mensaje
	 * @return el mensaje registrado
	 */
	static ExchangedMessage of(MessageVersionRef message, MessageHeader header) {
		return new ExchangedMessage(header.messageId(), message, header.source(), header.sourceInstanceId(),
				header.occurredAt());
	}
}
