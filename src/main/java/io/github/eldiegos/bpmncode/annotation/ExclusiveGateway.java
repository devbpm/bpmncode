package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que elige entre varias ramas (gateway exclusivo BPMN, {@code bpmn:exclusiveGateway}).
 * <p>
 * Responsabilidad: declarar la pregunta del gateway y sus flujos de salida, cada uno ligado con {@code when} a una
 * constante del enum que devuelve el método.
 * <p>
 * Invariantes: el método devuelve un enum; hay al menos dos flujos de salida; cada constante tiene exactamente un flujo
 * y no hay flujos sin constante, de modo que el diagrama y el código que elige no pueden divergir.
 *
 * @see SequenceFlow
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ExclusiveGateway {

	/**
	 * Identificador estable del gateway (atributo {@code id}).
	 *
	 * @return un NCName XML único en el proceso
	 */
	String id();

	/**
	 * La pregunta que responde el gateway (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Flujos de secuencia que salen del gateway, uno por constante del enum (elementos {@code bpmn:outgoing}).
	 *
	 * @return al menos dos flujos, todos con {@code when}
	 */
	SequenceFlow[] outgoing();

	/**
	 * {@link BusinessRule} en las que se basa la decisión: {@code R-PER-001} si la regla tiene una sola versión, o
	 * {@code R-PER-001@2} para elegir una versión (obligatorio si hay varias). Se exporta como {@code pac:businessRuleRef}.
	 *
	 * @return las referencias, posiblemente ninguna
	 */
	String[] businessRuleRefs() default {};

	/**
	 * Texto de negocio del gateway (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
