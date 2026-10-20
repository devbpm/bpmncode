package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara que una tarea se repite (bucle estándar BPMN, {@code bpmn:standardLoopCharacteristics}, marcador ↻).
 * <p>
 * Responsabilidad: indicar que el método de la tarea puede completarse varias veces seguidas mientras se cumpla
 * {@link #loopCondition()}, hasta {@link #loopMaximum()} iteraciones; el journal cuenta cada iteración
 * ({@code loopCounter}) y rechaza la que supere el máximo.
 * <p>
 * Invariantes: solo se usa en {@code loop} de una tarea; la condición es obligatoria; el máximo es cero (sin límite)
 * o positivo. La condición se evalúa al final de cada iteración ({@code testBefore="false"}): la tarea se ejecuta al
 * menos una vez. Para repetir con la condición antes, o repetir varios nodos, se modela un ciclo con un
 * {@link ExclusiveGateway} y un flujo de vuelta.
 *
 * @see ServiceTask#loop()
 * @see UserTask#loop()
 * @see BusinessRuleTask#loop()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface StandardLoop {

	/**
	 * Condición legible para repetir la tarea (elemento {@code bpmn:loopCondition}).
	 *
	 * @return la condición, en inglés
	 */
	String loopCondition();

	/**
	 * Número máximo de iteraciones seguidas (atributo {@code loopMaximum}). Cero significa sin límite.
	 *
	 * @return el máximo, cero o positivo
	 */
	int loopMaximum() default 0;
}
