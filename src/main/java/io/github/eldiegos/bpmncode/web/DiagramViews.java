package io.github.eldiegos.bpmncode.web;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.eldiegos.bpmncode.bpmn.BpmnDiagram;
import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;
import io.github.eldiegos.bpmncode.bpmn.DiagramElement;
import io.github.eldiegos.bpmncode.i18n.LabelResolver;
import io.github.eldiegos.bpmncode.model.CodeReference;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.MessageEventDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.ResourceRole;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import io.github.eldiegos.bpmncode.statistics.ProcessExecutionStatistics;
import io.github.eldiegos.bpmncode.web.DiagramElementInfo.CodeInfo;
import io.github.eldiegos.bpmncode.web.DiagramElementInfo.EventInfo;
import io.github.eldiegos.bpmncode.web.DiagramElementInfo.ProcessInfo;
import io.github.eldiegos.bpmncode.web.DiagramElementInfo.RoleInfo;
import io.github.eldiegos.bpmncode.web.DiagramElementInfo.RuleInfo;
import io.github.eldiegos.bpmncode.web.DiagramElementInfo.TransitionInfo;

/**
 * Lo que muestra el visor, ya traducido: nombres de los diagramas, fichas de sus elementos, camino de una instancia y
 * mapa de calor de una versión.
 * <p>
 * Responsabilidad: combinar el modelo, los diagramas y las estadísticas de ejecución con los textos de un idioma.
 * <p>
 * Invariantes: solo lee; las cifras salen de {@link ProcessExecutionStatistics} (dos consultas por versión, nunca una
 * por instancia); los textos salen del mismo traductor que el BPMN, así que coinciden con lo dibujado.
 *
 * @see ProcessDiagramController
 */
class DiagramViews {

	private final BpmnDiagramService diagrams;
	private final ProcessDefinitionRegistry registry;
	private final ProcessExecutionStatistics statistics;
	private final WebTexts texts;

	DiagramViews(BpmnDiagramService diagrams, ProcessDefinitionRegistry registry, ProcessExecutionStatistics statistics,
			WebTexts texts) {
		this.diagrams = diagrams;
		this.registry = registry;
		this.statistics = statistics;
		this.texts = texts;
	}

	/** Diagramas disponibles, con su nombre traducido. */
	List<DiagramOption> options(Locale locale) {
		return diagrams.diagrams().stream().map(view -> new DiagramOption(view.id(), name(view, locale))).toList();
	}

	/** XML BPMN de un diagrama en un idioma. */
	String xml(BpmnDiagram view, Locale locale) {
		return diagrams.xml(view, locale.toLanguageTag(), texts.labels(locale));
	}

	private String name(BpmnDiagram view, Locale locale) {
		var labels = texts.labels(locale);
		return switch (view) {
			case BpmnDiagram.CollaborationDiagram collaboration -> texts.text(locale, "pac.web.diagram.collaboration",
					"Collaboration between processes ({0})", String.join(" → ", collaboration.processIds()));
			case BpmnDiagram.ProcessDiagram single -> {
				var process = registry.processDefinition(single.processId());
				yield texts.text(locale, "pac.web.diagram.view", "{0} ({1} v{2})", labels.processName(process), process.id(),
						String.valueOf(process.version()));
			}
			case BpmnDiagram.ProcessVersionDiagram version -> {
				var process = registry.processDefinition(version.ref());
				yield texts.text(locale, "pac.web.diagram.previousVersion", "{0} ({1} v{2}, previous version)",
						labels.processName(process), process.id(), String.valueOf(process.version()));
			}
		};
	}

	/** Ficha de cada elemento seleccionable de un diagrama. */
	List<DiagramElementInfo> elements(BpmnDiagram view, Locale locale) {
		var labels = texts.labels(locale);
		// Instancias en espera por versión y nodo: v1 y v2 de un proceso comparten identificadores de nodo.
		Map<ProcessDefinitionRef, Map<String, Long>> waiting = new HashMap<>();
		return diagrams.elements(view).stream().map(element -> switch (element) {
			case DiagramElement.Participant participant -> new DiagramElementInfo(participant.elementId(), "participant",
					labels.processName(participant.process()),
					texts.text(locale, "pac.web.diagramElement.participant", "Process (pool)"),
					labels.processDocumentation(participant.process()), process(participant.process(), labels, locale),
					new CodeInfo(participant.process().definedBy(), null), List.of(), List.of(), List.of(), null, null);
			case DiagramElement.FlowNode node -> new DiagramElementInfo(node.elementId(), "node",
					labels.flowNodeName(node.flowNode()), labels.elementType(node.flowNode().elementType()),
					labels.flowNodeDocumentation(node.process(), node.flowNode()), process(node.process(), labels, locale),
					code(node.flowNode().implementation()), rules(node.flowNode(), labels), events(node.flowNode(), labels, locale),
					roles(node.flowNode(), labels, locale), null,
					waiting.computeIfAbsent(node.process().ref(), statistics::waitingByNode).getOrDefault(node.elementId(), 0L));
			case DiagramElement.SequenceFlow flow -> flow(flow, labels, locale);
			case DiagramElement.MessageFlow message -> {
				var event = registry.definitions().message(message.messageFlow().messageRef()).orElseThrow();
				yield new DiagramElementInfo(message.elementId(), "message", labels.messageName(event),
						texts.text(locale, "pac.web.diagramElement.message", "Message between processes"),
						labels.messageDocumentation(event), process(message.process(), labels, locale), null, List.of(),
						List.of(new EventInfo(texts.text(locale, "pac.web.diagramElement.publishes", "Publishes"),
								event.id() + " v" + message.messageFlow().version(), labels.messageName(event),
								event.version(message.messageFlow().version()).orElseThrow().itemDefinition().structureRef())),
						List.of(),
						new TransitionInfo(step(message.process(), message.messageFlow().sourceRef(), labels),
								step(message.target(), message.messageFlow().targetRef(), labels), null, null),
						null);
			}
		}).toList();
	}

	/** Camino de una instancia, o vacío si no existe o su versión no está desplegada. */
	Optional<InstancePathView> path(UUID instanceId, Locale locale) {
		return statistics.path(instanceId).flatMap(path -> registry.definitions().process(path.process())
				.flatMap(process -> diagrams.diagramOf(path.process()).map(view -> {
					var labels = texts.labels(locale);
					var steps = path.steps().stream().map(step -> new InstancePathView.PathStep(step.order(), step.flowNodeId(),
							process.flowNode(step.flowNodeId()).map(labels::flowNodeName).orElse(step.flowNodeId()))).toList();
					var waiting = path.waitingFlowNodeId().flatMap(process::flowNode);
					return new InstancePathView(instanceId.toString(), view.id(), process.id(), labels.processName(process),
							process.version(), versionLabel(process.ref(), locale), path.running(), status(path.running(), locale),
							steps, path.sequenceFlowIds(), waiting.map(FlowNode::id).orElse(null),
							waiting.map(labels::flowNodeName).orElse(null));
				})));
	}

	/** Mapa de calor de la versión que dibuja un diagrama, o vacío si es una colaboración. */
	Optional<HeatmapView> heatmap(BpmnDiagram view, Locale locale) {
		Optional<ProcessDefinitionRef> ref = switch (view) {
			case BpmnDiagram.ProcessDiagram single -> registry.definitions().process(single.processId())
					.map(ProcessDefinition::ref);
			case BpmnDiagram.ProcessVersionDiagram version -> Optional.of(version.ref());
			case BpmnDiagram.CollaborationDiagram collaboration -> Optional.empty();
		};
		return ref.flatMap(statistics::heatmap).flatMap(heatmap -> registry.definitions().process(heatmap.process())
				.map(process -> {
					var labels = texts.labels(locale);
					var nodes = heatmap.nodes().stream().map(node -> {
						var flowNode = process.flowNode(node.flowNodeId()).orElseThrow();
						return new HeatmapView.NodeHeat(node.flowNodeId(), labels.flowNodeName(flowNode),
								labels.elementType(flowNode.elementType()), node.executions(), node.instances(), node.share(),
								node.waiting());
					}).toList();
					var flows = heatmap.flows().stream()
							.map(flow -> new HeatmapView.FlowHeat(flow.sequenceFlowId(), flow.traversals())).toList();
					return new HeatmapView(view.id(), process.id(), labels.processName(process), process.version(),
							versionLabel(process.ref(), locale), heatmap.instances(), heatmap.running(), heatmap.completed(),
							heatmap.maxExecutions(), nodes, flows);
				}));
	}

	private String versionLabel(ProcessDefinitionRef ref, Locale locale) {
		var current = registry.definitions().isCurrent(ref);
		return texts.text(locale, current ? "pac.web.version.current" : "pac.web.version.previous",
				current ? "v{0} · current" : "v{0} · previous version", String.valueOf(ref.version()));
	}

	private String status(boolean running, Locale locale) {
		return running ? texts.text(locale, "pac.web.instanceStatus.ACTIVE", "Active")
				: texts.text(locale, "pac.web.instanceStatus.COMPLETED", "Completed");
	}

	private DiagramElementInfo flow(DiagramElement.SequenceFlow flow, LabelResolver labels, Locale locale) {
		var process = flow.process();
		var source = step(process, flow.sequenceFlow().sourceRef(), labels);
		var target = step(process, flow.sequenceFlow().targetRef(), labels);
		var transition = switch (flow.sequenceFlow()) {
			case SequenceFlow.Unconditional unconditional -> new TransitionInfo(source, target, null, null);
			case SequenceFlow.Conditional conditional ->
				new TransitionInfo(source, target, labels.sequenceFlowConditionExpression(conditional), conditional.when());
		};
		var name = switch (flow.sequenceFlow()) {
			case SequenceFlow.Unconditional unconditional -> flow.elementId();
			case SequenceFlow.Conditional conditional -> labels.sequenceFlowName(conditional);
		};
		return new DiagramElementInfo(flow.elementId(), "flow", name,
				texts.text(locale, "pac.web.diagramElement.flow", "Sequence flow"), "", process(process, labels, locale), null,
				List.of(), List.of(), List.of(), transition, null);
	}

	private ProcessInfo process(ProcessDefinition process, LabelResolver labels, Locale locale) {
		return new ProcessInfo(process.id(), labels.processName(process), process.version(),
				registry.definitions().isCurrent(process.ref()), versionLabel(process.ref(), locale), process.definedBy(),
				process.implementedBy());
	}

	private List<RuleInfo> rules(FlowNode node, LabelResolver labels) {
		return node.businessRuleRefs().stream().flatMap(key -> registry.definitions().businessRule(key).stream())
				.map(rule -> new RuleInfo(rule.id(), rule.version(), labels.businessRuleName(rule),
						labels.businessRuleDocumentation(rule), code(rule.implementation())))
				.toList();
	}

	private List<EventInfo> events(FlowNode node, LabelResolver labels, Locale locale) {
		var events = new ArrayList<EventInfo>();
		switch (node) {
			case FlowNode.StartEvent start -> {
				start.messageDefinition().ifPresent(accepted -> events.add(event(accepted,
						texts.text(locale, "pac.web.diagramElement.consumes", "Consumes"), labels)));
				// El temporizador sale del @Scheduled del método: se muestra tal y como lo programa Spring.
				start.timer().ifPresent(timer -> events.add(new EventInfo(
						texts.text(locale, "pac.web.diagramElement.timer", "Timer"), timer.timeCycle(),
						"(" + timer.schedule().name().toLowerCase(Locale.ROOT) + timer.zone().map(zone -> " · " + zone).orElse("")
								+ ")", "@Scheduled")));
			}
			case FlowNode.EndEvent end -> {
				end.messageDefinition().ifPresent(published -> events.add(
						event(published, texts.text(locale, "pac.web.diagramElement.publishes", "Publishes"), labels)));
				end.error().flatMap(error -> registry.definitions().error(error.errorRef())).ifPresent(error -> events.add(
						new EventInfo(texts.text(locale, "pac.web.diagramElement.throws", "Ends with error"),
								error.id() + " (" + error.errorCode() + ")", labels.errorName(error),
								error.itemDefinition().structureRef())));
			}
			case FlowNode.Activity activity -> {
				activity.publishedMessage().ifPresent(sent -> events.add(event(
						new MessageEventDefinition(sent.messageId(), List.of(sent.version())),
						texts.text(locale, "pac.web.diagramElement.sends", "Sends"), labels)));
			}
			case FlowNode.ExclusiveGateway gateway -> {
				// Las decisiones no intercambian mensajes.
			}
		}
		return events;
	}

	/** Quién puede o debe realizar una tarea humana; ninguno en el resto de nodos. */
	private List<RoleInfo> roles(FlowNode node, LabelResolver labels, Locale locale) {
		if (!(node instanceof FlowNode.UserTask task)) {
			return List.of();
		}
		var roles = new ArrayList<RoleInfo>();
		var owner = texts.text(locale, "pac.web.diagramElement.potentialOwner", "Potential owner");
		task.potentialOwners().forEach(role -> roles.add(role(role, owner, labels)));
		var performer = texts.text(locale, "pac.web.diagramElement.humanPerformer", "Human performer");
		task.humanPerformers().forEach(role -> roles.add(role(role, performer, labels)));
		return roles;
	}

	/** Rol de una tarea humana: el recurso con su nombre y su tipo Java, o la expresión que resuelve la aplicación. */
	private RoleInfo role(ResourceRole role, String kind, LabelResolver labels) {
		return switch (role) {
			case ResourceRole.Reference reference -> {
				var resource = registry.definitions().resource(reference.resourceRef()).orElseThrow();
				yield new RoleInfo(kind, resource.id(), labels.resourceName(resource), resource.definedBy());
			}
			case ResourceRole.Expression expression -> new RoleInfo(kind, null, expression.expression(), null);
		};
	}

	/** Mensaje que publica (una versión: su record) o acepta (una o varias versiones: la familia si son todas) un evento. */
	private EventInfo event(MessageEventDefinition definition, String direction, LabelResolver labels) {
		var event = registry.definitions().message(definition.messageRef()).orElseThrow();
		var versions = definition.versions().stream().map(version -> "v" + version).collect(Collectors.joining(", "));
		var type = definition.versions().size() == 1
				? event.version(definition.versions().getFirst()).orElseThrow().itemDefinition().structureRef()
				: event.itemDefinition().structureRef();
		return new EventInfo(direction, event.id() + " " + versions, labels.messageName(event), type);
	}

	/** Paso en forma corta: «Nombre [ID] · PROCESO vN». */
	private static String step(ProcessDefinition process, String nodeId, LabelResolver labels) {
		var name = process.flowNode(nodeId).map(labels::flowNodeName).orElse(nodeId);
		return name + " [" + nodeId + "] · " + process.id() + " v" + process.version();
	}

	private static CodeInfo code(CodeReference reference) {
		return switch (reference) {
			case CodeReference.TypeReference type -> new CodeInfo(type.className(), null);
			case CodeReference.MethodReference method -> new CodeInfo(method.className(), method.signature());
		};
	}
}
