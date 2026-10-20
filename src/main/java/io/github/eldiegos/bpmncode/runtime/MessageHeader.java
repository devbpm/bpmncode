package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Cabecera común a todo mensaje entre procesos (el sobre del mensaje).
 * <p>
 * Responsabilidad: llevar, separados de la carga de negocio, los datos que el framework necesita para correlacionar,
 * deduplicar y trazar: identificador del mensaje, correlación, momento y la instancia que lo publicó.
 * <p>
 * Invariantes: ningún componente es nulo; la construye {@link ProcessJournal#messageHeader(ProcessInstanceRef)} a partir
 * de la instancia que publica, así que la correlación es siempre la de esa instancia.
 *
 * @param messageId identificador único de este envío
 * @param correlationKey correlación de negocio que heredan las instancias que inicie
 * @param occurredAt momento de la publicación
 * @param source versión del proceso que lo publica
 * @param sourceInstanceId instancia que lo publica
 * @see ProcessMessage
 */
public record MessageHeader(MessageId messageId, CorrelationKey correlationKey, Instant occurredAt,
		ProcessDefinitionRef source, UUID sourceInstanceId) {

	public MessageHeader {
		Objects.requireNonNull(messageId, "messageId");
		Objects.requireNonNull(correlationKey, "correlationKey");
		Objects.requireNonNull(occurredAt, "occurredAt");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
	}
}
