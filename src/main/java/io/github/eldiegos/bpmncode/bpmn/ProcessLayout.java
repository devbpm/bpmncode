package io.github.eldiegos.bpmncode.bpmn;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;

/**
 * Disposición de izquierda a derecha de los nodos de un proceso dentro de su piscina.
 * <p>
 * Responsabilidad: convertir columnas y filas en rectángulos con el tamaño BPMN de cada tipo de nodo, y calcular el
 * tamaño de la piscina.
 * <p>
 * Invariantes: los límites son relativos a la esquina superior izquierda de la piscina; la disposición es determinista.
 *
 * @param nodes rectángulo de cada nodo, relativo a la piscina
 * @param width ancho de la piscina
 * @param height alto de la piscina
 * @see LayerAssignment
 */
record ProcessLayout(Map<String, Bounds> nodes, double width, double height) {

	static final double TASK_WIDTH = 120;
	static final double TASK_HEIGHT = 80;
	static final double EVENT_SIZE = 36;
	static final double GATEWAY_SIZE = 50;
	static final double COLUMN_WIDTH = 180;
	static final double ROW_HEIGHT = 130;
	static final double POOL_LABEL_WIDTH = 30;
	static final double POOL_PADDING = 50;

	/**
	 * Calcula la disposición de un proceso.
	 *
	 * @param process proceso
	 * @return la disposición de sus nodos y el tamaño de su piscina
	 */
	static ProcessLayout of(ProcessDefinition process) {
		var layers = LayerAssignment.of(process);
		var nodes = new LinkedHashMap<String, Bounds>();
		for (var node : process.flowNodes()) {
			var centerX = POOL_LABEL_WIDTH + POOL_PADDING + layers.columns().get(node.id()) * COLUMN_WIDTH + TASK_WIDTH / 2;
			var centerY = POOL_PADDING + layers.rows().get(node.id()) * ROW_HEIGHT + TASK_HEIGHT / 2;
			var size = sizeOf(node);
			nodes.put(node.id(), new Bounds(centerX - size.x() / 2, centerY - size.y() / 2, size.x(), size.y()));
		}
		var width = POOL_LABEL_WIDTH + 2 * POOL_PADDING + layers.maxColumn() * COLUMN_WIDTH + TASK_WIDTH;
		var height = 2 * POOL_PADDING + layers.maxRow() * ROW_HEIGHT + TASK_HEIGHT;
		return new ProcessLayout(Map.copyOf(nodes), width, height);
	}

	/**
	 * Devuelve el rectángulo absoluto de un nodo.
	 *
	 * @param nodeId nodo
	 * @param pool rectángulo de la piscina en el lienzo
	 * @return el rectángulo del nodo en el lienzo
	 */
	Bounds node(String nodeId, Bounds pool) {
		return nodes.get(nodeId).translate(pool.x(), pool.y());
	}

	private static Point sizeOf(FlowNode node) {
		return switch (node) {
			case FlowNode.StartEvent start -> new Point(EVENT_SIZE, EVENT_SIZE);
			case FlowNode.EndEvent end -> new Point(EVENT_SIZE, EVENT_SIZE);
			case FlowNode.ExclusiveGateway gateway -> new Point(GATEWAY_SIZE, GATEWAY_SIZE);
			case FlowNode.Activity activity -> new Point(TASK_WIDTH, TASK_HEIGHT);
		};
	}
}
