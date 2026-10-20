package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que concluye una instancia publicando un mensaje (evento de fin de mensaje BPMN: {@code bpmn:endEvent}
 * con {@code bpmn:messageEventDefinition}).
 * <p>
 * Responsabilidad: declarar un desenlace del proceso y el mensaje que publica al alcanzarlo; el tipo Java del mensaje
 * es el contrato y dibuja el flujo de mensaje hacia los procesos que inicia.
 * <p>
 * Invariantes: no tiene flujos de salida; {@code messageRef} es una versión concreta de un {@link Message} (un record
 * con {@link MessageVersion}, o el record de un mensaje de una sola versión).
 *
 * @see EndEvent
 * @see MessageStartEvent
 * @see SendTask
 * @see Message
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MessageEndEvent {

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
	 * Tipo Java del mensaje que publica el proceso al terminar aquí (atributo {@code messageRef} de su
	 * {@code bpmn:messageEventDefinition}): una versión concreta.
	 *
	 * @return el record de la versión publicada
	 */
	Class<?> messageRef();

	/**
	 * Texto de negocio del evento (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
