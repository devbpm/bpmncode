package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que inicia una instancia a petición de quien lo llama (evento de inicio simple BPMN,
 * {@code bpmn:startEvent} sin definición de evento).
 * <p>
 * Responsabilidad: declarar el punto de entrada del proceso y su flujo de salida cuando lo inicia una llamada directa
 * (una pantalla, una API); si lo inicia un mensaje se usa {@link MessageStartEvent} y si lo inicia un temporizador,
 * {@link TimerStartEvent}.
 * <p>
 * Invariantes: un proceso tiene exactamente un evento de inicio (de cualquiera de los tres tipos) con exactamente un
 * flujo de salida.
 *
 * @see MessageStartEvent
 * @see TimerStartEvent
 * @see EndEvent
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface StartEvent {

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
