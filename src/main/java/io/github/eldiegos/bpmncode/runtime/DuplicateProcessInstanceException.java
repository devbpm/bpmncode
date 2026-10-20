package io.github.eldiegos.bpmncode.runtime;

/**
 * Se intentó iniciar una segunda instancia de un proceso con la misma correlación.
 * <p>
 * Responsabilidad: señalar que se ha roto la regla de una instancia por proceso y correlación, que hace idempotentes los
 * inicios por mensaje.
 * <p>
 * Invariantes: es una {@link ProcessConformanceException}, así que deshace la transacción de quien llama, si la hay.
 *
 * @see ProcessInstanceStore#insert(ProcessInstanceRecord)
 */
public class DuplicateProcessInstanceException extends ProcessConformanceException {

	/**
	 * Crea la excepción.
	 *
	 * @param processId proceso
	 * @param correlationKey correlación repetida
	 */
	public DuplicateProcessInstanceException(String processId, CorrelationKey correlationKey) {
		super("There is already an instance of " + processId + " with correlation " + correlationKey);
	}
}
