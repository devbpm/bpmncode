package io.github.eldiegos.bpmncode.web;

/**
 * Diagrama que se puede abrir en el visor.
 * <p>
 * Responsabilidad: llenar el selector de diagramas del visor.
 * <p>
 * Invariantes: {@code id} es el de {@link io.github.eldiegos.bpmncode.bpmn.BpmnDiagram#id()}; el nombre está traducido.
 *
 * @param id identificador del diagrama
 * @param label nombre visible, p. ej. «Alta de persona (PER-REGISTRATION v1, versión anterior)»
 * @see DiagramViews#options(java.util.Locale)
 */
public record DiagramOption(String id, String label) {
}
