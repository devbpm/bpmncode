package io.github.eldiegos.bpmncode.runtime;

/**
 * Error que indica que el código intentó seguir un flujo que su propia definición anotada no declara.
 * <p>
 * Responsabilidad: detener la ejecución cuando el código se desvía del modelo; es un error de programación, nunca un
 * resultado de negocio, y la transacción que lo rodea se deshace.
 * <p>
 * Invariantes: el mensaje es técnico (en inglés) y nombra el flujo no declarado.
 *
 * @see ProcessJournal
 */
public class ProcessConformanceException extends IllegalStateException {

	/**
	 * Crea la excepción.
	 *
	 * @param message explicación técnica
	 */
	public ProcessConformanceException(String message) {
		super(message);
	}
}
