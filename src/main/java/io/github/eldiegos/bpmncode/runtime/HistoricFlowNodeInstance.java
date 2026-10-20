package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.BpmnElementType;

/**
 * Evidencia histórica de un nodo de flujo por el que pasó una instancia: Instancia → Nodo de flujo → Resultado.
 * <p>
 * Responsabilidad: exponer, en orden, lo que hizo cada instancia de proceso y con qué resultado.
 * <p>
 * Invariantes: es inmutable; el resultado es un código neutro respecto al idioma y el sujeto, si aparece, es una clave
 * seudónima.
 *
 * @param instanceId instancia a la que pertenece
 * @param sequence posición dentro de la instancia, empezando en 1
 * @param processId proceso
 * @param processVersion versión de la definición
 * @param flowNodeId nodo de flujo
 * @param flowNodeName nombre canónico del nodo en la versión de la instancia
 * @param elementType elemento BPMN del nodo
 * @param loopCounter iteración del nodo en esta visita: 1, o el número de vuelta de un bucle estándar
 * @param occurredAt momento
 * @param outcome resultado
 * @param correlationKey correlación de negocio
 * @param businessKey sujeto de negocio, si se conocía
 * @see ProcessInstanceHistory#flowNodeInstances(UUID)
 */
public record HistoricFlowNodeInstance(
		UUID instanceId,
		int sequence,
		String processId,
		int processVersion,
		String flowNodeId,
		String flowNodeName,
		BpmnElementType elementType,
		int loopCounter,
		Instant occurredAt,
		FlowNodeOutcome outcome,
		CorrelationKey correlationKey,
		Optional<String> businessKey) {
}
