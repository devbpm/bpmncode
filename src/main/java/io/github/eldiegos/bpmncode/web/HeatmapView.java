package io.github.eldiegos.bpmncode.web;

import java.util.List;

/**
 * Mapa de calor de una versión, listo para pintarlo en el visor.
 * <p>
 * Responsabilidad: ser el JSON del mapa de calor: las cifras de
 * {@link io.github.eldiegos.bpmncode.statistics.ExecutionHeatmap} con los nombres y tipos ya traducidos.
 * <p>
 * Invariantes: hay una entrada por nodo y por flujo del modelo, también con cero.
 *
 * @param viewId diagrama de la versión
 * @param processId proceso
 * @param processName nombre visible del proceso
 * @param version versión
 * @param versionLabel versión y si es la vigente
 * @param instances instancias de la versión
 * @param running activas
 * @param completed completadas
 * @param maxExecutions ejecuciones del nodo más ejecutado
 * @param nodes cifras por nodo
 * @param flows cifras por flujo de secuencia
 * @see DiagramViews#heatmap(io.github.eldiegos.bpmncode.bpmn.BpmnDiagram, java.util.Locale)
 */
public record HeatmapView(String viewId, String processId, String processName, int version, String versionLabel,
		long instances, long running, long completed, long maxExecutions, List<NodeHeat> nodes, List<FlowHeat> flows) {

	public HeatmapView {
		nodes = List.copyOf(nodes);
		flows = List.copyOf(flows);
	}

	/**
	 * Cifras de un nodo.
	 * <p>
	 * Responsabilidad: decir cuánto se ha usado un nodo en la versión.
	 * <p>
	 * Invariantes: {@code instances <= executions}.
	 *
	 * @param nodeId nodo
	 * @param name nombre visible
	 * @param kind tipo traducido
	 * @param executions veces que se ejecutó
	 * @param instances instancias que pasaron por él
	 * @param share porcentaje de instancias de la versión que pasaron por él
	 * @param waiting instancias que esperan en él
	 * @see HeatmapView
	 */
	public record NodeHeat(String nodeId, String name, String kind, long executions, long instances, int share,
			long waiting) {
	}

	/**
	 * Cifras de un flujo de secuencia.
	 * <p>
	 * Responsabilidad: contar sus recorridos.
	 * <p>
	 * Invariantes: no es negativo.
	 *
	 * @param flowId flujo de secuencia
	 * @param traversals veces que se recorrió
	 * @see HeatmapView
	 */
	public record FlowHeat(String flowId, long traversals) {
	}
}
