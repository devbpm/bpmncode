package io.github.eldiegos.bpmncode.model;

/**
 * Definición de evento BPMN ({@code bpmn:eventDefinition}): lo que dispara un evento de inicio o lo que lanza un evento
 * de fin.
 * <p>
 * Responsabilidad: distinguir los eventos simples de los de mensaje, temporizador y error sin cambiar
 * {@link FlowNode.Event}.
 * <p>
 * Invariantes: un evento de inicio puede definirse por mensaje o por temporizador; uno de fin, por mensaje o por error.
 *
 * @see MessageEventDefinition
 * @see TimerEventDefinition
 * @see ErrorEventDefinition
 */
public sealed interface EventDefinition permits MessageEventDefinition, TimerEventDefinition, ErrorEventDefinition {
}
