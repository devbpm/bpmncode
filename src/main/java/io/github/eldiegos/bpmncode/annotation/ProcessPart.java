package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca una clase que implementa nodos de un proceso declarado en otra clase.
 * <p>
 * Responsabilidad: permitir repartir un proceso entre varias clases (p. ej. el listener que lo inicia y el caso de uso
 * que lo continúa). No tiene equivalente en BPMN: es organización del código; sigue la convención {@code processRef} de
 * los atributos BPMN.
 * <p>
 * Invariantes: el proceso referenciado debe estar declarado con {@link Process}; una clase no puede llevar a la vez
 * esta anotación y {@link Process}; si el proceso tiene varias versiones, hay que indicar a cuál contribuye.
 *
 * @see Process
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProcessPart {

	/**
	 * Identificador del {@link Process} al que contribuye la clase.
	 *
	 * @return el identificador del proceso
	 */
	String processRef();

	/**
	 * Versión del proceso a la que contribuye la clase. El valor por defecto, {@code 0}, solo es válido cuando el proceso
	 * tiene una única versión.
	 *
	 * @return la versión, o {@code 0} si el proceso tiene una sola
	 */
	int version() default 0;
}
