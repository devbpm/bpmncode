package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara un proceso BPMN ({@code bpmn:process}) en una versión concreta.
 * <p>
 * Responsabilidad: dar identidad, nombre canónico y versión al proceso cuyos nodos son métodos anotados de esta clase y
 * de las clases {@link ProcessPart} que le contribuyen.
 * <p>
 * Invariantes: exactamente una clase por identificador y versión; el identificador es un NCName XML y la versión es
 * mayor que cero. Al importar esta anotación se oculta {@link java.lang.Process} en ese fichero.
 *
 * @see ProcessPart
 * @see StartEvent
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Process {

	/**
	 * Identificador estable del proceso, común a todas sus versiones (atributo {@code id} de {@code bpmn:process}), p.
	 * ej. {@code PER-REGISTRATION}.
	 *
	 * @return un NCName XML
	 */
	String id();

	/**
	 * Nombre canónico del proceso (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Versión de la definición. No es un concepto BPMN: se exporta como {@code pac:version}. Se incrementa cuando cambia
	 * el flujo declarado o su contrato de inicio.
	 *
	 * @return la versión, mayor que cero
	 */
	int version();

	/**
	 * Texto de negocio del proceso (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
