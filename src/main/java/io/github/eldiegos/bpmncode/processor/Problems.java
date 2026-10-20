package io.github.eldiegos.bpmncode.processor;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;

/**
 * Errores de compilación que informa el procesador.
 * <p>
 * Responsabilidad: hacer fallar {@code javac} señalando el elemento y la anotación culpables, para que el IDE subraye el
 * sitio exacto.
 * <p>
 * Invariantes: todo problema es un error ({@link Diagnostic.Kind#ERROR}), nunca un aviso: una declaración que el
 * framework rechazaría al arrancar no debe compilar.
 *
 * @param messager canal de diagnósticos del compilador
 * @see ProcessAnnotationProcessor
 */
record Problems(Messager messager) {

	/**
	 * Informa de un error.
	 *
	 * @param element elemento anotado
	 * @param annotation anotación que lo provoca
	 * @param message explicación técnica, en el mismo vocabulario que el lector del modelo
	 */
	void error(Element element, DeclaredAnnotation annotation, String message) {
		messager.printMessage(Diagnostic.Kind.ERROR, message, element, annotation.mirror());
	}
}
