package io.github.eldiegos.bpmncode.trace;

import java.util.List;
import java.util.Optional;

import io.github.eldiegos.bpmncode.model.BpmnElementType;
import io.github.eldiegos.bpmncode.model.BusinessRuleDefinition;
import io.github.eldiegos.bpmncode.model.ErrorDefinition;
import io.github.eldiegos.bpmncode.model.Message;

/**
 * Fila del informe de trazabilidad: Proceso → Nodo de flujo → Regla → Código → Mensaje.
 * <p>
 * Responsabilidad: relacionar un nodo con su proceso, el método Java que lo implementa, las reglas que aplica y los
 * mensajes que lanza (evento de fin) o captura (evento de inicio).
 * <p>
 * Invariantes: un nodo lanza como mucho un mensaje o un error y captura como mucho un mensaje.
 *
 * @param processId proceso
 * @param processName nombre canónico del proceso
 * @param processVersion versión de la definición
 * @param flowNodeId nodo de flujo
 * @param flowNodeName nombre canónico del nodo
 * @param elementType elemento BPMN del nodo
 * @param javaClass clase que implementa el nodo
 * @param javaMethod método que implementa el nodo
 * @param businessRules reglas que aplica
 * @param thrownMessage mensaje que lanza (publica), si lo hay
 * @param caughtMessage mensaje que captura (lo dispara), si lo hay
 * @param thrownError error con el que termina el proceso en el nodo, si lo hay
 * @see TraceabilityReport
 */
public record TraceabilityEntry(
		String processId,
		String processName,
		int processVersion,
		String flowNodeId,
		String flowNodeName,
		BpmnElementType elementType,
		String javaClass,
		String javaMethod,
		List<BusinessRuleDefinition> businessRules,
		Optional<Message> thrownMessage,
		Optional<Message> caughtMessage,
		Optional<ErrorDefinition> thrownError) {
}
