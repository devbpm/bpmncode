package io.github.eldiegos.bpmncode.bpmn;

/**
 * Rectángulo del lienzo del diagrama.
 * <p>
 * Responsabilidad: representar la posición y el tamaño de una figura o de una piscina.
 * <p>
 * Invariantes: ancho y alto no son negativos.
 *
 * @param x esquina superior izquierda, horizontal
 * @param y esquina superior izquierda, vertical
 * @param width ancho
 * @param height alto
 * @see ProcessLayout
 */
record Bounds(double x, double y, double width, double height) {

	Bounds {
		if (width < 0 || height < 0) {
			throw new IllegalArgumentException("Bounds cannot have a negative size");
		}
	}

	double centerX() {
		return x + width / 2;
	}

	double centerY() {
		return y + height / 2;
	}

	double right() {
		return x + width;
	}

	double bottom() {
		return y + height;
	}

	/** Desplaza el rectángulo. */
	Bounds translate(double dx, double dy) {
		return new Bounds(x + dx, y + dy, width, height);
	}
}
