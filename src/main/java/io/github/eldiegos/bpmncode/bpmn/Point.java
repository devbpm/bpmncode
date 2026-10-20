package io.github.eldiegos.bpmncode.bpmn;

/**
 * Punto del lienzo del diagrama.
 * <p>
 * Responsabilidad: representar un punto de paso de una arista BPMN.
 * <p>
 * Invariantes: es inmutable.
 *
 * @param x coordenada horizontal
 * @param y coordenada vertical
 * @see EdgeRouting
 */
record Point(double x, double y) {
}
