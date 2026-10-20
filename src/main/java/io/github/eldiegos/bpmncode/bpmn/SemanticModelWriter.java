package io.github.eldiegos.bpmncode.bpmn;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.w3c.dom.Element;

import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.MessageEventDefinition;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ResourceRole;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import io.github.eldiegos.bpmncode.model.StandardLoopCharacteristics;
import io.github.eldiegos.bpmncode.model.TimerEventDefinition;

/**
 * Escritor del modelo semántico BPMN 2.0 (estructuras, mensajes, colaboración y procesos).
 * <p>
 * Responsabilidad: traducir el alcance de un diagrama a elementos {@code bpmn:itemDefinition}, {@code bpmn:message},
 * {@code bpmn:collaboration} y {@code bpmn:process} con sus nodos, flujos y documentación de negocio; lo que BPMN no
 * define (versión, código que implementa cada elemento, reglas de negocio y constante de cada rama) va como extensión
 * {@code pac:}.
 * <p>
 * Invariantes: los ids de los elementos son los declarados en las anotaciones; los procesos de fuera del diagrama son
 * participantes sin {@code processRef}; los textos visibles pasan por el {@code LabelResolver} del alcance;
 * {@code bpmn:documentation} solo lleva texto de negocio.
 *
 * @see DiagramInterchangeWriter
 */
final class SemanticModelWriter {

	private final BpmnDocument document;
	private final DiagramScope scope;

	SemanticModelWriter(BpmnDocument document, DiagramScope scope) {
		this.document = document;
		this.scope = scope;
	}

	/** Escribe estructuras, mensajes, colaboración y procesos dentro de {@code definitions}. */
	void write() {
		var definitions = document.definitionsElement();
		messages(definitions);
		errors(definitions);
		resources(definitions);
		definitions.appendChild(collaboration());
		scope.processes().forEach(process -> definitions.appendChild(process(process)));
	}

	private void messages(Element definitions) {
		var messageIds = new TreeSet<String>();
		scope.messageFlows().forEach(flow -> messageIds.add(flow.messageRef()));
		scope.processes().forEach(process -> process.flowNodes().forEach(node -> messageRefOf(node).ifPresent(messageIds::add)));
		messageIds.forEach(messageId -> {
			var message = scope.definitions().message(messageId).orElseThrow();
			var itemDefinition = document.bpmn("itemDefinition");
			itemDefinition.setAttribute("id", message.itemDefinition().id());
			itemDefinition.setAttribute("structureRef", message.itemDefinition().structureRef());
			definitions.appendChild(itemDefinition);
			message.versions().forEach(version -> {
				var versionItem = document.bpmn("itemDefinition");
				versionItem.setAttribute("id", version.itemDefinition().id());
				versionItem.setAttribute("structureRef", version.itemDefinition().structureRef());
				definitions.appendChild(versionItem);
			});
			var element = document.bpmn("message");
			element.setAttribute("id", DiagramScope.messageId(messageId));
			element.setAttribute("name", scope.labels().messageName(message));
			element.setAttribute("itemRef", message.itemDefinition().id());
			document.document(element, message.documentation());
			// Cada versión del contrato: su estructura y la huella de su esquema, la misma que congela el fichero de bloqueo.
			message.versions().forEach(version -> {
				var extension = document.appendExtension(element, "messageVersion");
				extension.setAttribute("version", Integer.toString(version.version()));
				extension.setAttribute("itemRef", version.itemDefinition().id());
				extension.setAttribute("schemaHash", version.schema().fingerprint());
			});
			definitions.appendChild(element);
		});
	}

	/** Recursos que referencian las tareas humanas del diagrama, con sus parámetros y el tipo Java que los declara. */
	private void resources(Element definitions) {
		var resourceIds = new TreeSet<String>();
		scope.processes().forEach(process -> process.flowNodes().stream()
				.filter(FlowNode.UserTask.class::isInstance).map(FlowNode.UserTask.class::cast)
				.flatMap(task -> Stream.concat(task.potentialOwners().stream(), task.humanPerformers().stream()))
				.filter(ResourceRole.Reference.class::isInstance)
				.forEach(role -> resourceIds.add(((ResourceRole.Reference) role).resourceRef())));
		resourceIds.forEach(resourceId -> {
			var resource = scope.definitions().resource(resourceId).orElseThrow();
			resource.parameters().forEach(parameter -> {
				var itemDefinition = document.bpmn("itemDefinition");
				itemDefinition.setAttribute("id", parameter.type().id());
				itemDefinition.setAttribute("structureRef", parameter.type().structureRef());
				definitions.appendChild(itemDefinition);
			});
			var element = document.bpmn("resource");
			element.setAttribute("id", DiagramScope.resourceId(resourceId));
			element.setAttribute("name", scope.labels().resourceName(resource));
			document.document(element, scope.labels().resourceDocumentation(resource));
			document.appendExtension(element, "definedBy").setAttribute("class", resource.definedBy());
			resource.parameters().forEach(parameter -> {
				var resourceParameter = document.bpmn("resourceParameter");
				resourceParameter.setAttribute("id", DiagramScope.resourceId(resourceId) + "_" + parameter.name());
				resourceParameter.setAttribute("name", parameter.name());
				resourceParameter.setAttribute("type", parameter.type().id());
				resourceParameter.setAttribute("isRequired", Boolean.toString(parameter.required()));
				element.appendChild(resourceParameter);
			});
			definitions.appendChild(element);
		});
	}

	/** Errores con los que terminan los procesos del diagrama: su estructura (la excepción Java) y su código. */
	private void errors(Element definitions) {
		var errorIds = new TreeSet<String>();
		scope.processes().forEach(process -> process.flowNodes().stream()
				.filter(FlowNode.EndEvent.class::isInstance)
				.flatMap(node -> ((FlowNode.EndEvent) node).error().stream())
				.forEach(error -> errorIds.add(error.errorRef())));
		errorIds.forEach(errorId -> {
			var error = scope.definitions().error(errorId).orElseThrow();
			var itemDefinition = document.bpmn("itemDefinition");
			itemDefinition.setAttribute("id", error.itemDefinition().id());
			itemDefinition.setAttribute("structureRef", error.itemDefinition().structureRef());
			definitions.appendChild(itemDefinition);
			var element = document.bpmn("error");
			element.setAttribute("id", DiagramScope.errorId(errorId));
			element.setAttribute("name", scope.labels().errorName(error));
			element.setAttribute("errorCode", error.errorCode());
			element.setAttribute("structureRef", error.itemDefinition().id());
			document.document(element, scope.labels().errorDocumentation(error));
			definitions.appendChild(element);
		});
	}

	private Element collaboration() {
		var labels = scope.labels();
		var collaboration = document.bpmn("collaboration");
		collaboration.setAttribute("id", "Collaboration_" + scope.diagram().id());
		for (var processId : scope.pools()) {
			var process = scope.shown(processId);
			var participant = document.bpmn("participant");
			participant.setAttribute("id", DiagramScope.participantId(processId));
			participant.setAttribute("name", labels.processName(process));
			document.setExtensionAttribute(participant, "version", Integer.toString(process.version()));
			if (scope.isInDiagram(processId)) {
				participant.setAttribute("processRef", processId);
				document.document(participant, labels.processDocumentation(process));
			}
			else {
				document.document(participant, labels.resolve(labels.key("bpmn.doc.externalProcess"),
						"Process {0} v{1} (outside this diagram).", processId, process.version())
						+ " " + labels.processDocumentation(process));
			}
			collaboration.appendChild(participant);
		}
		for (var flow : scope.messageFlows()) {
			var messageFlow = document.bpmn("messageFlow");
			messageFlow.setAttribute("id", flow.id());
			messageFlow.setAttribute("sourceRef", scope.isInDiagram(flow.sourceProcessId()) ? flow.sourceRef()
					: DiagramScope.participantId(flow.sourceProcessId()));
			messageFlow.setAttribute("targetRef", scope.isInDiagram(flow.targetProcessId()) ? flow.targetRef()
					: DiagramScope.participantId(flow.targetProcessId()));
			messageFlow.setAttribute("messageRef", DiagramScope.messageId(flow.messageRef()));
			document.setExtensionAttribute(messageFlow, "version", Integer.toString(flow.version()));
			collaboration.appendChild(messageFlow);
		}
		return collaboration;
	}

	private Element process(ProcessDefinition process) {
		var element = document.bpmn("process");
		element.setAttribute("id", process.id());
		element.setAttribute("name", scope.labels().processName(process));
		// Lo ejecuta el código Java anotado, no un motor BPMN.
		element.setAttribute("isExecutable", "false");
		document.setExtensionAttribute(element, "version", Integer.toString(process.version()));
		document.document(element, scope.labels().processDocumentation(process));
		document.appendExtension(element, "definedBy").setAttribute("class", process.definedBy());
		process.implementedBy().forEach(type -> document.appendExtension(element, "implementedBy").setAttribute("class", type));
		process.flowNodes().forEach(node -> element.appendChild(flowNode(process, node)));
		process.sequenceFlows().forEach(flow -> element.appendChild(sequenceFlow(flow)));
		return element;
	}

	private Element sequenceFlow(SequenceFlow flow) {
		var element = document.bpmn("sequenceFlow");
		element.setAttribute("id", flow.id());
		switch (flow) {
			case SequenceFlow.Unconditional unconditional -> {
				element.setAttribute("sourceRef", flow.sourceRef());
				element.setAttribute("targetRef", flow.targetRef());
			}
			case SequenceFlow.Conditional conditional -> {
				element.setAttribute("name", scope.labels().sequenceFlowName(conditional));
				element.setAttribute("sourceRef", flow.sourceRef());
				element.setAttribute("targetRef", flow.targetRef());
				document.setExtensionAttribute(element, "when", conditional.when());
				var condition = document.bpmn("conditionExpression");
				document.setXsiType(condition, "bpmn:tFormalExpression");
				condition.setAttribute("language", "text/plain");
				condition.setTextContent(scope.labels().sequenceFlowConditionExpression(conditional));
				element.appendChild(condition);
			}
		}
		return element;
	}

	private Element flowNode(ProcessDefinition process, FlowNode node) {
		var element = document.bpmn(node.elementType().localName());
		element.setAttribute("id", node.id());
		element.setAttribute("name", scope.labels().flowNodeName(node));
		if (node instanceof FlowNode.SendTask task) {
			// La tarea de envío referencia el mensaje directamente, sin definición de evento.
			element.setAttribute("messageRef", DiagramScope.messageId(task.message().messageId()));
			document.setExtensionAttribute(element, "version", Integer.toString(task.message().version()));
		}
		document.document(element, scope.labels().flowNodeDocumentation(process, node));
		var implementation = document.appendExtension(element, "implementation");
		implementation.setAttribute("class", node.implementation().className());
		implementation.setAttribute("method", node.implementation().signature());
		node.businessRuleRefs().forEach(ref -> {
			var rule = document.appendExtension(element, "businessRuleRef");
			rule.setAttribute("id", ref.id());
			rule.setAttribute("version", Integer.toString(ref.version()));
			rule.setAttribute("name", scope.labels().businessRuleName(scope.definitions().businessRule(ref).orElseThrow()));
		});
		process.incoming(node.id()).forEach(flow -> document.appendText(element, "incoming", flow.id()));
		process.outgoing(node.id()).forEach(flow -> document.appendText(element, "outgoing", flow.id()));
		// Los roles van antes del bucle, como exige el orden de tActivity.
		if (node instanceof FlowNode.UserTask task) {
			resourceRoles(element, task.id() + "_PotentialOwner", "potentialOwner", task.potentialOwners());
			resourceRoles(element, task.id() + "_HumanPerformer", "humanPerformer", task.humanPerformers());
		}
		if (node instanceof FlowNode.Task task) {
			task.loopCharacteristics().ifPresent(loop -> element.appendChild(standardLoop(task, loop)));
		}
		messageDefinitionOf(node).ifPresent(message -> {
			var definition = document.bpmn("messageEventDefinition");
			definition.setAttribute("id", node.id() + "_MessageEventDefinition");
			definition.setAttribute("messageRef", DiagramScope.messageId(message.messageRef()));
			// Un evento de fin publica una versión; uno de inicio acepta una lista.
			if (node instanceof FlowNode.EndEvent) {
				document.setExtensionAttribute(definition, "version", Integer.toString(message.publishedVersion()));
			}
			else {
				document.setExtensionAttribute(definition, "acceptedVersions", message.versions().stream()
						.map(String::valueOf).collect(Collectors.joining(" ")));
			}
			element.appendChild(definition);
		});
		if (node instanceof FlowNode.StartEvent start) {
			start.timer().ifPresent(timer -> element.appendChild(timerDefinition(start, timer)));
		}
		if (node instanceof FlowNode.EndEvent end) {
			end.error().ifPresent(error -> {
				var definition = document.bpmn("errorEventDefinition");
				definition.setAttribute("id", end.id() + "_ErrorEventDefinition");
				definition.setAttribute("errorRef", DiagramScope.errorId(error.errorRef()));
				element.appendChild(definition);
			});
		}
		return element;
	}

	/** Roles de una tarea humana: cada uno apunta a un recurso o lleva la expresión que resuelve la aplicación. */
	private void resourceRoles(Element task, String idPrefix, String localName, List<ResourceRole> roles) {
		for (var index = 0; index < roles.size(); index++) {
			var element = document.bpmn(localName);
			element.setAttribute("id", idPrefix + "_" + (index + 1));
			switch (roles.get(index)) {
				case ResourceRole.Reference reference ->
					document.appendText(element, "resourceRef", DiagramScope.resourceId(reference.resourceRef()));
				case ResourceRole.Expression expression -> {
					var assignment = document.bpmn("resourceAssignmentExpression");
					var formal = document.bpmn("formalExpression");
					formal.setAttribute("language", "text/plain");
					formal.setTextContent(expression.expression());
					assignment.appendChild(formal);
					element.appendChild(assignment);
				}
			}
			task.appendChild(element);
		}
	}

	/** Bucle estándar: va tras {@code bpmn:outgoing}, como exige el orden de {@code tActivity}; la condición se evalúa al final. */
	private Element standardLoop(FlowNode.Task task, StandardLoopCharacteristics loop) {
		var element = document.bpmn("standardLoopCharacteristics");
		element.setAttribute("id", task.id() + "_StandardLoopCharacteristics");
		element.setAttribute("testBefore", "false");
		loop.maximum().ifPresent(maximum -> element.setAttribute("loopMaximum", Integer.toString(maximum)));
		var condition = document.bpmn("loopCondition");
		document.setXsiType(condition, "bpmn:tFormalExpression");
		condition.setAttribute("language", "text/plain");
		condition.setTextContent(scope.labels().flowNodeLoopCondition(task, loop));
		element.appendChild(condition);
		return element;
	}

	/**
	 * Temporizador de un evento de inicio: {@code bpmn:timeCycle} con la expresión cron de Spring o con el intervalo
	 * repetido de ISO 8601 ({@code R/PT5M}); el tipo de programación, la espera inicial y la zona van como extensión.
	 */
	private Element timerDefinition(FlowNode.StartEvent start, TimerEventDefinition timer) {
		var definition = document.bpmn("timerEventDefinition");
		definition.setAttribute("id", start.id() + "_TimerEventDefinition");
		document.setExtensionAttribute(definition, "schedule", switch (timer.schedule()) {
			case CRON -> "cron";
			case FIXED_RATE -> "fixedRate";
			case FIXED_DELAY -> "fixedDelay";
		});
		timer.initialDelay().ifPresent(delay -> document.setExtensionAttribute(definition, "initialDelay", delay.toString()));
		timer.zone().ifPresent(zone -> document.setExtensionAttribute(definition, "zone", zone));
		var cycle = document.bpmn("timeCycle");
		document.setXsiType(cycle, "bpmn:tFormalExpression");
		if (timer.cron().isPresent()) {
			cycle.setAttribute("language", "spring-cron");
		}
		cycle.setTextContent(timer.timeCycle());
		definition.appendChild(cycle);
		return definition;
	}

	/** Mensaje que recibe (evento de inicio) o publica (evento de fin, tarea de envío) un nodo. */
	private static Optional<String> messageRefOf(FlowNode node) {
		return node.publishedMessage().map(MessageVersionRef::messageId)
				.or(() -> messageDefinitionOf(node).map(MessageEventDefinition::messageRef));
	}

	/** Definición de mensaje de un nodo, con sus versiones. */
	private static Optional<MessageEventDefinition> messageDefinitionOf(FlowNode node) {
		return switch (node) {
			case FlowNode.Event event -> event.messageDefinition();
			case FlowNode.Activity activity -> Optional.empty();
			case FlowNode.Gateway gateway -> Optional.empty();
		};
	}
}
