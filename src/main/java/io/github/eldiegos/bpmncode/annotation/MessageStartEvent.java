package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que inicia una instancia al recibir un mensaje (evento de inicio de mensaje BPMN:
 * {@code bpmn:startEvent} con {@code bpmn:messageEventDefinition}).
 * <p>
 * Responsabilidad: declarar el punto de entrada del proceso y el mensaje que lo dispara; la suscripción de Spring y el
 * flujo de mensaje BPMN son la misma declaración, y el tipo Java del mensaje es el contrato de inicio.
 * <p>
 * Invariantes: un proceso tiene exactamente un evento de inicio con exactamente un flujo de salida; el tipo de
 * {@code messageRef} está anotado con {@link Message}; el método es un listener de eventos de Spring (p. ej.
 * {@code @ApplicationModuleListener}) que recibe ese tipo; algún proceso publica el mensaje.
 *
 * @see StartEvent
 * @see TimerStartEvent
 * @see Message
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MessageStartEvent {

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
	 * Tipo Java del mensaje que dispara el proceso (atributo {@code messageRef} de su
	 * {@code bpmn:messageEventDefinition}): el contrato de inicio.
	 *
	 * @return el tipo anotado con {@link Message}
	 */
	Class<?> messageRef();

	/**
	 * Flujo de secuencia que sale del evento (elementos {@code bpmn:outgoing}).
	 *
	 * @return exactamente un flujo
	 */
	SequenceFlow[] outgoing();

	/**
	 * Texto de negocio del evento (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
