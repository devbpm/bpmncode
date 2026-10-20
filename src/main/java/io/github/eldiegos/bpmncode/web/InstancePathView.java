package io.github.eldiegos.bpmncode.web;

import java.util.List;

/**
 * Camino seguido por una instancia, listo para pintarlo en el visor sobre el diagrama de su versión.
 * <p>
 * Responsabilidad: ser el JSON que el visor colorea: pasos ejecutados con su orden, flujos recorridos y paso en
 * espera, con los textos ya traducidos.
 * <p>
 * Invariantes: {@code viewId} es el diagrama de la versión de la instancia; {@code waitingNodeId} y
 * {@code waitingNodeName} son nulos si la instancia está completada.
 *
 * @param instanceId instancia
 * @param viewId diagrama de su versión
 * @param processId proceso
 * @param processName nombre visible del proceso
 * @param version versión
 * @param versionLabel versión y si es la vigente
 * @param running si sigue activa
 * @param status estado traducido
 * @param steps pasos ejecutados
 * @param flows flujos de secuencia recorridos
 * @param waitingNodeId nodo en espera, o nulo
 * @param waitingNodeName nombre del nodo en espera, o nulo
 * @see DiagramViews#path(java.util.UUID, java.util.Locale)
 */
public record InstancePathView(String instanceId, String viewId, String processId, String processName, int version,
		String versionLabel, boolean running, String status, List<PathStep> steps, List<String> flows,
		String waitingNodeId, String waitingNodeName) {

	public InstancePathView {
		steps = List.copyOf(steps);
		flows = List.copyOf(flows);
	}

	/**
	 * Paso ejecutado.
	 * <p>
	 * Responsabilidad: situar en el diagrama cada nodo registrado con su orden.
	 * <p>
	 * Invariantes: {@code order} empieza en 1.
	 *
	 * @param order posición en el camino
	 * @param nodeId nodo ejecutado
	 * @param nodeName nombre visible
	 * @see InstancePathView
	 */
	public record PathStep(int order, String nodeId, String nodeName) {
	}
}
