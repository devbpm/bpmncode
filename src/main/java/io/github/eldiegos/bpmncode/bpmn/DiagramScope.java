package io.github.eldiegos.bpmncode.bpmn;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.github.eldiegos.bpmncode.i18n.LabelResolver;
import io.github.eldiegos.bpmncode.model.MessageFlow;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.Definitions;

/**
 * Lo que entra en un diagrama concreto y dónde va cada piscina.
 * <p>
 * Responsabilidad: reunir los procesos del diagrama, los mensajes que los conectan, el orden de las piscinas
 * (publicadores externos arriba, procesos del diagrama, consumidores externos abajo) y la posición de cada piscina.
 * <p>
 * Invariantes: los procesos de fuera del diagrama solo aparecen como piscinas colapsadas; todas las piscinas tienen el
 * mismo ancho; cada proceso aparece en una sola versión, de modo que los identificadores BPMN no se repiten.
 *
 * @param definitions modelo completo
 * @param diagram diagrama que se dibuja
 * @param labels traductor de los textos visibles
 * @param processes procesos del diagrama, en la versión que muestra el diagrama
 * @param shown versión que se muestra de cada proceso de una piscina (la del diagrama o, si es externo, la vigente)
 * @param messageFlows mensajes con origen o destino en el diagrama
 * @param pools identificadores de proceso de todas las piscinas, de arriba abajo
 * @param layouts disposición de cada proceso del diagrama
 * @param poolBounds rectángulo de cada piscina
 * @see BpmnXmlExporter
 */
record DiagramScope(
		Definitions definitions,
		BpmnDiagram diagram,
		LabelResolver labels,
		List<ProcessDefinition> processes,
		Map<String, ProcessDefinition> shown,
		List<MessageFlow> messageFlows,
		List<String> pools,
		Map<String, ProcessLayout> layouts,
		Map<String, Bounds> poolBounds) {

	static final double COLLAPSED_POOL_HEIGHT = 60;
	static final double POOL_GAP = 80;

	/**
	 * Calcula el alcance de un diagrama.
	 *
	 * @param definitions modelo completo
	 * @param diagram diagrama que se dibuja
	 * @param labels traductor de los textos visibles
	 * @return el alcance, con las piscinas ya situadas
	 * @throws IllegalArgumentException si el diagrama nombra un proceso inexistente
	 */
	static DiagramScope of(Definitions definitions, BpmnDiagram diagram, LabelResolver labels) {
		var processes = switch (diagram) {
			case BpmnDiagram.ProcessDiagram single -> List.of(current(definitions, single.processId()));
			case BpmnDiagram.ProcessVersionDiagram version -> List.of(definitions.process(version.ref())
					.orElseThrow(() -> new IllegalArgumentException("Unknown process " + version.ref())));
			case BpmnDiagram.CollaborationDiagram collaboration -> collaboration.processIds().stream()
					.map(processId -> current(definitions, processId)).toList();
		};
		var processIds = processes.stream().map(ProcessDefinition::id).toList();
		// Cada proceso aparece en una sola versión: la del diagrama o, si es externo, la vigente.
		var shown = new LinkedHashMap<String, ProcessDefinition>();
		processes.forEach(process -> shown.put(process.id(), process));
		var messageFlows = definitions.messageFlows().stream()
				.filter(flow -> processIds.contains(flow.sourceProcessId()) || processIds.contains(flow.targetProcessId()))
				.filter(flow -> flow.sourceProcess().equals(shownRef(definitions, shown, flow.sourceProcessId()))
						&& flow.targetProcess().equals(shownRef(definitions, shown, flow.targetProcessId())))
				.toList();
		var externalSources = new TreeSet<String>();
		var externalTargets = new TreeSet<String>();
		for (var flow : messageFlows) {
			if (!processIds.contains(flow.sourceProcessId())) {
				externalSources.add(flow.sourceProcessId());
			}
			if (!processIds.contains(flow.targetProcessId())) {
				externalTargets.add(flow.targetProcessId());
			}
		}
		externalTargets.removeAll(externalSources);
		externalSources.forEach(processId -> shown.put(processId, current(definitions, processId)));
		externalTargets.forEach(processId -> shown.put(processId, current(definitions, processId)));
		var ordered = new LinkedHashSet<String>(externalSources);
		ordered.addAll(processIds);
		ordered.addAll(externalTargets);
		var pools = List.copyOf(ordered);

		var layouts = new LinkedHashMap<String, ProcessLayout>();
		processes.forEach(process -> layouts.put(process.id(), ProcessLayout.of(process)));
		var width = layouts.values().stream().mapToDouble(ProcessLayout::width).max().orElse(400);
		var poolBounds = new LinkedHashMap<String, Bounds>();
		var y = 0.0;
		for (var processId : pools) {
			var layout = layouts.get(processId);
			var height = layout == null ? COLLAPSED_POOL_HEIGHT : layout.height();
			poolBounds.put(processId, new Bounds(0, y, width, height));
			y += height + POOL_GAP;
		}
		return new DiagramScope(definitions, diagram, labels, processes, Map.copyOf(shown), messageFlows, pools, layouts,
				poolBounds);
	}

	/**
	 * Indica si un proceso se dibuja completo (no como piscina colapsada).
	 *
	 * @param processId proceso
	 * @return {@code true} si pertenece a el diagrama
	 */
	boolean isInDiagram(String processId) {
		return layouts.containsKey(processId);
	}

	/**
	 * Versión que se muestra de un proceso con piscina en este diagrama.
	 *
	 * @param processId proceso
	 * @return su definición en la versión mostrada
	 */
	ProcessDefinition shown(String processId) {
		return shown.get(processId);
	}

	private static ProcessDefinition current(Definitions definitions, String processId) {
		return definitions.process(processId).orElseThrow(() -> new IllegalArgumentException("Unknown process " + processId));
	}

	private static ProcessDefinitionRef shownRef(Definitions definitions, Map<String, ProcessDefinition> shown, String processId) {
		var definition = shown.get(processId);
		return definition != null ? definition.ref() : current(definitions, processId).ref();
	}

	static String participantId(String processId) {
		return "Participant_" + processId;
	}

	static String messageId(String messageId) {
		return "Message_" + messageId;
	}

	static String errorId(String errorId) {
		return "Error_" + errorId;
	}

	static String resourceId(String resourceId) {
		return "Resource_" + resourceId;
	}
}
