package io.github.eldiegos.bpmncode.model.reader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.eldiegos.bpmncode.model.FlowNode.EndEvent;
import io.github.eldiegos.bpmncode.model.FlowNode.StartEvent;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.ViolationKind;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.SequenceFlow;

/**
 * Constructor del grafo de un proceso a partir de sus nodos ya convertidos.
 * <p>
 * Responsabilidad: comprobar inicio y fin, resolver los flujos de secuencia declarados, ordenar los nodos canónicamente y
 * detectar nodos inalcanzables y ciclos sin salida.
 * <p>
 * Invariantes: solo construye una {@link ProcessDefinition} si el proceso no tiene problemas propios; el orden de los
 * nodos es un recorrido en anchura desde el inicio que sigue los flujos de secuencia en el orden en que se declararon.
 *
 * @see AnnotationProcessModelReader
 */
final class ProcessGraphBuilder {

	private final ModelDiagnostics diagnostics;

	ProcessGraphBuilder(ModelDiagnostics diagnostics) {
		this.diagnostics = diagnostics;
	}

	/**
	 * Construye un proceso.
	 *
	 * @param header cabecera del proceso
	 * @param allNodes nodos convertidos de todos los procesos
	 * @return la definición, o vacío si el proceso tiene problemas (ya registrados)
	 */
	Optional<ProcessDefinition> build(DeclaredProcesses.Header header, List<ParsedFlowNode> allNodes) {
		var problemsBefore = diagnostics.count();
		var ref = header.ref();
		var processId = ref.processId();
		var location = "Process " + ref;
		var own = new LinkedHashMap<String, ParsedFlowNode>();
		allNodes.stream().filter(parsed -> parsed.ref().equals(ref))
				.forEach(parsed -> own.putIfAbsent(parsed.node().id(), parsed));

		var starts = own.values().stream().map(ParsedFlowNode::node).filter(StartEvent.class::isInstance).toList();
		if (starts.isEmpty()) {
			diagnostics.report(ViolationKind.MISSING_START_EVENT, location, "declares no start event (@StartEvent, @MessageStartEvent or @TimerStartEvent)");
		}
		else if (starts.size() > 1) {
			diagnostics.report(ViolationKind.AMBIGUOUS_REFERENCE, location,
					"declares several start events: " + starts.stream().map(FlowNode::id).toList());
		}
		if (own.values().stream().map(ParsedFlowNode::node).noneMatch(EndEvent.class::isInstance)) {
			diagnostics.report(ViolationKind.MISSING_END_EVENT, location, "declares no end event (@EndEvent, @MessageEndEvent or @ErrorEndEvent)");
		}

		var outgoing = new HashMap<String, List<SequenceFlow>>();
		for (var parsed : own.values()) {
			var flows = new ArrayList<SequenceFlow>();
			for (var declared : parsed.outgoing()) {
				sequenceFlow(ref, parsed.node(), declared, own, allNodes).ifPresent(flows::add);
			}
			outgoing.put(parsed.node().id(), flows);
		}
		if (starts.size() != 1) {
			return Optional.empty();
		}

		var reached = reachableFrom(starts.getFirst().id(), outgoing);
		own.keySet().stream().filter(nodeId -> !reached.contains(nodeId)).forEach(nodeId -> diagnostics.report(
				ViolationKind.UNREACHABLE_FLOW_NODE, location, "flow node " + nodeId + " is not reachable from the start event"));
		var ending = reachingAnEndEvent(own, outgoing);
		reached.stream().filter(nodeId -> !ending.contains(nodeId)).forEach(nodeId -> diagnostics.report(
				ViolationKind.NO_PATH_TO_END_EVENT, location, "no end event can be reached from flow node " + nodeId
						+ ": a cycle without exit"));
		if (diagnostics.count() > problemsBefore) {
			return Optional.empty();
		}
		var nodes = reached.stream().map(nodeId -> own.get(nodeId).node()).toList();
		var flows = reached.stream().flatMap(nodeId -> outgoing.getOrDefault(nodeId, List.of()).stream()).toList();
		return Optional.of(new ProcessDefinition(processId, header.annotation().name(), header.annotation().version(),
				header.annotation().documentation(), header.type().getName(), List.copyOf(header.implementedBy()),
				nodes, flows));
	}

	/** Nodos desde los que se llega a algún evento de fin: recorrido hacia atrás desde los fines. */
	private static Set<String> reachingAnEndEvent(Map<String, ParsedFlowNode> own, Map<String, List<SequenceFlow>> outgoing) {
		var incoming = new HashMap<String, List<String>>();
		outgoing.values().forEach(flows -> flows.forEach(
				flow -> incoming.computeIfAbsent(flow.targetRef(), target -> new ArrayList<>()).add(flow.sourceRef())));
		var ending = new HashSet<String>();
		var queue = new ArrayDeque<String>();
		own.values().stream().map(ParsedFlowNode::node).filter(EndEvent.class::isInstance).map(FlowNode::id)
				.forEach(queue::add);
		while (!queue.isEmpty()) {
			var nodeId = queue.poll();
			if (ending.add(nodeId)) {
				queue.addAll(incoming.getOrDefault(nodeId, List.of()));
			}
		}
		return ending;
	}

	private static LinkedHashSet<String> reachableFrom(String startId, Map<String, List<SequenceFlow>> outgoing) {
		var reached = new LinkedHashSet<String>();
		var queue = new ArrayDeque<String>(List.of(startId));
		while (!queue.isEmpty()) {
			var nodeId = queue.poll();
			if (reached.add(nodeId)) {
				outgoing.getOrDefault(nodeId, List.of()).forEach(flow -> queue.add(flow.targetRef()));
			}
		}
		return reached;
	}

	private Optional<SequenceFlow> sequenceFlow(ProcessDefinitionRef ref, FlowNode source, DeclaredSequenceFlow declared,
			Map<String, ParsedFlowNode> own, List<ParsedFlowNode> allNodes) {
		var target = declared.targetRef();
		var location = source.implementation().toString();
		if (!own.containsKey(target)) {
			var foreign = allNodes.stream().filter(parsed -> parsed.node().id().equals(target))
					.filter(parsed -> !parsed.ref().processId().equals(ref.processId())).findFirst();
			diagnostics.report(ViolationKind.UNKNOWN_TARGET_REF, location, foreign
					.map(parsed -> "the sequence flow targets " + target + ", which belongs to process "
							+ parsed.node().processId() + "; processes only communicate through messages")
					.orElse("the sequence flow targets the unknown flow node '" + target + "'"));
			return Optional.empty();
		}
		return switch (declared) {
			case DeclaredSequenceFlow.Unconditional unconditional -> claimed(flowId(unconditional, source, target), ref, location)
					.map(id -> new SequenceFlow.Unconditional(id, ref.processId(), source.id(), target));
			case DeclaredSequenceFlow.Conditional conditional -> claimed(flowId(conditional, source, target), ref, location)
					.map(id -> new SequenceFlow.Conditional(id, ref.processId(), source.id(), target, conditional.name(),
							conditional.conditionExpression(), conditional.when()));
		};
	}

	/** Identificador declarado del flujo o, si no lo hay, {@code Flow_<origen>_<destino>}. */
	private static String flowId(DeclaredSequenceFlow declared, FlowNode source, String target) {
		return declared.id().isBlank() ? "Flow_" + source.id() + "_" + target : declared.id();
	}

	private Optional<String> claimed(String flowId, ProcessDefinitionRef ref, String location) {
		return diagnostics.isValidId(flowId, "sequence flow", location) && diagnostics.claimBpmnId(flowId, ref, location)
				? Optional.of(flowId) : Optional.empty();
	}
}
