package io.github.eldiegos.bpmncode.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Camino seguido por una instancia de proceso.
 * <p>
 * Responsabilidad: dar los nodos de flujo que registró la instancia, en orden, los flujos de secuencia que recorrió y,
 * si sigue activa, el nodo en el que espera; es lo que una herramienta pinta sobre el diagrama de su versión.
 * <p>
 * Invariantes: los pasos están en orden de secuencia y numerados desde 1 (un nodo aparece varias veces si un bucle o
 * un ciclo lo repitieron); los flujos van en el orden en que se recorrieron; una instancia completada no espera en
 * ningún nodo.
 *
 * @param instanceId instancia
 * @param process proceso y versión que ejecuta
 * @param running si sigue activa
 * @param steps pasos registrados
 * @param sequenceFlowIds flujos de secuencia recorridos
 * @param waitingFlowNodeId nodo en el que espera, si está activa
 * @see ProcessExecutionStatistics#path(UUID)
 */
public record InstancePath(UUID instanceId, ProcessDefinitionRef process, boolean running, List<PathStep> steps,
		List<String> sequenceFlowIds, Optional<String> waitingFlowNodeId) {

	public InstancePath {
		Objects.requireNonNull(instanceId, "instanceId");
		Objects.requireNonNull(process, "process");
		steps = List.copyOf(steps);
		sequenceFlowIds = List.copyOf(sequenceFlowIds);
		Objects.requireNonNull(waitingFlowNodeId, "waitingFlowNodeId");
	}

	/**
	 * Un paso del camino.
	 * <p>
	 * Responsabilidad: situar un nodo registrado en el camino de la instancia.
	 * <p>
	 * Invariantes: {@code order} empieza en 1 y no se repite; {@code loopCounter} es el del journal (1 al llegar por un
	 * flujo, +1 en cada vuelta de un bucle estándar).
	 *
	 * @param order posición en el camino
	 * @param flowNodeId nodo de flujo
	 * @param loopCounter iteración del nodo
	 * @param occurredAt momento en que se registró
	 * @see InstancePath
	 */
	public record PathStep(int order, String flowNodeId, int loopCounter, Instant occurredAt) {

		public PathStep {
			Objects.requireNonNull(flowNodeId, "flowNodeId");
			Objects.requireNonNull(occurredAt, "occurredAt");
		}
	}
}
