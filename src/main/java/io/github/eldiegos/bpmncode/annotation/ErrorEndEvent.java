package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que concluye una instancia con un error de negocio (evento de fin de error BPMN: {@code bpmn:endEvent}
 * con {@code bpmn:errorEventDefinition}).
 * <p>
 * Responsabilidad: declarar un desenlace fallido del proceso y el error que lo identifica; el tipo Java del error es el
 * contrato, igual que el de un mensaje.
 * <p>
 * Invariantes: no tiene flujos de salida; el tipo de {@code errorRef} es una excepción anotada con
 * {@link ProcessError}. La instancia termina en este evento: el error es su resultado de negocio, no un fallo técnico.
 *
 * @see EndEvent
 * @see ProcessError
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ErrorEndEvent {

	/**
	 * Identificador estable del evento (atributo {@code id}).
	 *
	 * @return un NCName XML único en el proceso
	 */
	String id();

	/**
	 * Nombre canónico del evento (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Tipo Java del error con el que termina el proceso aquí (atributo {@code errorRef} de su
	 * {@code bpmn:errorEventDefinition}).
	 *
	 * @return la excepción anotada con {@link ProcessError}
	 */
	Class<? extends Throwable> errorRef();

	/**
	 * Texto de negocio del evento (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
