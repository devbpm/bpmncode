package io.github.eldiegos.bpmncode.model;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Conjunto validado de todas las definiciones de la aplicación: el equivalente de {@code bpmn:definitions}.
 * <p>
 * Responsabilidad: reunir procesos (todas sus versiones desplegadas), reglas de negocio, mensajes, errores, recursos y
 * flujos de mensaje, y permitir buscarlos por identificador o por versión.
 * <p>
 * Invariantes: solo se construye a partir de un modelo sin violaciones; todas las listas están ordenadas de forma
 * determinista.
 *
 * @param processes definiciones de proceso
 * @param businessRules reglas de negocio
 * @param messages mensajes
 * @param errors errores con los que pueden terminar los procesos
 * @param resources recursos (roles) que realizan o pueden reclamar tareas humanas
 * @param messageFlows flujos de mensaje entre procesos
 * @see AnnotationProcessModelReader
 */
public record Definitions(
		List<ProcessDefinition> processes,
		List<BusinessRuleDefinition> businessRules,
		List<Message> messages,
		List<ErrorDefinition> errors,
		List<ResourceDefinition> resources,
		List<MessageFlow> messageFlows) {

	public Definitions {
		processes = List.copyOf(processes);
		businessRules = List.copyOf(businessRules);
		messages = List.copyOf(messages);
		errors = List.copyOf(errors);
		resources = List.copyOf(resources);
		messageFlows = List.copyOf(messageFlows);
	}

	/**
	 * Busca la versión vigente (la mayor) de un proceso.
	 *
	 * @param processId identificador del proceso
	 * @return la definición, o vacío si no existe
	 */
	public Optional<ProcessDefinition> process(String processId) {
		return versions(processId).stream().reduce((older, newer) -> newer);
	}

	/**
	 * Busca una versión concreta de un proceso.
	 *
	 * @param ref identificador y versión
	 * @return la definición, o vacío si no existe esa versión
	 */
	public Optional<ProcessDefinition> process(ProcessDefinitionRef ref) {
		return processes.stream().filter(process -> process.ref().equals(ref)).findFirst();
	}

	/**
	 * Todas las versiones desplegadas de un proceso.
	 *
	 * @param processId identificador del proceso
	 * @return las definiciones, de la más antigua a la vigente
	 */
	public List<ProcessDefinition> versions(String processId) {
		return processes.stream().filter(process -> process.id().equals(processId))
				.sorted(Comparator.comparingInt(ProcessDefinition::version)).toList();
	}

	/**
	 * Indica si una versión es la vigente de su proceso.
	 *
	 * @param ref identificador y versión
	 * @return {@code true} si no hay ninguna versión mayor
	 */
	public boolean isCurrent(ProcessDefinitionRef ref) {
		return process(ref.processId()).map(current -> current.version() == ref.version()).orElse(false);
	}

	/**
	 * Busca una versión de una regla de negocio.
	 *
	 * @param ref regla y versión
	 * @return la regla, o vacío si no existe
	 */
	public Optional<BusinessRuleDefinition> businessRule(BusinessRuleRef ref) {
		return businessRules.stream().filter(rule -> rule.ref().equals(ref)).findFirst();
	}

	/**
	 * Devuelve las versiones de una regla de negocio.
	 *
	 * @param businessRuleId identificador de la regla
	 * @return sus versiones, de la menor a la mayor; vacío si no existe
	 */
	public List<BusinessRuleDefinition> businessRuleVersions(String businessRuleId) {
		return businessRules.stream().filter(rule -> rule.id().equals(businessRuleId))
				.sorted(Comparator.comparing(BusinessRuleDefinition::ref)).toList();
	}

	/**
	 * Busca un mensaje.
	 *
	 * @param messageId identificador del mensaje
	 * @return el mensaje, o vacío si no existe
	 */
	public Optional<Message> message(String messageId) {
		return messages.stream().filter(message -> message.id().equals(messageId)).findFirst();
	}

	/**
	 * Busca un error.
	 *
	 * @param errorId identificador del error
	 * @return el error, o vacío si no existe
	 */
	public Optional<ErrorDefinition> error(String errorId) {
		return errors.stream().filter(error -> error.id().equals(errorId)).findFirst();
	}

	/**
	 * Busca un recurso.
	 *
	 * @param resourceId identificador del recurso
	 * @return el recurso, o vacío si no existe
	 */
	public Optional<ResourceDefinition> resource(String resourceId) {
		return resources.stream().filter(resource -> resource.id().equals(resourceId)).findFirst();
	}

	/**
	 * Busca el error que representa una excepción: el declarado en su clase o en la superclase más cercana que lo
	 * declare, para que una subclase concreta cuente como el error de su familia.
	 *
	 * @param type clase de la excepción
	 * @return el error, o vacío si ni la clase ni sus superclases están anotadas con {@code @ProcessError}
	 */
	public Optional<ErrorDefinition> errorOf(Class<?> type) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			var name = current.getCanonicalName();
			var found = errors.stream().filter(error -> error.itemDefinition().structureRef().equals(name)).findFirst();
			if (found.isPresent()) {
				return found;
			}
		}
		return Optional.empty();
	}

	/**
	 * Busca dónde se aplica una versión de una regla de negocio.
	 *
	 * @param rule regla y versión
	 * @return los nodos que la referencian, por proceso, versión y orden del modelo; vacío si ninguno la usa
	 */
	public List<BusinessRuleUsage> usagesOf(BusinessRuleRef rule) {
		return processes.stream()
				.flatMap(process -> process.flowNodes().stream().filter(node -> node.businessRuleRefs().contains(rule))
						.map(node -> new BusinessRuleUsage(process.ref(), node)))
				.toList();
	}

	/**
	 * Busca la versión de mensaje que transporta un record.
	 *
	 * @param type record anotado con {@code @MessageVersion} (o record {@code @Message} de una sola versión)
	 * @return la versión, o vacío si el tipo no es una versión de ningún mensaje
	 */
	public Optional<MessageVersionRef> messageVersionOf(Class<?> type) {
		return messages.stream()
				.flatMap(message -> message.versionOf(type).map(version -> new MessageVersionRef(message.id(),
						version.version())).stream())
				.findFirst();
	}
}
