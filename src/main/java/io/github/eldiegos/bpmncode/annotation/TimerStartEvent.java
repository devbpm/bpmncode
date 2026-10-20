package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que inicia una instancia cada vez que vence un temporizador (evento de inicio de temporizador BPMN:
 * {@code bpmn:startEvent} con {@code bpmn:timerEventDefinition}).
 * <p>
 * Responsabilidad: declarar el punto de entrada del proceso; el temporizador no se repite aquí: es el
 * {@code @Scheduled} de Spring del mismo método, que el framework lee ({@code cron}, {@code fixedRate},
 * {@code fixedDelay}, {@code initialDelay}, {@code zone}, con sus {@code ${…}} resueltos) y exporta como
 * {@code bpmn:timeCycle}. Así lo que se dibuja es exactamente lo que programa Spring.
 * <p>
 * Invariantes: un proceso tiene exactamente un evento de inicio con exactamente un flujo de salida; el método lleva
 * exactamente un {@code @Scheduled} activo (no {@code cron = "-"}) y, como exige Spring, no tiene parámetros.
 *
 * @see StartEvent
 * @see MessageStartEvent
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TimerStartEvent {

	/**
	 * Identificador estable del evento (atributo {@code id}).
	 *
	 * @return un NCName XML único en el proceso
	 */
	String id();

	/**
	 * Nombre canónico del evento (atributo {@code name}), p. ej. {@code Every morning}.
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
