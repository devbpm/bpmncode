package io.github.eldiegos.bpmncode.trace;

import java.util.List;
import java.util.Optional;

import io.github.eldiegos.bpmncode.model.ErrorEventDefinition;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.FlowNode.Activity;
import io.github.eldiegos.bpmncode.model.FlowNode.EndEvent;
import io.github.eldiegos.bpmncode.model.FlowNode.Gateway;
import io.github.eldiegos.bpmncode.model.FlowNode.StartEvent;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;

/**
 * Informe de trazabilidad de las definiciones de proceso.
 * <p>
 * Responsabilidad: derivar, para cada nodo de cada proceso, la cadena Proceso → Nodo de flujo → Regla → Código →
 * Mensaje.
 * <p>
 * Invariantes: solo lee el registro; el informe sigue el orden de los procesos y de sus nodos.
 *
 * @see TraceabilityEntry
 */
public class TraceabilityReport {

	private final ProcessDefinitionRegistry registry;

	/**
	 * Crea el informe sobre un registro de definiciones.
	 *
	 * @param registry registro de definiciones
	 */
	public TraceabilityReport(ProcessDefinitionRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Genera el informe.
	 *
	 * @return una fila por nodo de cada proceso
	 */
	public List<TraceabilityEntry> entries() {
		var definitions = registry.definitions();
		return definitions.processes().stream()
				.flatMap(process -> process.flowNodes().stream().map(node -> new TraceabilityEntry(
						process.id(), process.name(), process.version(),
						node.id(), node.name(), node.elementType(),
						node.implementation().className(), node.implementation().signature(),
						node.businessRuleRefs().stream().map(ref -> definitions.businessRule(ref).orElseThrow()).toList(),
						thrown(node).flatMap(definitions::message),
						caught(node).flatMap(definitions::message),
						error(node).flatMap(definitions::error))))
				.toList();
	}

	private static Optional<String> error(FlowNode node) {
		return node instanceof EndEvent end ? end.error().map(ErrorEventDefinition::errorRef) : Optional.empty();
	}

	private static Optional<String> thrown(FlowNode node) {
		return node.publishedMessage().map(MessageVersionRef::messageId);
	}

	private static Optional<String> caught(FlowNode node) {
		return switch (node) {
			case StartEvent start -> start.messageRef();
			case EndEvent end -> Optional.empty();
			case Activity activity -> Optional.empty();
			case Gateway gateway -> Optional.empty();
		};
	}
}
