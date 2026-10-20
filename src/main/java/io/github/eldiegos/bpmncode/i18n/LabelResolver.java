package io.github.eldiegos.bpmncode.i18n;

import java.text.MessageFormat;

import io.github.eldiegos.bpmncode.model.BpmnElementType;
import io.github.eldiegos.bpmncode.model.BusinessRuleDefinition;
import io.github.eldiegos.bpmncode.model.ErrorDefinition;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.Message;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ResourceDefinition;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import io.github.eldiegos.bpmncode.model.StandardLoopCharacteristics;

/**
 * Traductor de los textos visibles del modelo de procesos.
 * <p>
 * Responsabilidad: dar a la capa de presentación un único punto para traducir nombres y documentación de procesos,
 * nodos de flujo, flujos de secuencia, reglas de negocio, mensajes y tipos de elemento BPMN, con claves derivadas de
 * sus identificadores estables y del vocabulario BPMN, todas bajo el prefijo {@link #keyPrefix()} (por defecto
 * {@code pac.}) para no chocar con las claves de la aplicación:
 * <ul>
 * <li>{@code pac.process.<id>.name}, {@code pac.process.<id>.documentation} y
 * {@code pac.process.<id>.v<n>.documentation};</li>
 * <li>{@code pac.flowNode.<id>.name}, {@code pac.flowNode.<id>.documentation} y
 * {@code pac.flowNode.<id>.v<n>.documentation} y {@code pac.flowNode.<id>.loopCondition};</li>
 * <li>{@code pac.sequenceFlow.<id>.name} y {@code pac.sequenceFlow.<id>.conditionExpression};</li>
 * <li>{@code pac.businessRule.<id>[.v<n>].name} y {@code pac.businessRule.<id>[.v<n>].documentation};</li>
 * <li>{@code pac.message.<id>.name}, {@code pac.error.<id>.name}, {@code pac.resource.<id>.name} y
 * {@code pac.bpmn.<localName>} (p. ej. {@code pac.bpmn.serviceTask}).</li>
 * </ul>
 * <p>
 * Invariantes: si una clave no tiene traducción se devuelve el texto canónico de la anotación; nunca se devuelve una
 * clave en lugar de un texto.
 *
 * @see #CANONICAL
 */
@FunctionalInterface
public interface LabelResolver {

	/** Prefijo por defecto de todas las claves del modelo. */
	String DEFAULT_KEY_PREFIX = "pac.";

	/** Traductor que devuelve los textos canónicos, sustituyendo los argumentos si los hay. */
	LabelResolver CANONICAL = (key, fallback, arguments) -> fallback == null || arguments.length == 0 ? fallback
			: MessageFormat.format(fallback, arguments);

	/**
	 * Traduce una clave.
	 *
	 * @param key clave de mensaje completa (ya con el prefijo)
	 * @param fallback texto que se usa si la clave no está definida
	 * @param arguments argumentos de formato
	 * @return el texto traducido o el de reserva
	 */
	String resolve(String key, String fallback, Object... arguments);

	/**
	 * Prefijo de las claves del modelo. Una implementación puede cambiarlo para encajar en el bundle de la aplicación.
	 *
	 * @return el prefijo, por defecto {@value #DEFAULT_KEY_PREFIX}
	 */
	default String keyPrefix() {
		return DEFAULT_KEY_PREFIX;
	}

	/**
	 * Clave completa de un texto del modelo.
	 *
	 * @param suffix parte de la clave tras el prefijo, p. ej. {@code process.PER-REGISTRATION.name}
	 * @return la clave con el prefijo
	 */
	default String key(String suffix) {
		return keyPrefix() + suffix;
	}

	/**
	 * Nombre visible de un proceso.
	 *
	 * @param process versión del proceso
	 * @return el nombre traducido
	 */
	default String processName(ProcessDefinition process) {
		return resolve(key("process." + process.id() + ".name"), process.name());
	}

	/**
	 * Documentación de una versión de un proceso: primero la propia de esa versión
	 * ({@code pac.process.<id>.v<n>.documentation}) y, si no hay, la común a todas las versiones.
	 *
	 * @param process versión del proceso
	 * @return la documentación traducida
	 */
	default String processDocumentation(ProcessDefinition process) {
		var common = resolve(key("process." + process.id() + ".documentation"), process.documentation());
		return resolve(key("process." + process.id() + ".v" + process.version() + ".documentation"), common);
	}

	/**
	 * Nombre visible de un nodo de flujo.
	 *
	 * @param node nodo
	 * @return el nombre traducido
	 */
	default String flowNodeName(FlowNode node) {
		return flowNodeName(node.id(), node.name());
	}

	/**
	 * Nombre visible de un nodo de flujo a partir de su identificador.
	 *
	 * @param flowNodeId identificador del nodo
	 * @param fallback nombre canónico
	 * @return el nombre traducido
	 */
	default String flowNodeName(String flowNodeId, String fallback) {
		return resolve(key("flowNode." + flowNodeId + ".name"), fallback);
	}

	/**
	 * Documentación visible de un nodo en una versión: un nodo conserva su identificador entre versiones, pero su
	 * documentación puede cambiar (p. ej. la v2 del alta pide además el documento).
	 *
	 * @param process versión del proceso que declara el nodo
	 * @param node nodo
	 * @return la documentación de esa versión, la común del nodo o la del código, por este orden
	 */
	default String flowNodeDocumentation(ProcessDefinition process, FlowNode node) {
		var common = resolve(key("flowNode." + node.id() + ".documentation"), node.documentation());
		return resolve(key("flowNode." + node.id() + ".v" + process.version() + ".documentation"), common);
	}

	/**
	 * Condición visible del bucle estándar de una tarea ({@code pac.flowNode.<id>.loopCondition}).
	 *
	 * @param task tarea
	 * @param loop bucle estándar de la tarea
	 * @return la condición traducida
	 */
	default String flowNodeLoopCondition(FlowNode task, StandardLoopCharacteristics loop) {
		return resolve(key("flowNode." + task.id() + ".loopCondition"), loop.loopCondition());
	}

	/**
	 * Etiqueta visible de una rama de un gateway.
	 *
	 * @param flow flujo condicional
	 * @return la etiqueta traducida
	 */
	default String sequenceFlowName(SequenceFlow.Conditional flow) {
		return resolve(key("sequenceFlow." + flow.id() + ".name"), flow.name());
	}

	/**
	 * Condición visible de una rama de un gateway.
	 *
	 * @param flow flujo condicional
	 * @return la condición traducida
	 */
	default String sequenceFlowConditionExpression(SequenceFlow.Conditional flow) {
		return resolve(key("sequenceFlow." + flow.id() + ".conditionExpression"), flow.conditionExpression());
	}

	/**
	 * Nombre visible de una versión de una regla de negocio.
	 *
	 * @param rule regla
	 * @return el nombre de esa versión, el común de la regla o el del código, por este orden
	 */
	default String businessRuleName(BusinessRuleDefinition rule) {
		var common = resolve(key("businessRule." + rule.id() + ".name"), rule.name());
		return resolve(key("businessRule." + rule.id() + ".v" + rule.version() + ".name"), common);
	}

	/**
	 * Documentación visible de una versión de una regla de negocio.
	 *
	 * @param rule regla
	 * @return la documentación de esa versión, la común de la regla o la del código, por este orden
	 */
	default String businessRuleDocumentation(BusinessRuleDefinition rule) {
		var common = resolve(key("businessRule." + rule.id() + ".documentation"), rule.documentation());
		return resolve(key("businessRule." + rule.id() + ".v" + rule.version() + ".documentation"), common);
	}

	/**
	 * Nombre visible de un mensaje.
	 *
	 * @param message mensaje
	 * @return el nombre traducido
	 */
	default String messageName(Message message) {
		return resolve(key("message." + message.id() + ".name"), message.name());
	}

	/**
	 * Documentación visible de un mensaje.
	 *
	 * @param message mensaje
	 * @return la documentación traducida o, si no hay traducción, la declarada
	 */
	default String messageDocumentation(Message message) {
		return resolve(key("message." + message.id() + ".documentation"), message.documentation());
	}

	/**
	 * Nombre visible de un recurso.
	 *
	 * @param resource recurso
	 * @return el nombre traducido
	 */
	default String resourceName(ResourceDefinition resource) {
		return resolve(key("resource." + resource.id() + ".name"), resource.name());
	}

	/**
	 * Documentación visible de un recurso.
	 *
	 * @param resource recurso
	 * @return la documentación traducida o, si no hay traducción, la declarada
	 */
	default String resourceDocumentation(ResourceDefinition resource) {
		return resolve(key("resource." + resource.id() + ".documentation"), resource.documentation());
	}

	/**
	 * Nombre visible de un error.
	 *
	 * @param error error
	 * @return el nombre traducido
	 */
	default String errorName(ErrorDefinition error) {
		return resolve(key("error." + error.id() + ".name"), error.name());
	}

	/**
	 * Documentación visible de un error.
	 *
	 * @param error error
	 * @return la documentación traducida o, si no hay traducción, la declarada
	 */
	default String errorDocumentation(ErrorDefinition error) {
		return resolve(key("error." + error.id() + ".documentation"), error.documentation());
	}

	/**
	 * Nombre visible de un tipo de elemento BPMN.
	 *
	 * @param elementType tipo de elemento
	 * @return el nombre traducido o, si no hay traducción, el nombre local BPMN
	 */
	default String elementType(BpmnElementType elementType) {
		return resolve(key("bpmn." + elementType.localName()), elementType.localName());
	}
}
