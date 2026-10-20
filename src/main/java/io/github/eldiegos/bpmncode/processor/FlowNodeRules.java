package io.github.eldiegos.bpmncode.processor;

import static io.github.eldiegos.bpmncode.processor.AnnotationNames.BUSINESS_RULE_TASK;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.ERROR_END_EVENT;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.EVENT_LISTENER;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.EXCLUSIVE_GATEWAY;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.FLOW_NODES;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE_END_EVENT;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE_START_EVENT;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE_VERSION;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_ERROR;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_PART;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_RESOURCE;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.SCHEDULED;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.SCHEDULES;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.SEND_TASK;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.SINGLE_OUTGOING;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.TASKS;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.TIMER_START_EVENT;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.USER_TASK;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Reglas de compilación de los métodos anotados como nodo de flujo.
 * <p>
 * Responsabilidad: comprobar lo que un nodo exige de su propio método y de su clase: pertenecer a un proceso, ser un
 * único nodo, sus flujos de salida, su bucle, su temporizador ({@code @Scheduled}), su listener de mensajes, el tipo de
 * su {@code messageRef} y, en un gateway, la correspondencia entre sus ramas y el {@code enum} que devuelve.
 * <p>
 * Invariantes: solo usa el método, su clase y los tipos que referencian, así que da el mismo resultado compilando la
 * clase sola o la aplicación entera; lo que depende de otras clases (destinos en otra {@code @ProcessPart}, reglas,
 * publicadores, alcanzabilidad) lo comprueba el lector del modelo al arrancar.
 *
 * @see ProcessAnnotationProcessor
 */
final class FlowNodeRules {

	/** Valor de {@code Scheduled.CRON_DISABLED}. */
	private static final String CRON_DISABLED = "-";

	private final Elements elements;
	private final Types types;
	private final Problems problems;

	FlowNodeRules(Elements elements, Types types, Problems problems) {
		this.elements = elements;
		this.types = types;
		this.problems = problems;
	}

	/**
	 * Comprueba un nodo de flujo.
	 *
	 * @param method método anotado
	 * @param node anotación de nodo
	 */
	void check(ExecutableElement method, DeclaredAnnotation node) {
		var declared = method.getAnnotationMirrors().stream().map(DeclaredAnnotation::nameOf).filter(FLOW_NODES::contains)
				.toList();
		// Lo que es del método se informa una sola vez, con su primera anotación de nodo.
		if (declared.getFirst().equals(node.name())) {
			if (declared.size() > 1) {
				problems.error(method, node, "the method declares several flow nodes: " + declared.stream()
						.map(name -> "@" + name.substring(name.lastIndexOf('.') + 1)).toList());
			}
			membership(method, node);
		}
		Identity.check(node, "flow node", method, problems);
		if (SINGLE_OUTGOING.contains(node.name())) {
			singleUnconditional(method, node);
		}
		if (TASKS.contains(node.name())) {
			loop(method, node);
		}
		// El resto de nodos no exige nada más de su método.
		if (node.name().equals(TIMER_START_EVENT)) {
			timer(method, node);
		}
		else if (node.name().equals(MESSAGE_START_EVENT)) {
			messageStart(method, node);
		}
		else if (node.name().equals(MESSAGE_END_EVENT)) {
			publishedMessage(method, node, "an end event publishes");
		}
		else if (node.name().equals(ERROR_END_EVENT)) {
			node.type("errorRef").flatMap(FlowNodeRules::typeElement).filter(type -> !has(type, PROCESS_ERROR))
					.ifPresent(type -> problems.error(method, node, "errorRef " + type.getQualifiedName()
							+ " is not an exception annotated with @ProcessError"));
		}
		else if (node.name().equals(SEND_TASK)) {
			if (node.type("messageRef").filter(type -> type.getKind() != TypeKind.VOID).isEmpty()) {
				problems.error(method, node, "a send task sends a message: declare its messageRef");
			}
			publishedMessage(method, node, "a send task sends");
		}
		else if (node.name().equals(EXCLUSIVE_GATEWAY)) {
			gateway(method, node);
		}
		else if (node.name().equals(USER_TASK)) {
			node.annotations("potentialOwners").forEach(role -> resourceRole(method, node, role, "a potential owner"));
			node.annotations("humanPerformers").forEach(role -> resourceRole(method, node, role, "a human performer"));
		}
		else if (node.name().equals(BUSINESS_RULE_TASK) && node.strings("businessRuleRefs").isEmpty()) {
			problems.error(method, node, "a business rule task must reference at least one business rule");
		}
	}

	private void membership(ExecutableElement method, DeclaredAnnotation node) {
		var type = method.getEnclosingElement();
		var process = DeclaredAnnotation.on(type, PROCESS, elements).isPresent();
		var part = DeclaredAnnotation.on(type, PROCESS_PART, elements).isPresent();
		if (process && part) {
			problems.error(method, node, "class " + type.getSimpleName() + " declares both @Process and @ProcessPart");
		}
		else if (!process && !part) {
			problems.error(method, node, "class " + type.getSimpleName() + " declares neither @Process nor @ProcessPart");
		}
	}

	private void singleUnconditional(ExecutableElement method, DeclaredAnnotation node) {
		var outgoing = node.annotations("outgoing");
		if (outgoing.size() != 1) {
			problems.error(method, node, outgoing.isEmpty() ? "does not declare its outgoing sequence flow"
					: "declares exactly one outgoing sequence flow; use an exclusive gateway to branch");
			return;
		}
		var flow = outgoing.getFirst();
		if (flow.string("targetRef").isBlank()) {
			problems.error(method, node, "does not declare the targetRef of its outgoing sequence flow");
		}
		if (!flow.string("when").isBlank() || !flow.string("conditionExpression").isBlank()) {
			problems.error(method, node, "'when' and 'conditionExpression' are only allowed on the outgoing sequence"
					+ " flows of an exclusive gateway");
		}
	}

	private void loop(ExecutableElement method, DeclaredAnnotation node) {
		var loops = node.annotations("loop");
		if (loops.size() > 1) {
			problems.error(method, node, "a task declares at most one standard loop");
		}
		for (var loop : loops) {
			if (loop.string("loopCondition").isBlank()) {
				problems.error(method, node, "the standard loop does not declare its loopCondition");
			}
			if (loop.integer("loopMaximum") < 0) {
				problems.error(method, node, "loopMaximum must be zero (unlimited) or positive, not "
						+ loop.integer("loopMaximum"));
			}
		}
	}

	/** El temporizador es el {@code @Scheduled} del método: exactamente uno, activo y con un solo disparador. */
	private void timer(ExecutableElement method, DeclaredAnnotation node) {
		var container = DeclaredAnnotation.on(method, SCHEDULES, elements).map(schedules -> schedules.annotations("value")
				.size()).orElse(0);
		var count = AnnotationNames.countWithMeta(method, SCHEDULED) + container;
		if (count != 1) {
			problems.error(method, node, "a @TimerStartEvent requires exactly one @Scheduled on the same method, found "
					+ count + " (the timer of the start event is the Spring @Scheduled of the method)");
			return;
		}
		// Solo se puede leer el @Scheduled escrito en el método; el de una anotación compuesta lo comprueba el arranque.
		DeclaredAnnotation.on(method, SCHEDULED, elements).ifPresent(scheduled -> {
			if (CRON_DISABLED.equals(scheduled.string("cron"))) {
				problems.error(method, node, "the @Scheduled of a @TimerStartEvent is disabled (cron = \"-\")");
				return;
			}
			var triggers = Stream.of(scheduled.isWritten("cron"),
					scheduled.isWritten("fixedRate") || scheduled.isWritten("fixedRateString"),
					scheduled.isWritten("fixedDelay") || scheduled.isWritten("fixedDelayString"))
					.filter(Boolean::booleanValue).count();
			if (triggers != 1) {
				problems.error(method, node, "the @Scheduled of a @TimerStartEvent needs exactly one of cron, fixedRate"
						+ " or fixedDelay");
			}
		});
	}

	/** Un evento de inicio de mensaje es un listener de Spring que recibe un mensaje declarado. */
	private void messageStart(ExecutableElement method, DeclaredAnnotation node) {
		var messageType = node.type("messageRef").flatMap(FlowNodeRules::typeElement);
		if (messageType.isEmpty()) {
			return;
		}
		var type = messageType.get();
		if (!has(type, MESSAGE) && !has(type, MESSAGE_VERSION)) {
			problems.error(method, node, "messageRef " + type.getQualifiedName()
					+ " is neither a @Message nor one of its @MessageVersion records");
		}
		var listener = AnnotationNames.countWithMeta(method, EVENT_LISTENER) > 0;
		var receives = method.getParameters().stream()
				.anyMatch(parameter -> types.isAssignable(types.erasure(type.asType()), types.erasure(parameter.asType())));
		if (!listener || !receives) {
			problems.error(method, node, "a message start event with messageRef " + type.getSimpleName()
					+ " requires the method to be a Spring event listener receiving that type");
		}
	}

	/** Un evento de fin o una tarea de envío que publica un mensaje publica una versión concreta: un record. */
	private void publishedMessage(ExecutableElement method, DeclaredAnnotation node, String publishes) {
		var messageType = node.type("messageRef").filter(type -> type.getKind() != TypeKind.VOID)
				.flatMap(FlowNodeRules::typeElement);
		if (messageType.isEmpty()) {
			return;
		}
		var type = messageType.get();
		if (type.getKind() != ElementKind.RECORD && has(type, MESSAGE)) {
			problems.error(method, node, publishes + " one concrete version: use one of the @MessageVersion records of "
					+ type.getSimpleName() + " as messageRef");
		}
		else if (!has(type, MESSAGE) && !has(type, MESSAGE_VERSION)) {
			problems.error(method, node, "messageRef " + type.getQualifiedName()
					+ " is neither a @Message nor one of its @MessageVersion records");
		}
	}

	/** Un rol de una tarea humana dice quién por un recurso declarado o por una expresión, nunca por las dos cosas. */
	private void resourceRole(ExecutableElement method, DeclaredAnnotation node, DeclaredAnnotation role, String kind) {
		var resource = role.type("resourceRef").filter(type -> type.getKind() != TypeKind.VOID)
				.flatMap(FlowNodeRules::typeElement);
		if (resource.isPresent() == !role.string("expression").isBlank()) {
			problems.error(method, node, kind + " declares exactly one of resourceRef or expression");
		}
		resource.filter(type -> !has(type, PROCESS_RESOURCE)).ifPresent(type -> problems.error(method, node,
				"resourceRef " + type.getQualifiedName() + " is not a type annotated with @ProcessResource"));
	}

	/** Las ramas de un gateway son exactamente las constantes del {@code enum} que devuelve su método. */
	private void gateway(ExecutableElement method, DeclaredAnnotation node) {
		var outgoing = node.annotations("outgoing");
		if (outgoing.size() < 2) {
			problems.error(method, node, "an exclusive gateway needs at least two outgoing sequence flows");
		}
		outgoing.stream().filter(flow -> flow.string("targetRef").isBlank()).forEach(flow -> problems.error(method, node,
				"sequence flow '" + flow.string("when") + "' does not declare its targetRef"));
		var returned = typeElement(method.getReturnType()).filter(type -> type.getKind() == ElementKind.ENUM);
		if (returned.isEmpty()) {
			problems.error(method, node, "an exclusive gateway method must return an enum whose constants select the"
					+ " outgoing sequence flows; it returns " + method.getReturnType());
			return;
		}
		var constants = returned.get().getEnclosedElements().stream()
				.filter(element -> element.getKind() == ElementKind.ENUM_CONSTANT)
				.map(element -> element.getSimpleName().toString()).toList();
		var mapped = new LinkedHashSet<String>();
		for (var flow : outgoing) {
			var when = flow.string("when");
			if (when.isBlank()) {
				problems.error(method, node, "sequence flow to '" + flow.string("targetRef") + "' without 'when'");
			}
			else if (!constants.contains(when)) {
				problems.error(method, node, "'" + when + "' is not a constant of " + returned.get().getSimpleName() + " "
						+ constants);
			}
			else if (!mapped.add(when)) {
				problems.error(method, node, "constant '" + when + "' selects several sequence flows");
			}
		}
		List<String> unmapped = constants.stream().filter(constant -> !mapped.contains(constant)).toList();
		if (!unmapped.isEmpty()) {
			problems.error(method, node, "constants of " + returned.get().getSimpleName()
					+ " without an outgoing sequence flow: " + unmapped);
		}
	}

	private boolean has(Element element, String annotation) {
		return DeclaredAnnotation.on(element, annotation, elements).isPresent();
	}

	private static java.util.Optional<TypeElement> typeElement(TypeMirror type) {
		return type instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element
				? java.util.Optional.of(element) : java.util.Optional.empty();
	}
}
