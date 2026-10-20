package io.github.eldiegos.bpmncode.bpmn;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.SequenceFlow;

/**
 * Columna y fila de cada nodo de un proceso.
 * <p>
 * Responsabilidad: asignar las columnas por el camino más largo desde el inicio (ignorando los ciclos) y las filas
 * dejando la primera rama de cada nodo en su fila y abriendo filas nuevas debajo para las demás.
 * <p>
 * Invariantes: todo nodo alcanzable tiene columna y fila; la asignación es determinista.
 *
 * @param columns columna de cada nodo
 * @param rows fila de cada nodo
 * @see ProcessLayout
 */
record LayerAssignment(Map<String, Integer> columns, Map<String, Integer> rows) {

	/**
	 * Calcula la asignación de un proceso.
	 *
	 * @param process proceso
	 * @return columnas y filas de sus nodos
	 */
	static LayerAssignment of(ProcessDefinition process) {
		var forward = forwardFlows(process);
		return new LayerAssignment(columns(process, forward), rows(process, forward));
	}

	int maxColumn() {
		return columns.values().stream().mapToInt(Integer::intValue).max().orElse(0);
	}

	int maxRow() {
		return rows.values().stream().mapToInt(Integer::intValue).max().orElse(0);
	}

	/** Flujos sin aristas de retroceso (las que cierran un ciclo), halladas con búsqueda en profundidad desde el inicio. */
	private static List<SequenceFlow> forwardFlows(ProcessDefinition process) {
		var backEdges = new HashSet<SequenceFlow>();
		findBackEdges(process, process.startEvent().id(), new HashSet<>(), new HashSet<>(), backEdges);
		return process.sequenceFlows().stream().filter(flow -> !backEdges.contains(flow)).toList();
	}

	private static void findBackEdges(ProcessDefinition process, String nodeId, Set<String> visited, Set<String> onPath,
			Set<SequenceFlow> backEdges) {
		visited.add(nodeId);
		onPath.add(nodeId);
		for (var flow : process.outgoing(nodeId)) {
			if (onPath.contains(flow.targetRef())) {
				backEdges.add(flow);
			}
			else if (!visited.contains(flow.targetRef())) {
				findBackEdges(process, flow.targetRef(), visited, onPath, backEdges);
			}
		}
		onPath.remove(nodeId);
	}

	/** Camino más largo desde el evento de inicio por los flujos hacia delante (orden topológico). */
	private static Map<String, Integer> columns(ProcessDefinition process, List<SequenceFlow> forward) {
		var incoming = new HashMap<String, Integer>();
		process.flowNodes().forEach(node -> incoming.put(node.id(), 0));
		forward.forEach(flow -> incoming.merge(flow.targetRef(), 1, Integer::sum));
		var columns = new HashMap<String, Integer>();
		var ready = new ArrayDeque<String>();
		process.flowNodes().stream().filter(node -> incoming.get(node.id()) == 0).forEach(node -> {
			columns.put(node.id(), 0);
			ready.add(node.id());
		});
		while (!ready.isEmpty()) {
			var nodeId = ready.poll();
			for (var flow : forward) {
				if (flow.sourceRef().equals(nodeId)) {
					columns.merge(flow.targetRef(), columns.get(nodeId) + 1, Math::max);
					if (incoming.merge(flow.targetRef(), -1, Integer::sum) == 0) {
						ready.add(flow.targetRef());
					}
				}
			}
		}
		return columns;
	}

	private static Map<String, Integer> rows(ProcessDefinition process, List<SequenceFlow> forward) {
		var rows = new HashMap<String, Integer>();
		assignRow(process.startEvent().id(), 0, forward, rows, new int[] { 0 });
		return rows;
	}

	private static void assignRow(String nodeId, int row, List<SequenceFlow> forward, Map<String, Integer> rows,
			int[] maxRow) {
		if (rows.containsKey(nodeId)) {
			return;
		}
		rows.put(nodeId, row);
		var first = true;
		for (var flow : forward) {
			if (!flow.sourceRef().equals(nodeId) || rows.containsKey(flow.targetRef())) {
				continue;
			}
			if (first) {
				assignRow(flow.targetRef(), row, forward, rows, maxRow);
				first = false;
			}
			else {
				maxRow[0]++;
				assignRow(flow.targetRef(), maxRow[0], forward, rows, maxRow);
			}
		}
	}
}
