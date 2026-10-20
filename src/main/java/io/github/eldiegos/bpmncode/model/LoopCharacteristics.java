package io.github.eldiegos.bpmncode.model;

/**
 * Características de bucle de una actividad BPMN ({@code bpmn:loopCharacteristics}).
 * <p>
 * Responsabilidad: describir cómo se repite una actividad, dejando sitio a la multiinstancia sin cambiar
 * {@link FlowNode.Task}.
 * <p>
 * Invariantes: hoy solo existe el bucle estándar, que se ejecuta con un único token.
 *
 * @see StandardLoopCharacteristics
 */
public sealed interface LoopCharacteristics permits StandardLoopCharacteristics {
}
