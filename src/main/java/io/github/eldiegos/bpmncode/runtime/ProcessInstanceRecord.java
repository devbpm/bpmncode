package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Estado de una instancia de proceso tal como lo guarda un {@link ProcessInstanceStore}.
 * <p>
 * Responsabilidad: transportar entre el journal y el almacén todo lo que hay que recordar de una instancia, sin lógica
 * de conformidad (que vive en el journal).
 * <p>
 * Invariantes: inmutable; ningún componente es nulo; {@code version} es la versión que se leyó del almacén y sirve para
 * el bloqueo optimista: el almacén solo acepta actualizar una instancia si su versión no ha cambiado.
 *
 * @param id identificador de la instancia
 * @param processId proceso que ejecuta
 * @param processVersion versión de la definición
 * @param correlationKey correlación de negocio; como mucho una instancia por proceso y correlación
 * @param businessKey referencia seudónima al sujeto de negocio, p. ej. {@code person:<uuid>}
 * @param state activa o completada
 * @param currentFlowNodeId nodo de flujo en el que está el token
 * @param loopCounter iteraciones seguidas del nodo actual: 1 al llegar por un flujo, +1 en cada vuelta de un bucle
 * @param selectedSequenceFlowId flujo de salida que seleccionó el último gateway, mientras no se haya seguido
 * @param triggeringMessage mensaje que inició la instancia, si empezó por un mensaje
 * @param publishedMessage mensaje que publicó al terminar, si terminó en un evento de fin de mensaje
 * @param startedAt inicio
 * @param endedAt fin, si está completada
 * @param version versión para el bloqueo optimista; 0 al crearla
 * @see ProcessInstanceStore
 */
public record ProcessInstanceRecord(
		UUID id,
		String processId,
		int processVersion,
		CorrelationKey correlationKey,
		Optional<String> businessKey,
		ProcessInstanceState state,
		String currentFlowNodeId,
		int loopCounter,
		Optional<String> selectedSequenceFlowId,
		Optional<ExchangedMessage> triggeringMessage,
		Optional<ExchangedMessage> publishedMessage,
		Instant startedAt,
		Optional<Instant> endedAt,
		long version) {

	public ProcessInstanceRecord {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(processId, "processId");
		Objects.requireNonNull(correlationKey, "correlationKey");
		Objects.requireNonNull(businessKey, "businessKey");
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(currentFlowNodeId, "currentFlowNodeId");
		Objects.requireNonNull(selectedSequenceFlowId, "selectedSequenceFlowId");
		Objects.requireNonNull(triggeringMessage, "triggeringMessage");
		Objects.requireNonNull(publishedMessage, "publishedMessage");
		Objects.requireNonNull(startedAt, "startedAt");
		Objects.requireNonNull(endedAt, "endedAt");
	}

	/**
	 * Proceso y versión que ejecuta.
	 *
	 * @return la referencia a la definición
	 */
	public ProcessDefinitionRef ref() {
		return ProcessDefinitionRef.of(processId, processVersion);
	}

	/**
	 * Copia con otra versión de bloqueo optimista; la usan los almacenes al guardar.
	 *
	 * @param newVersion versión guardada
	 * @return la copia
	 */
	public ProcessInstanceRecord withVersion(long newVersion) {
		return new ProcessInstanceRecord(id, processId, processVersion, correlationKey, businessKey, state,
				currentFlowNodeId, loopCounter, selectedSequenceFlowId, triggeringMessage, publishedMessage, startedAt,
				endedAt, newVersion);
	}
}
