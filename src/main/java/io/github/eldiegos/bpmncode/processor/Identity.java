package io.github.eldiegos.bpmncode.processor;

import java.util.regex.Pattern;

import javax.lang.model.element.Element;

/**
 * Identidad de los elementos declarados: identificador y nombre.
 * <p>
 * Responsabilidad: comprobar al compilar que cada identificador es un NCName XML (se convierte en el {@code id} de un
 * elemento BPMN) y que el nombre legible no está vacío.
 * <p>
 * Invariantes: aplica la misma expresión que el lector del modelo; la unicidad de los identificadores depende de toda la
 * aplicación y se comprueba al arrancar.
 *
 * @see FlowNodeRules
 */
final class Identity {

	private static final Pattern NCNAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

	private Identity() {
	}

	/**
	 * Comprueba el {@code id} y el {@code name} de una anotación.
	 *
	 * @param annotation anotación con {@code id} y {@code name}
	 * @param kind tipo de elemento, para el mensaje
	 * @param element elemento anotado
	 * @param problems colector de errores
	 */
	static void check(DeclaredAnnotation annotation, String kind, Element element, Problems problems) {
		var id = annotation.string("id");
		if (id.isBlank()) {
			problems.error(element, annotation, kind + " without identifier");
		}
		else if (!NCNAME.matcher(id).matches()) {
			problems.error(element, annotation, "identifier '" + id + "' is invalid: it must start with a letter or '_'"
					+ " and contain only letters, digits, '-', '_' or '.'");
		}
		if (annotation.string("name").isBlank()) {
			problems.error(element, annotation, "the readable name is missing");
		}
	}

	/**
	 * Comprueba que una versión empieza en 1.
	 *
	 * @param annotation anotación con {@code version}
	 * @param attribute nombre del atributo de versión
	 * @param element elemento anotado
	 * @param problems colector de errores
	 */
	static void positiveVersion(DeclaredAnnotation annotation, String attribute, Element element, Problems problems) {
		if (annotation.integer(attribute) < 1) {
			problems.error(element, annotation, "versions start at 1, not " + annotation.integer(attribute));
		}
	}
}
