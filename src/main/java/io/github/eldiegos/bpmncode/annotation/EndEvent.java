package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que concluye una instancia de proceso (evento de fin simple BPMN, {@code bpmn:endEvent}).
 * <p>
 * Responsabilidad: declarar cada desenlace posible del proceso que no publica nada; para terminar publicando un mensaje
 * se usa {@link MessageEndEvent} y para terminar con un error, {@link ErrorEndEvent}.
 * <p>
 * Invariantes: un proceso tiene al menos un evento de fin (de cualquiera de los tres tipos); un evento de fin no tiene
 * flujos de salida.
 *
 * @see StartEvent
 * @see MessageEndEvent
 * @see ErrorEndEvent
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EndEvent {

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
	 * Texto de negocio del evento (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
