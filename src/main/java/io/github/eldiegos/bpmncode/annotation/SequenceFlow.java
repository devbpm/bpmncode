package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara un flujo de secuencia BPMN ({@code bpmn:sequenceFlow}) que sale del nodo anotado.
 * <p>
 * Responsabilidad: unir el nodo con su destino ({@code targetRef}) y, si sale de un {@link ExclusiveGateway}, ligar la
 * rama con la constante del enum que devuelve el método del gateway ({@code when}) y con su condición legible
 * ({@code conditionExpression}).
 * <p>
 * Invariantes: solo se usa dentro de {@code outgoing}; {@code targetRef} es un nodo del mismo proceso; {@code when} y
 * {@code conditionExpression} solo se admiten en las salidas de un gateway, donde {@code when} es obligatorio.
 *
 * @see ExclusiveGateway
 * @see StartEvent
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface SequenceFlow {

	/**
	 * Identificador del nodo destino (atributo {@code targetRef}).
	 *
	 * @return el identificador del nodo destino
	 */
	String targetRef();

	/**
	 * Identificador estable opcional del flujo (atributo {@code id}). Por defecto {@code Flow_<origen>_<destino>}.
	 *
	 * @return el identificador, o vacío para el de por defecto
	 */
	String id() default "";

	/**
	 * Etiqueta corta que se muestra en el diagrama (atributo {@code name}). En un gateway, por defecto, {@link #when()}.
	 *
	 * @return la etiqueta, posiblemente vacía
	 */
	String name() default "";

	/**
	 * Condición legible de la rama (elemento {@code bpmn:conditionExpression}). En un gateway, por defecto,
	 * {@link #when()}.
	 *
	 * @return la condición, posiblemente vacía
	 */
	String conditionExpression() default "";

	/**
	 * Nombre de la constante del enum, devuelta por el método del gateway, que selecciona esta rama. No es un concepto
	 * BPMN: es el enlace con el código y se exporta como {@code pac:when}.
	 *
	 * @return la constante, o vacío si el flujo no sale de un gateway
	 */
	String when() default "";
}
