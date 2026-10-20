package io.github.eldiegos.bpmncode.processor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.lang.model.element.Element;

/**
 * Nombres cualificados de las anotaciones y tipos que comprueba el procesador.
 * <p>
 * Responsabilidad: reconocer las anotaciones del framework y de Spring por su nombre, sin depender de sus clases, y
 * buscarlas también como meta-anotaciones (anotaciones compuestas).
 * <p>
 * Invariantes: los nombres coinciden con los de {@code io.github.eldiegos.bpmncode.annotation} y Spring; la búsqueda de
 * meta-anotaciones no entra en {@code java.lang.annotation} y no repite tipos, así que termina siempre.
 *
 * @see ProcessAnnotationProcessor
 */
final class AnnotationNames {

	private static final String PACKAGE = "io.github.eldiegos.bpmncode.annotation.";

	static final String PROCESS = PACKAGE + "Process";
	static final String PROCESS_PART = PACKAGE + "ProcessPart";
	static final String BUSINESS_RULE = PACKAGE + "BusinessRule";
	static final String MESSAGE = PACKAGE + "Message";
	static final String MESSAGE_VERSION = PACKAGE + "MessageVersion";
	static final String PROCESS_ERROR = PACKAGE + "ProcessError";
	static final String PROCESS_RESOURCE = PACKAGE + "ProcessResource";

	static final String START_EVENT = PACKAGE + "StartEvent";
	static final String MESSAGE_START_EVENT = PACKAGE + "MessageStartEvent";
	static final String TIMER_START_EVENT = PACKAGE + "TimerStartEvent";
	static final String SERVICE_TASK = PACKAGE + "ServiceTask";
	static final String USER_TASK = PACKAGE + "UserTask";
	static final String BUSINESS_RULE_TASK = PACKAGE + "BusinessRuleTask";
	static final String SEND_TASK = PACKAGE + "SendTask";
	static final String EXCLUSIVE_GATEWAY = PACKAGE + "ExclusiveGateway";
	static final String END_EVENT = PACKAGE + "EndEvent";
	static final String MESSAGE_END_EVENT = PACKAGE + "MessageEndEvent";
	static final String ERROR_END_EVENT = PACKAGE + "ErrorEndEvent";

	/** Anotaciones de nodo de flujo: cada método lleva como mucho una. */
	static final List<String> FLOW_NODES = List.of(START_EVENT, MESSAGE_START_EVENT, TIMER_START_EVENT, SERVICE_TASK,
			USER_TASK, BUSINESS_RULE_TASK, SEND_TASK, EXCLUSIVE_GATEWAY, END_EVENT, MESSAGE_END_EVENT, ERROR_END_EVENT);

	/** Nodos con exactamente un flujo de salida sin condición. */
	static final List<String> SINGLE_OUTGOING = List.of(START_EVENT, MESSAGE_START_EVENT, TIMER_START_EVENT, SERVICE_TASK,
			USER_TASK, BUSINESS_RULE_TASK, SEND_TASK);

	/** Tareas: admiten bucle estándar. */
	static final List<String> TASKS = List.of(SERVICE_TASK, USER_TASK, BUSINESS_RULE_TASK, SEND_TASK);

	static final String PROCESS_MESSAGE = "io.github.eldiegos.bpmncode.runtime.ProcessMessage";

	static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
	static final String SCHEDULES = "org.springframework.scheduling.annotation.Schedules";
	static final String EVENT_LISTENER = "org.springframework.context.event.EventListener";

	private AnnotationNames() {
	}

	/**
	 * Cuenta las veces que un elemento lleva una anotación, directa o dentro de anotaciones compuestas.
	 *
	 * @param element elemento anotado
	 * @param name nombre cualificado de la anotación buscada
	 * @return el número de apariciones
	 */
	static int countWithMeta(Element element, String name) {
		return countWithMeta(element, name, new HashSet<>());
	}

	private static int countWithMeta(Element element, String name, Set<String> visited) {
		var count = 0;
		for (var mirror : element.getAnnotationMirrors()) {
			var found = DeclaredAnnotation.nameOf(mirror);
			if (found.equals(name)) {
				count++;
			}
			else if (!found.startsWith("java.lang.annotation.") && visited.add(found)) {
				count += countWithMeta(mirror.getAnnotationType().asElement(), name, visited);
			}
		}
		return count;
	}
}
