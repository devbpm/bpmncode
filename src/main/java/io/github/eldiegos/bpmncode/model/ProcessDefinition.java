package io.github.eldiegos.bpmncode.model;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import io.github.eldiegos.bpmncode.model.FlowNode.EndEvent;
import io.github.eldiegos.bpmncode.model.FlowNode.StartEvent;

/**
 * Definición validada de una versión de un proceso BPMN ({@code bpmn:process}).
 * <p>
 * Responsabilidad: ofrecer el grafo de un proceso (nodos en orden de recorrido desde el inicio y flujos de secuencia
 * declarados), independiente de cómo estén escritos los métodos Java. Como en los motores BPMN, una definición de
 * proceso es un {@code process} desplegado con su versión.
 * <p>
 * Invariantes: tiene exactamente un evento de inicio y al menos un evento de fin, sus nodos tienen identificadores
 * únicos y todos sus flujos unen nodos del propio proceso.
 *
 * @param id identificador estable, común a todas las versiones
 * @param name nombre canónico
 * @param version versión, mayor que cero
 * @param documentation texto de negocio, posiblemente vacío
 * @param definedBy clase anotada con {@code @Process}
 * @param implementedBy clases que aportan nodos, ordenadas por nombre
 * @param flowNodes nodos en orden de recorrido
 * @param sequenceFlows flujos de secuencia declarados
 * @see Definitions
 */
public record ProcessDefinition(
		String id,
		String name,
		int version,
		String documentation,
		String definedBy,
		List<String> implementedBy,
		List<FlowNode> flowNodes,
		List<SequenceFlow> sequenceFlows) {

	public ProcessDefinition {
		implementedBy = List.copyOf(implementedBy);
		flowNodes = List.copyOf(flowNodes);
		sequenceFlows = List.copyOf(sequenceFlows);
		documentation = documentation == null ? "" : documentation;
		if (version < 1) {
			throw new IllegalArgumentException("Process " + id + " must have a version >= 1");
		}
		if (flowNodes.stream().filter(StartEvent.class::isInstance).count() != 1) {
			throw new IllegalArgumentException("Process " + id + " must have exactly one start event");
		}
		if (flowNodes.stream().noneMatch(EndEvent.class::isInstance)) {
			throw new IllegalArgumentException("Process " + id + " must have at least one end event");
		}
		var nodeIds = new HashSet<String>();
		flowNodes.forEach(node -> {
			if (!nodeIds.add(node.id())) {
				throw new IllegalArgumentException("Duplicate flow node " + node.id() + " in process " + id);
			}
		});
		sequenceFlows.forEach(flow -> {
			if (!nodeIds.contains(flow.sourceRef()) || !nodeIds.contains(flow.targetRef())) {
				throw new IllegalArgumentException("Sequence flow " + flow.id() + " connects nodes outside process " + id);
			}
		});
	}

	/**
	 * Referencia a esta versión del proceso.
	 *
	 * @return identificador y versión
	 */
	public ProcessDefinitionRef ref() {
		return ProcessDefinitionRef.of(id, version);
	}

	/**
	 * Busca un nodo de esta versión del proceso.
	 *
	 * @param flowNodeId identificador del nodo
	 * @return el nodo, o vacío si no pertenece a esta versión del proceso
	 */
	public Optional<FlowNode> flowNode(String flowNodeId) {
		return flowNodes.stream().filter(node -> node.id().equals(flowNodeId)).findFirst();
	}

	/**
	 * Devuelve el único evento de inicio.
	 *
	 * @return el evento de inicio
	 */
	public StartEvent startEvent() {
		return flowNodes.stream().filter(StartEvent.class::isInstance).map(StartEvent.class::cast).findFirst().orElseThrow();
	}

	/**
	 * Devuelve los flujos que salen de un nodo (elementos {@code bpmn:outgoing}), en el orden en que están declarados.
	 *
	 * @param flowNodeId identificador del nodo de origen
	 * @return los flujos, posiblemente ninguno
	 */
	public List<SequenceFlow> outgoing(String flowNodeId) {
		return sequenceFlows.stream().filter(flow -> flow.sourceRef().equals(flowNodeId)).toList();
	}

	/**
	 * Devuelve los flujos que llegan a un nodo (elementos {@code bpmn:incoming}).
	 *
	 * @param flowNodeId identificador del nodo de destino
	 * @return los flujos, posiblemente ninguno
	 */
	public List<SequenceFlow> incoming(String flowNodeId) {
		return sequenceFlows.stream().filter(flow -> flow.targetRef().equals(flowNodeId)).toList();
	}

	/**
	 * Busca el flujo declarado entre dos nodos.
	 *
	 * @param sourceRef nodo de origen
	 * @param targetRef nodo de destino
	 * @return el flujo, o vacío si no está declarado
	 */
	public Optional<SequenceFlow> sequenceFlow(String sourceRef, String targetRef) {
		return sequenceFlows.stream().filter(flow -> flow.sourceRef().equals(sourceRef) && flow.targetRef().equals(targetRef))
				.findFirst();
	}
}
