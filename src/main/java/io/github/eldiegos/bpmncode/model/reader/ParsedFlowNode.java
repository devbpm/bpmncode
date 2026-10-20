package io.github.eldiegos.bpmncode.model.reader;

import java.util.List;

import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Nodo ya convertido desde sus anotaciones, con los flujos de salida que declara.
 * <p>
 * Responsabilidad: unir el nodo válido, la versión del proceso que lo declara y sus flujos todavía sin resolver.
 * <p>
 * Invariantes: el nodo es válido; los flujos pueden apuntar a nodos inexistentes hasta construir el grafo.
 *
 * @param ref versión del proceso que declara el nodo
 * @param node nodo
 * @param outgoing flujos de salida declarados, en orden de declaración
 * @see FlowNodeParser
 */
record ParsedFlowNode(ProcessDefinitionRef ref, FlowNode node, List<DeclaredSequenceFlow> outgoing) {

	ParsedFlowNode {
		outgoing = List.copyOf(outgoing);
	}
}
