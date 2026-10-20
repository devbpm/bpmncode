package io.github.eldiegos.bpmncode.model.reader;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.support.CronExpression;

import io.github.eldiegos.bpmncode.annotation.BusinessRuleTask;
import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ErrorEndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.MessageEndEvent;
import io.github.eldiegos.bpmncode.annotation.MessageStartEvent;
import io.github.eldiegos.bpmncode.annotation.SendTask;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StandardLoop;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.annotation.TimerStartEvent;
import io.github.eldiegos.bpmncode.annotation.UserTask;
import io.github.eldiegos.bpmncode.model.BusinessRuleRef;
import io.github.eldiegos.bpmncode.model.CodeReference;
import io.github.eldiegos.bpmncode.model.CodeReference.MethodReference;
import io.github.eldiegos.bpmncode.model.ErrorEventDefinition;
import io.github.eldiegos.bpmncode.model.EventDefinition;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.MessageEventDefinition;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ResourceRole;
import io.github.eldiegos.bpmncode.model.StandardLoopCharacteristics;
import io.github.eldiegos.bpmncode.model.TimerEventDefinition;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Conversor de un método anotado en un nodo de flujo válido (parse, don't validate).
 * <p>
 * Responsabilidad: comprobar los metadatos de un único método (identidad, flujos de salida, reglas, mensaje y
 * coherencia del gateway con su enum) y, solo si son correctos, construir el {@link FlowNode} con sus flujos
 * declarados.
 * <p>
 * Invariantes: informa de todos los problemas del método antes de decidir; nunca construye un nodo inválido.
 *
 * @see AnnotationProcessModelReader
 */
final class FlowNodeParser {

	private final DeclaredMessages messages;
	private final DeclaredErrors errors;
	private final DeclaredResources resources;
	private final DeclaredBusinessRules rules;
	private final ModelDiagnostics diagnostics;
	private final UnaryOperator<String> placeholders;

	FlowNodeParser(DeclaredMessages messages, DeclaredErrors errors, DeclaredResources resources,
			DeclaredBusinessRules rules, ModelDiagnostics diagnostics, UnaryOperator<String> placeholders) {
		this.messages = messages;
		this.errors = errors;
		this.resources = resources;
		this.rules = rules;
		this.diagnostics = diagnostics;
		this.placeholders = placeholders;
	}

	/**
	 * Convierte un método con una única anotación de nodo.
	 *
	 * @param method método anotado
	 * @param ref versión del proceso a la que pertenece
	 * @return el nodo con sus flujos, o vacío si los metadatos tienen problemas (ya registrados)
	 */
	Optional<ParsedFlowNode> parse(Method method, ProcessDefinitionRef ref) {
		var location = CodeReference.ofMethod(method).toString();
		return Optional.ofNullable(method.getAnnotation(StartEvent.class))
				.map(start -> startEvent(start.id(), start.name(), start.documentation(), start.outgoing(), method, ref,
						location, Optional.of(Optional.empty())))
				.or(() -> Optional.ofNullable(method.getAnnotation(MessageStartEvent.class))
						.map(start -> startEvent(start.id(), start.name(), start.documentation(), start.outgoing(), method, ref,
								location, messageDefinition(start, method, location))))
				.or(() -> Optional.ofNullable(method.getAnnotation(TimerStartEvent.class))
						.map(start -> startEvent(start.id(), start.name(), start.documentation(), start.outgoing(), method, ref,
								location, timerDefinition(method, location))))
				.or(() -> Optional.ofNullable(method.getAnnotation(ServiceTask.class))
						.map(task -> task(new TaskDeclaration(task.id(), task.name(), task.documentation(), task.outgoing(),
								task.businessRuleRefs(), task.loop()), method, ref, location, "a service task", FlowNode.ServiceTask::new)))
				.or(() -> Optional.ofNullable(method.getAnnotation(UserTask.class))
						.map(task -> userTask(task, method, ref, location)))
				.or(() -> Optional.ofNullable(method.getAnnotation(BusinessRuleTask.class))
						.map(task -> businessRuleTask(task, method, ref, location)))
				.or(() -> Optional.ofNullable(method.getAnnotation(SendTask.class))
						.map(task -> sendTask(task, method, ref, location)))
				.or(() -> Optional.ofNullable(method.getAnnotation(ExclusiveGateway.class))
						.map(gateway -> exclusiveGateway(gateway, method, ref, location)))
				.or(() -> Optional.ofNullable(method.getAnnotation(EndEvent.class))
						.map(end -> endEvent(end.id(), end.name(), end.documentation(), method, ref, location,
								Optional.of(Optional.empty()))))
				.or(() -> Optional.ofNullable(method.getAnnotation(MessageEndEvent.class))
						.map(end -> endEvent(end.id(), end.name(), end.documentation(), method, ref, location,
								publishedDefinition(end, location))))
				.or(() -> Optional.ofNullable(method.getAnnotation(ErrorEndEvent.class))
						.map(end -> endEvent(end.id(), end.name(), end.documentation(), method, ref, location,
								errorDefinition(end, location))))
				.flatMap(parsed -> parsed);
	}

	/**
	 * Lo común a las anotaciones de tarea.
	 * <p>
	 * Responsabilidad: tratar igual {@code @ServiceTask}, {@code @UserTask}, {@code @BusinessRuleTask} y
	 * {@code @SendTask}.
	 * <p>
	 * Invariantes: refleja exactamente los atributos de la anotación.
	 *
	 * @param id identificador declarado
	 * @param name nombre declarado
	 * @param documentation documentación declarada
	 * @param outgoing flujos de salida declarados
	 * @param businessRuleRefs referencias a reglas declaradas
	 * @param loop bucle estándar declarado: cero o uno
	 * @see #task(TaskDeclaration, Method, ProcessDefinitionRef, String, String, TaskFactory)
	 */
	private record TaskDeclaration(String id, String name, String documentation, SequenceFlow[] outgoing,
			String[] businessRuleRefs, StandardLoop[] loop) {
	}

	/**
	 * Constructor de una variante de tarea del modelo.
	 * <p>
	 * Responsabilidad: crear la tarea concreta una vez validados sus datos.
	 * <p>
	 * Invariantes: recibe datos ya validados.
	 *
	 * @see FlowNode.Task
	 */
	@FunctionalInterface
	private interface TaskFactory {

		FlowNode.Task create(String id, String processId, String name, String documentation,
				MethodReference implementation, List<BusinessRuleRef> businessRuleRefs,
				Optional<StandardLoopCharacteristics> loopCharacteristics);
	}

	private Optional<ParsedFlowNode> startEvent(String id, String name, String documentation, SequenceFlow[] outgoing,
			Method method, ProcessDefinitionRef ref, String location, Optional<Optional<EventDefinition>> definition) {
		var identified = hasIdentity(id, name, ref, location);
		var flow = singleUnconditional(outgoing, location, "a start event");
		if (!identified || flow.isEmpty() || definition.isEmpty()) {
			return Optional.empty();
		}
		var node = new FlowNode.StartEvent(id, ref.processId(), name, documentation, CodeReference.ofMethod(method),
				definition.get());
		return Optional.of(new ParsedFlowNode(ref, node, List.of(flow.get())));
	}

	/**
	 * Mensaje de un evento de inicio de mensaje, con las versiones que acepta (todas si {@code messageRef} es la familia):
	 * vacío si tiene problemas (ya registrados).
	 */
	private Optional<Optional<EventDefinition>> messageDefinition(MessageStartEvent start, Method method, String location) {
		var reference = messages.resolve(start.messageRef(), location, diagnostics);
		var listens = listensTo(method, start.messageRef(), location);
		return reference.isPresent() && listens
				? Optional.of(Optional.of(new MessageEventDefinition(reference.get().message().id(),
						reference.get().versions())))
				: Optional.empty();
	}

	/**
	 * Temporizador de un evento de inicio de temporizador, leído del {@code @Scheduled} del método con sus {@code ${…}}
	 * resueltos: vacío si tiene problemas (ya registrados).
	 */
	private Optional<Optional<EventDefinition>> timerDefinition(Method method, String location) {
		var schedules = AnnotatedElementUtils.getMergedRepeatableAnnotations(method, Scheduled.class, Schedules.class);
		if (schedules.size() != 1) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, "a timer start event requires exactly one"
					+ " @Scheduled on the same method, found " + schedules.size());
			return Optional.empty();
		}
		var scheduled = schedules.iterator().next();
		try {
			var cron = placeholders.apply(scheduled.cron());
			if (!cron.isBlank()) {
				if (Scheduled.CRON_DISABLED.equals(cron)) {
					diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
							"the @Scheduled of a timer start event is disabled (cron = \"-\")");
					return Optional.empty();
				}
				if (!CronExpression.isValidExpression(cron)) {
					diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, "invalid cron expression '" + cron + "'");
					return Optional.empty();
				}
				var zone = placeholders.apply(scheduled.zone());
				return Optional.of(Optional.of(TimerEventDefinition.cron(cron, zone.isBlank() ? Optional.empty() : Optional.of(zone))));
			}
			var unit = scheduled.timeUnit();
			var rate = interval(scheduled.fixedRate(), scheduled.fixedRateString(), unit);
			var delay = interval(scheduled.fixedDelay(), scheduled.fixedDelayString(), unit);
			if (rate.isPresent() == delay.isPresent()) {
				diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
						"the @Scheduled of a timer start event needs exactly one of cron, fixedRate or fixedDelay");
				return Optional.empty();
			}
			var schedule = rate.isPresent() ? TimerEventDefinition.Schedule.FIXED_RATE : TimerEventDefinition.Schedule.FIXED_DELAY;
			var initialDelay = interval(scheduled.initialDelay(), scheduled.initialDelayString(), unit);
			return Optional.of(Optional.of(TimerEventDefinition.periodic(schedule, rate.or(() -> delay).orElseThrow(), initialDelay)));
		}
		catch (IllegalArgumentException | java.time.format.DateTimeParseException invalid) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
					"the @Scheduled of a timer start event cannot be read: " + invalid.getMessage());
			return Optional.empty();
		}
	}

	/** Intervalo de un atributo de {@code @Scheduled}: el numérico si es positivo o el de texto (número o ISO 8601). */
	private Optional<Duration> interval(long value, String text, TimeUnit unit) {
		if (value >= 0) {
			return Optional.of(Duration.of(value, unit.toChronoUnit()));
		}
		var resolved = placeholders.apply(text).strip();
		if (resolved.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Character.isDigit(resolved.charAt(0)) ? Duration.of(Long.parseLong(resolved), unit.toChronoUnit())
				: Duration.parse(resolved));
	}

	private Optional<ParsedFlowNode> task(TaskDeclaration task, Method method, ProcessDefinitionRef ref, String location,
			String elementName, TaskFactory factory) {
		return task(task, method, ref, location, elementName, true, factory);
	}

	/** Tarea con un requisito propio ya comprobado ({@code ownDataValid}): solo se construye si también se cumple. */
	private Optional<ParsedFlowNode> task(TaskDeclaration task, Method method, ProcessDefinitionRef ref, String location,
			String elementName, boolean ownDataValid, TaskFactory factory) {
		var identified = hasIdentity(task.id(), task.name(), ref, location);
		var outgoing = singleUnconditional(task.outgoing(), location, elementName);
		var ruleRefs = rules.resolve(task.businessRuleRefs(), location, diagnostics);
		var valid = ownDataValid & outgoing.isPresent() & ruleRefs.size() == task.businessRuleRefs().length;
		var loop = loopOf(task.loop(), location);
		valid &= loop.isPresent();
		if (!identified || !valid) {
			return Optional.empty();
		}
		var node = factory.create(task.id(), ref.processId(), task.name(), task.documentation(),
				CodeReference.ofMethod(method), ruleRefs, loop.get());
		return Optional.of(new ParsedFlowNode(ref, node, List.of(outgoing.get())));
	}

	private Optional<ParsedFlowNode> businessRuleTask(BusinessRuleTask task, Method method, ProcessDefinitionRef ref,
			String location) {
		if (task.businessRuleRefs().length == 0) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
					"a business rule task must reference at least one business rule");
			hasIdentity(task.id(), task.name(), ref, location);
			return Optional.empty();
		}
		return task(new TaskDeclaration(task.id(), task.name(), task.documentation(), task.outgoing(),
				task.businessRuleRefs(), task.loop()), method, ref, location, "a business rule task", FlowNode.BusinessRuleTask::new);
	}

	private Optional<ParsedFlowNode> userTask(UserTask task, Method method, ProcessDefinitionRef ref, String location) {
		var owners = roles(Arrays.stream(task.potentialOwners())
				.map(owner -> new DeclaredRole(owner.resourceRef(), owner.expression())).toList(),
				"a potential owner", location);
		var performers = roles(Arrays.stream(task.humanPerformers())
				.map(performer -> new DeclaredRole(performer.resourceRef(), performer.expression())).toList(),
				"a human performer", location);
		var declaration = new TaskDeclaration(task.id(), task.name(), task.documentation(), task.outgoing(),
				task.businessRuleRefs(), task.loop());
		return task(declaration, method, ref, location, "a user task", owners.isPresent() & performers.isPresent(),
				(id, processId, name, documentation, implementation, ruleRefs, loop) -> new FlowNode.UserTask(id, processId,
						name, documentation, implementation, ruleRefs, loop, owners.orElseThrow(), performers.orElseThrow()));
	}

	/**
	 * Lo común a {@code @PotentialOwner} y {@code @HumanPerformer}.
	 * <p>
	 * Responsabilidad: tratar igual los dos roles de una tarea humana.
	 * <p>
	 * Invariantes: refleja exactamente los atributos de la anotación.
	 *
	 * @param resourceRef recurso declarado, o {@code void.class}
	 * @param expression expresión declarada, o vacía
	 */
	private record DeclaredRole(Class<?> resourceRef, String expression) {
	}

	/** Roles de una tarea humana: vacío si alguno tiene problemas (ya registrados). */
	private Optional<List<ResourceRole>> roles(List<DeclaredRole> declared, String kind, String location) {
		var roles = new ArrayList<ResourceRole>();
		var valid = true;
		for (var role : declared) {
			var byResource = role.resourceRef() != void.class;
			if (byResource == !role.expression().isBlank()) {
				diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, kind
						+ " declares exactly one of resourceRef or expression");
				valid = false;
			}
			else if (byResource) {
				var resource = resources.resolve(role.resourceRef(), location, diagnostics);
				resource.ifPresent(found -> roles.add(new ResourceRole.Reference(found.id())));
				valid &= resource.isPresent();
			}
			else {
				roles.add(new ResourceRole.Expression(role.expression().strip()));
			}
		}
		return valid ? Optional.of(List.copyOf(roles)) : Optional.empty();
	}

	private Optional<ParsedFlowNode> sendTask(SendTask task, Method method, ProcessDefinitionRef ref, String location) {
		var sent = concreteMessage(task.messageRef(), location, "a send task sends");
		var declaration = new TaskDeclaration(task.id(), task.name(), task.documentation(), task.outgoing(),
				task.businessRuleRefs(), task.loop());
		return task(declaration, method, ref, location, "a send task", sent.isPresent(),
				(id, processId, name, documentation, implementation, ruleRefs, loop) -> new FlowNode.SendTask(id,
						processId, name, documentation, implementation, ruleRefs, loop, sent.orElseThrow()));
	}

	/**
	 * Versión concreta de un mensaje que publica un nodo: vacía si el tipo no es un mensaje declarado o es la familia
	 * entera (problemas ya registrados).
	 *
	 * @param type tipo declarado en {@code messageRef}
	 * @param location método anotado
	 * @param publishes sujeto del mensaje de error, p. ej. {@code an end event publishes}
	 */
	private Optional<MessageVersionRef> concreteMessage(Class<?> type, String location, String publishes) {
		var reference = messages.resolve(type, location, diagnostics);
		if (reference.isPresent() && !reference.get().concrete()) {
			diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, location, publishes + " one concrete version: use"
					+ " one of the @MessageVersion records of " + type.getSimpleName() + " as messageRef");
			return Optional.empty();
		}
		return reference.map(published -> new MessageVersionRef(published.message().id(),
				published.versions().getFirst()));
	}

	private Optional<ParsedFlowNode> exclusiveGateway(ExclusiveGateway gateway, Method method, ProcessDefinitionRef ref,
			String location) {
		var identified = hasIdentity(gateway.id(), gateway.name(), ref, location);
		var ruleRefs = rules.resolve(gateway.businessRuleRefs(), location, diagnostics);
		var valid = ruleRefs.size() == gateway.businessRuleRefs().length;
		if (gateway.outgoing().length < 2) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
					"an exclusive gateway needs at least two outgoing sequence flows");
			valid = false;
		}
		valid &= outgoingMatchesCode(gateway, method, location);
		var outgoing = Arrays.stream(gateway.outgoing()).map(flow -> conditional(flow, location)).toList();
		valid &= outgoing.stream().noneMatch(flow -> flow.targetRef().isBlank());
		if (!identified || !valid) {
			return Optional.empty();
		}
		var node = new FlowNode.ExclusiveGateway(gateway.id(), ref.processId(), gateway.name(), gateway.documentation(),
				CodeReference.ofMethod(method), ruleRefs);
		return Optional.of(new ParsedFlowNode(ref, node, List.copyOf(outgoing)));
	}

	private Optional<ParsedFlowNode> endEvent(String id, String name, String documentation, Method method,
			ProcessDefinitionRef ref, String location, Optional<Optional<EventDefinition>> definition) {
		var identified = hasIdentity(id, name, ref, location);
		if (!identified || definition.isEmpty()) {
			return Optional.empty();
		}
		var node = new FlowNode.EndEvent(id, ref.processId(), name, documentation, CodeReference.ofMethod(method),
				definition.get());
		return Optional.of(new ParsedFlowNode(ref, node, List.of()));
	}

	/** Mensaje que publica un evento de fin de mensaje: una versión concreta; vacío si tiene problemas (ya registrados). */
	private Optional<Optional<EventDefinition>> publishedDefinition(MessageEndEvent end, String location) {
		return concreteMessage(end.messageRef(), location, "an end event publishes")
				.map(version -> Optional.of(new MessageEventDefinition(version.messageId(), List.of(version.version()))));
	}

	/** Error con el que termina un evento de fin de error; vacío si tiene problemas (ya registrados). */
	private Optional<Optional<EventDefinition>> errorDefinition(ErrorEndEvent end, String location) {
		return errors.resolve(end.errorRef(), location, diagnostics)
				.map(error -> Optional.of(new ErrorEventDefinition(error.id())));
	}

	/** Los flujos de salida de un gateway deben corresponder exactamente a las constantes del enum que devuelve su método. */
	private boolean outgoingMatchesCode(ExclusiveGateway gateway, Method method, String location) {
		var returnType = method.getReturnType();
		if (!returnType.isEnum()) {
			diagnostics.report(ViolationKind.GATEWAY_OUTGOING_MISMATCH, location, "an exclusive gateway method must return"
					+ " an enum whose constants select the outgoing sequence flows; it returns " + returnType.getSimpleName());
			return false;
		}
		var constants = Arrays.stream(returnType.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
		var mapped = new LinkedHashSet<String>();
		var valid = true;
		for (var flow : gateway.outgoing()) {
			if (flow.when().isBlank()) {
				diagnostics.report(ViolationKind.UNIDENTIFIED_ELEMENT, location,
						"sequence flow to '" + flow.targetRef() + "' without 'when'");
				valid = false;
			}
			else if (!constants.contains(flow.when())) {
				diagnostics.report(ViolationKind.GATEWAY_OUTGOING_MISMATCH, location, "'" + flow.when()
						+ "' is not a constant of " + returnType.getSimpleName() + " " + constants);
				valid = false;
			}
			else if (!mapped.add(flow.when())) {
				diagnostics.report(ViolationKind.GATEWAY_OUTGOING_MISMATCH, location,
						"constant '" + flow.when() + "' selects several sequence flows");
				valid = false;
			}
		}
		var unmapped = constants.stream().filter(constant -> !mapped.contains(constant)).toList();
		if (!unmapped.isEmpty()) {
			diagnostics.report(ViolationKind.GATEWAY_OUTGOING_MISMATCH, location, "constants of "
					+ returnType.getSimpleName() + " without an outgoing sequence flow: " + unmapped);
			valid = false;
		}
		return valid;
	}

	private DeclaredSequenceFlow conditional(SequenceFlow flow, String location) {
		if (flow.targetRef().isBlank()) {
			diagnostics.report(ViolationKind.UNKNOWN_TARGET_REF, location,
					"sequence flow '" + flow.when() + "' does not declare its targetRef");
		}
		var name = flow.name().isBlank() ? flow.when() : flow.name();
		var condition = flow.conditionExpression().isBlank() ? flow.when() : flow.conditionExpression();
		return new DeclaredSequenceFlow.Conditional(flow.id(), flow.targetRef(), name, condition, flow.when());
	}

	/** Un evento de inicio o una tarea declaran exactamente un flujo de salida, sin condición. */
	private Optional<DeclaredSequenceFlow> singleUnconditional(SequenceFlow[] outgoing, String location, String element) {
		if (outgoing.length == 0) {
			diagnostics.report(ViolationKind.UNKNOWN_TARGET_REF, location, "does not declare its outgoing sequence flow");
			return Optional.empty();
		}
		if (outgoing.length > 1) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
					element + " declares exactly one outgoing sequence flow; use an exclusive gateway to branch");
			return Optional.empty();
		}
		var flow = outgoing[0];
		var valid = true;
		if (flow.targetRef().isBlank()) {
			diagnostics.report(ViolationKind.UNKNOWN_TARGET_REF, location,
					"does not declare the targetRef of its outgoing sequence flow");
			valid = false;
		}
		if (!flow.when().isBlank() || !flow.conditionExpression().isBlank()) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, "'when' and 'conditionExpression' are only"
					+ " allowed on the outgoing sequence flows of an exclusive gateway, not on " + element);
			valid = false;
		}
		return valid ? Optional.of(new DeclaredSequenceFlow.Unconditional(flow.id(), flow.targetRef())) : Optional.empty();
	}

	/**
	 * Convierte el bucle estándar declarado en una tarea.
	 *
	 * @return vacío si la declaración tiene problemas (ya registrados); si no, el bucle o su ausencia
	 */
	private Optional<Optional<StandardLoopCharacteristics>> loopOf(StandardLoop[] loop, String location) {
		if (loop.length == 0) {
			return Optional.of(Optional.empty());
		}
		if (loop.length > 1) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, "a task declares at most one standard loop");
			return Optional.empty();
		}
		var declared = loop[0];
		var valid = true;
		if (declared.loopCondition().isBlank()) {
			diagnostics.report(ViolationKind.UNIDENTIFIED_ELEMENT, location, "the standard loop does not declare its loopCondition");
			valid = false;
		}
		if (declared.loopMaximum() < 0) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
					"loopMaximum must be zero (unlimited) or positive, not " + declared.loopMaximum());
			valid = false;
		}
		return valid ? Optional.of(Optional.of(new StandardLoopCharacteristics(declared.loopCondition(), declared.loopMaximum())))
				: Optional.empty();
	}

	private boolean hasIdentity(String id, String name, ProcessDefinitionRef ref, String location) {
		if (!diagnostics.isValidId(id, "flow node", location)) {
			return false;
		}
		var named = diagnostics.hasName(name, location);
		return diagnostics.claimBpmnId(id, ref, location) && named;
	}

	private boolean listensTo(Method method, Class<?> messageType, String location) {
		var listener = AnnotatedElementUtils.hasAnnotation(method, EventListener.class);
		var receivesMessage = Arrays.stream(method.getParameterTypes()).anyMatch(parameter -> parameter.isAssignableFrom(messageType));
		if (!listener || !receivesMessage) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, "a message start event with messageRef "
					+ messageType.getSimpleName() + " requires the method to be a Spring event listener receiving that type");
			return false;
		}
		return true;
	}
}
