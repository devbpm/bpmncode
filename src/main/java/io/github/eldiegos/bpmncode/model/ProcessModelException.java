package io.github.eldiegos.bpmncode.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Error que indica que las definiciones de proceso anotadas están incompletas o son incoherentes.
 * <p>
 * Responsabilidad: transportar todos los diagnósticos juntos; a partir de un modelo inválido no se produce registro
 * (ni, por tanto, BPMN) y la aplicación no arranca.
 * <p>
 * Invariantes: siempre contiene al menos una violación.
 *
 * @see ModelViolation
 */
public class ProcessModelException extends RuntimeException {

	private final List<ModelViolation> violations;

	/**
	 * Crea la excepción con todos los problemas encontrados.
	 *
	 * @param violations problemas, al menos uno
	 * @throws IllegalArgumentException si la lista está vacía
	 */
	public ProcessModelException(List<ModelViolation> violations) {
		super(describe(violations));
		if (violations.isEmpty()) {
			throw new IllegalArgumentException("A process model exception needs at least one violation");
		}
		this.violations = List.copyOf(violations);
	}

	/**
	 * Devuelve los problemas encontrados.
	 *
	 * @return las violaciones, en el orden en que se encontraron
	 */
	public List<ModelViolation> violations() {
		return violations;
	}

	private static String describe(List<ModelViolation> violations) {
		return violations.stream()
				.map(violation -> "  - " + violation)
				.collect(Collectors.joining(System.lineSeparator(),
						"Invalid process model (" + violations.size() + " problem(s)):" + System.lineSeparator(), ""));
	}
}
