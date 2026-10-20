package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Modelo de lectura de una instancia de proceso para las vistas de gobierno.
 * <p>
 * Responsabilidad: mostrar en qué punto está cada instancia o con qué resultado terminó.
 * <p>
 * Invariantes: una instancia activa no tiene fecha de fin; una completada siempre la tiene y su nodo es el evento de
 * fin que da su resultado.
 *
 * @see ProcessInstanceHistory#instances()
 */
public sealed interface ProcessInstanceSummary permits ProcessInstanceSummary.Active, ProcessInstanceSummary.Completed {

	UUID instanceId();

	String processId();

	int processVersion();

	CorrelationKey correlationKey();

	/** Referencia seudónima al sujeto de negocio, p. ej. {@code person:<uuid>}, si ya se conoce. */
	Optional<String> businessKey();

	/** Nodo de flujo alcanzado: el del token si está activa o el evento de fin si está completada. */
	String flowNodeId();

	/** Nombre canónico de {@link #flowNodeId()}. */
	String flowNodeName();

	Instant startedAt();

	/** Mensaje que inició la instancia, si empezó en un evento de inicio de mensaje. */
	Optional<ExchangedMessage> triggeringMessage();

	/**
	 * Instancia activa: aún no ha llegado a un evento de fin.
	 * <p>
	 * Responsabilidad: describir una instancia activa.
	 * <p>
	 * Invariantes: no tiene fecha de fin.
	 *
	 * @param instanceId identificador de la instancia
	 * @param processId proceso que ejecuta
	 * @param processVersion versión de la definición
	 * @param correlationKey correlación de negocio
	 * @param businessKey sujeto de negocio, si se conoce
	 * @param flowNodeId nodo actual
	 * @param flowNodeName nombre canónico del nodo actual
	 * @param startedAt inicio
	 * @param triggeringMessage mensaje que la inició, si empezó por un mensaje
	 * @param nextFlowNodeIds nodos a los que la conformidad permite avanzar ahora: el destino del flujo que seleccionó un
	 *        gateway, o los destinos de los flujos de salida y, si es una tarea cuyo bucle admite otra vuelta, ella misma
	 * @see ProcessInstanceSummary
	 */
	record Active(UUID instanceId, String processId, int processVersion, CorrelationKey correlationKey,
			Optional<String> businessKey, String flowNodeId, String flowNodeName, Instant startedAt,
			Optional<ExchangedMessage> triggeringMessage, List<String> nextFlowNodeIds) implements ProcessInstanceSummary {

		public Active {
			nextFlowNodeIds = List.copyOf(nextFlowNodeIds);
		}

		/**
		 * Nodo en el que espera el token: el siguiente si la conformidad solo admite uno (p. ej. la tarea humana que
		 * eligió un gateway) y, si admite varios (una tarea con bucle puede repetirse o seguir), el actual.
		 *
		 * @return el identificador del nodo en espera
		 */
		public String waitingFlowNodeId() {
			return nextFlowNodeIds.size() == 1 ? nextFlowNodeIds.getFirst() : flowNodeId;
		}
	}

	/**
	 * Instancia que terminó en un evento de fin.
	 * <p>
	 * Responsabilidad: describir una instancia terminada y su resultado.
	 * <p>
	 * Invariantes: siempre tiene fecha de fin.
	 *
	 * @param instanceId identificador de la instancia
	 * @param processId proceso que ejecutó
	 * @param processVersion versión de la definición
	 * @param correlationKey correlación de negocio
	 * @param businessKey sujeto de negocio, si se conoce
	 * @param flowNodeId evento de fin alcanzado (el resultado)
	 * @param flowNodeName nombre canónico del evento de fin
	 * @param startedAt inicio
	 * @param triggeringMessage mensaje que la inició, si empezó por un mensaje
	 * @param endedAt fin
	 * @param publishedMessage mensaje que publicó su evento de fin, si es un evento de fin de mensaje
	 * @see ProcessInstanceSummary
	 */
	record Completed(UUID instanceId, String processId, int processVersion, CorrelationKey correlationKey,
			Optional<String> businessKey, String flowNodeId, String flowNodeName, Instant startedAt,
			Optional<ExchangedMessage> triggeringMessage, Instant endedAt, Optional<ExchangedMessage> publishedMessage)
			implements ProcessInstanceSummary {
	}
}
