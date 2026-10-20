package io.github.eldiegos.bpmncode.model;

import java.util.OptionalInt;

/**
 * Bucle estándar BPMN ({@code bpmn:standardLoopCharacteristics}) de una tarea.
 * <p>
 * Responsabilidad: indicar que la tarea se repite mientras se cumple su condición, como mucho {@code loopMaximum}
 * veces seguidas; la condición se evalúa tras cada iteración ({@code testBefore="false"}).
 * <p>
 * Invariantes: la condición nunca está vacía; el máximo es cero (sin límite) o positivo.
 *
 * @param loopCondition condición legible para repetir (elemento {@code bpmn:loopCondition})
 * @param loopMaximum máximo de iteraciones seguidas; cero, sin límite (atributo {@code loopMaximum})
 * @see FlowNode.Task#loopCharacteristics()
 */
public record StandardLoopCharacteristics(String loopCondition, int loopMaximum) implements LoopCharacteristics {

	public StandardLoopCharacteristics {
		if (loopCondition == null || loopCondition.isBlank()) {
			throw new IllegalArgumentException("A standard loop needs its loopCondition");
		}
		if (loopMaximum < 0) {
			throw new IllegalArgumentException("loopMaximum must be zero (unlimited) or positive");
		}
	}

	/**
	 * Máximo de iteraciones, si lo hay.
	 *
	 * @return el máximo, o vacío si el bucle no tiene límite
	 */
	public OptionalInt maximum() {
		return loopMaximum == 0 ? OptionalInt.empty() : OptionalInt.of(loopMaximum);
	}

	/**
	 * Indica si se admite una iteración más.
	 *
	 * @param completedIterations iteraciones seguidas ya completadas
	 * @return {@code true} si no hay máximo o aún no se ha alcanzado
	 */
	public boolean allowsAnotherIteration(int completedIterations) {
		return loopMaximum == 0 || completedIterations < loopMaximum;
	}
}
