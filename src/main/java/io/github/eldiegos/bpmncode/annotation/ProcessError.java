package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara un error BPMN ({@code bpmn:error}): el desenlace fallido de negocio con el que puede terminar un proceso.
 * <p>
 * Responsabilidad: dar identidad estable y un código de error a una excepción de la aplicación, para que un
 * {@link ErrorEndEvent} la referencie como contrato y la exportación BPMN la describa.
 * <p>
 * Invariantes: se anota una clase que extiende {@link Throwable}; cada identificador de error aparece una sola vez y su
 * código ({@code errorCode}) nunca está vacío.
 *
 * @see ErrorEndEvent#errorRef()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProcessError {

	/**
	 * Identificador estable del error (atributo {@code id}), p. ej. {@code ERR-CREDIT-REJECTED}.
	 *
	 * @return un NCName XML único en la aplicación
	 */
	String id();

	/**
	 * Nombre canónico del error (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Código del error (atributo {@code errorCode}): el valor estable con el que lo identifican otros sistemas.
	 *
	 * @return el código, p. ej. {@code CREDIT_REJECTED}
	 */
	String errorCode();

	/**
	 * Texto de negocio del error (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
