package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Propietario potencial de una tarea humana ({@code bpmn:potentialOwner}): quienes pueden reclamarla y completarla. Se
 * declara dentro de {@link UserTask#potentialOwners()}.
 * <p>
 * Responsabilidad: decir quién, por un recurso declarado ({@code resourceRef}) o por una expresión que resuelve la
 * aplicación ({@code expression}).
 * <p>
 * Invariantes: declara exactamente uno de {@code resourceRef} o {@code expression}; el tipo de {@code resourceRef} está
 * anotado con {@link ProcessResource}. La librería no asigna la tarea ni comprueba quién la completa.
 *
 * @see HumanPerformer
 * @see ProcessResource
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface PotentialOwner {

	/**
	 * Recurso que cumple el rol (elemento {@code bpmn:resourceRef}). Por defecto, {@code void.class}: se usa
	 * {@link #expression()}.
	 *
	 * @return el tipo anotado con {@link ProcessResource}, o {@code void.class}
	 */
	Class<?> resourceRef() default void.class;

	/**
	 * Expresión que identifica a quién corresponde el rol (elemento {@code bpmn:resourceAssignmentExpression} con un
	 * {@code bpmn:formalExpression}), p. ej. {@code manager of the employee}. Por defecto, vacía: se usa
	 * {@link #resourceRef()}.
	 *
	 * @return la expresión, o vacía
	 */
	String expression() default "";
}
