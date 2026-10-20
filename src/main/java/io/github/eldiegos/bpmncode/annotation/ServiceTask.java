package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca el método que implementa una tarea de servicio BPMN ({@code bpmn:serviceTask}): trabajo automático del sistema.
 * <p>
 * Responsabilidad: declarar la tarea, las reglas de negocio que aplica y su flujo de salida.
 * <p>
 * Invariantes: tiene exactamente un flujo de salida, sin {@code when} ni condición; las reglas referenciadas existen.
 *
 * @see UserTask
 * @see BusinessRuleTask
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ServiceTask {

	/**
	 * Identificador estable de la tarea (atributo {@code id}).
	 *
	 * @return un NCName XML único en el proceso
	 */
	String id();

	/**
	 * Nombre canónico de la tarea (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Flujo de secuencia que sale de la tarea (elementos {@code bpmn:outgoing}).
	 *
	 * @return exactamente un flujo
	 */
	SequenceFlow[] outgoing();

	/**
	 * {@link BusinessRule} que aplica la tarea: {@code R-PER-001} si la regla tiene una sola versión, o
	 * {@code R-PER-001@2} para elegir una versión (obligatorio si hay varias). Se exporta como {@code pac:businessRuleRef}.
	 *
	 * @return las referencias, posiblemente ninguna
	 */
	String[] businessRuleRefs() default {};

	/**
	 * Bucle estándar de la tarea ({@code bpmn:standardLoopCharacteristics}): como mucho uno. Por defecto, ninguno.
	 *
	 * @return cero o un bucle
	 */
	StandardLoop[] loop() default {};

	/**
	 * Texto de negocio de la tarea (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
