package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara un recurso BPMN ({@code bpmn:resource}): el rol de negocio que realiza o puede reclamar una tarea humana, p.
 * ej. «Responsable de gastos».
 * <p>
 * Responsabilidad: dar identidad estable a un rol para que {@link PotentialOwner} y {@link HumanPerformer} lo
 * referencien por su tipo Java, igual que {@code messageRef} referencia un {@link Message}. Si el tipo es un record,
 * cada componente es un parámetro del recurso ({@code bpmn:resourceParameter}), p. ej. el departamento del
 * responsable.
 * <p>
 * Invariantes: cada identificador de recurso aparece una sola vez. La librería no asigna tareas: decidir qué persona
 * concreta cumple el rol es cosa de la aplicación.
 *
 * @see PotentialOwner#resourceRef()
 * @see HumanPerformer#resourceRef()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProcessResource {

	/**
	 * Identificador estable del recurso (atributo {@code id}), p. ej. {@code RES-EXPENSE-APPROVER}.
	 *
	 * @return un NCName XML único en la aplicación
	 */
	String id();

	/**
	 * Nombre canónico del recurso (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Texto de negocio del recurso (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
