package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Identifica el código que implementa una regla de negocio.
 * <p>
 * Responsabilidad: dar a la regla un identificador estable que referencian las {@link BusinessRuleTask}, el resto de
 * tareas y los {@link ExclusiveGateway}, y que la trazabilidad lleva hasta este tipo o método. BPMN no modela las
 * reglas: la referencia se exporta como extensión {@code pac:businessRuleRef}.
 * <p>
 * Invariantes: cada par identificador y versión aparece una sola vez en la aplicación; varias versiones de la misma
 * regla pueden convivir (cada una en su tipo o método) y los nodos que la usan indican cuál ({@code R-PER-001@2}).
 *
 * @see BusinessRuleTask#businessRuleRefs()
 * @see ExclusiveGateway#businessRuleRefs()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface BusinessRule {

	/**
	 * Identificador estable de la regla, común a todas sus versiones, p. ej. {@code R-PER-001}.
	 *
	 * @return un NCName XML
	 */
	String id();

	/**
	 * Nombre canónico de la regla.
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Texto de negocio de la regla.
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";

	/**
	 * Versión de la regla, independiente de la de los procesos que la usan. Se sube cuando cambia su comportamiento (p. ej.
	 * el umbral) sin cambiar su significado; si cambia el significado, es otra regla con otro identificador.
	 *
	 * @return la versión, mayor que cero
	 */
	int version() default 1;
}
