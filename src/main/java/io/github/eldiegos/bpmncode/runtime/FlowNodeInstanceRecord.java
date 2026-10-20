package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.BpmnElementType;

/**
 * Evidencia de que una instancia pasó por un nodo de flujo, tal como la guarda un {@link ProcessInstanceStore}.
 * <p>
 * Responsabilidad: guardar la evidencia de cada nodo de forma autocontenida (proceso, versión, correlación) para poder
 * exportarla a minería de procesos sin joins.
 * <p>
 * Invariantes: inmutable; solo se añade, nunca se modifica; {@code sequence} empieza en 1 y no se repite dentro de la
 * instancia.
 *
 * @param id identificador del registro
 * @param processInstanceId instancia
 * @param sequence posición dentro de la instancia, desde 1
 * @param processId proceso
 * @param processVersion versión de la definición
 * @param correlationKey correlación de negocio
 * @param businessKey sujeto de negocio, si se conocía
 * @param flowNodeId nodo de flujo
 * @param elementType elemento BPMN del nodo
 * @param loopCounter iteración del nodo en esta visita: 1, o el número de vuelta de un bucle estándar
 * @param occurredAt momento
 * @param outcome resultado
 * @see HistoricFlowNodeInstance
 */
public record FlowNodeInstanceRecord(
		UUID id,
		UUID processInstanceId,
		int sequence,
		String processId,
		int processVersion,
		CorrelationKey correlationKey,
		Optional<String> businessKey,
		String flowNodeId,
		BpmnElementType elementType,
		int loopCounter,
		Instant occurredAt,
		FlowNodeOutcome outcome) {

	public FlowNodeInstanceRecord {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(processInstanceId, "processInstanceId");
		Objects.requireNonNull(processId, "processId");
		Objects.requireNonNull(correlationKey, "correlationKey");
		Objects.requireNonNull(businessKey, "businessKey");
		Objects.requireNonNull(flowNodeId, "flowNodeId");
		Objects.requireNonNull(elementType, "elementType");
		Objects.requireNonNull(occurredAt, "occurredAt");
		Objects.requireNonNull(outcome, "outcome");
	}

	/**
	 * Construye el modelo de lectura de la evidencia.
	 *
	 * @param flowNodeName nombre canónico del nodo en la versión de la instancia
	 * @return la evidencia para las vistas
	 */
	HistoricFlowNodeInstance toHistoric(String flowNodeName) {
		return new HistoricFlowNodeInstance(processInstanceId, sequence, processId, processVersion, flowNodeId,
				flowNodeName, elementType, loopCounter, occurredAt, outcome, correlationKey, businessKey);
	}
}
