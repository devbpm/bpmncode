package io.github.eldiegos.bpmncode.statistics;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Mapa de calor de una versión de un proceso: lo que ha pasado en todas sus instancias.
 * <p>
 * Responsabilidad: dar, por cada nodo de flujo del modelo, cuántas veces se ejecutó, cuántas instancias pasaron por él y
 * cuántas esperan en él, y por cada flujo de secuencia cuántas veces se recorrió.
 * <p>
 * Invariantes: hay una entrada por cada nodo y cada flujo del modelo, en el orden del modelo, también los que nunca se
 * ejecutaron (con cero); {@code instances = running + completed}; {@code maxExecutions} es el máximo de
 * {@link NodeStatistics#executions()}.
 *
 * @param process proceso y versión
 * @param instances instancias de la versión
 * @param running instancias activas
 * @param completed instancias completadas
 * @param maxExecutions ejecuciones del nodo más ejecutado (0 si no hay ninguna)
 * @param nodes cifras por nodo de flujo
 * @param flows cifras por flujo de secuencia
 * @see ProcessExecutionStatistics#heatmap(ProcessDefinitionRef)
 */
public record ExecutionHeatmap(ProcessDefinitionRef process, int instances, int running, int completed, long maxExecutions,
		List<NodeStatistics> nodes, List<FlowStatistics> flows) {

	public ExecutionHeatmap {
		Objects.requireNonNull(process, "process");
		nodes = List.copyOf(nodes);
		flows = List.copyOf(flows);
	}

	/**
	 * Busca las cifras de un nodo.
	 *
	 * @param flowNodeId nodo de flujo
	 * @return sus cifras, o vacío si el nodo no es del modelo
	 */
	public Optional<NodeStatistics> node(String flowNodeId) {
		return nodes.stream().filter(node -> node.flowNodeId().equals(flowNodeId)).findFirst();
	}

	/**
	 * Cifras de un nodo de flujo.
	 * <p>
	 * Responsabilidad: resumir el histórico de un nodo en una versión.
	 * <p>
	 * Invariantes: ninguna cifra es negativa; {@code instances <= executions} (un bucle o un ciclo ejecutan un nodo
	 * varias veces en la misma instancia); {@code share} es el porcentaje de instancias de la versión que pasaron por él,
	 * redondeado.
	 *
	 * @param flowNodeId nodo de flujo
	 * @param executions veces que se ejecutó
	 * @param instances instancias distintas que pasaron por él
	 * @param share porcentaje de instancias de la versión que pasaron por él (0-100)
	 * @param waiting instancias activas que esperan en él
	 * @see ExecutionHeatmap
	 */
	public record NodeStatistics(String flowNodeId, long executions, int instances, int share, long waiting) {

		public NodeStatistics {
			Objects.requireNonNull(flowNodeId, "flowNodeId");
		}
	}

	/**
	 * Cifras de un flujo de secuencia.
	 * <p>
	 * Responsabilidad: contar cuántas veces se recorrió un flujo en una versión.
	 * <p>
	 * Invariantes: {@code traversals} no es negativo; cuenta cada paso entre dos nodos consecutivos de la evidencia que
	 * une ese flujo.
	 *
	 * @param sequenceFlowId flujo de secuencia
	 * @param traversals veces que se recorrió
	 * @see ExecutionHeatmap
	 */
	public record FlowStatistics(String sequenceFlowId, long traversals) {

		public FlowStatistics {
			Objects.requireNonNull(sequenceFlowId, "sequenceFlowId");
		}
	}
}
