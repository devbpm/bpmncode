package io.github.eldiegos.bpmncode.runtime;

import java.util.UUID;

/**
 * Dos avances concurrentes de la misma instancia: el segundo se basó en un estado que ya no es el guardado.
 * <p>
 * Responsabilidad: hacer visible el fallo del bloqueo optimista, para que quien llama vuelva a leer la instancia o
 * informe al usuario.
 * <p>
 * Invariantes: el almacén no ha guardado nada de la escritura rechazada: ni la instancia de un avance basado en un
 * estado viejo, ni un paso del historial cuya posición ya está ocupada.
 *
 * @see ProcessInstanceStore#update(ProcessInstanceRecord)
 * @see ProcessInstanceStore#append(FlowNodeInstanceRecord)
 */
public class ProcessInstanceConflictException extends RuntimeException {

	/**
	 * Crea la excepción.
	 *
	 * @param instanceId instancia
	 * @param version versión que se leyó
	 */
	public ProcessInstanceConflictException(UUID instanceId, long version) {
		this("Process instance " + instanceId + " changed after version " + version + " was read, or no longer exists");
	}

	private ProcessInstanceConflictException(String message) {
		super(message);
	}

	/**
	 * Crea la excepción para un paso del historial cuya posición ya está ocupada: otro paso de la misma instancia se
	 * registró a la vez (sin una transacción que los serialice).
	 *
	 * @param instanceId instancia
	 * @param sequence posición repetida
	 * @return la excepción
	 * @see ProcessInstanceStore#append(FlowNodeInstanceRecord)
	 */
	public static ProcessInstanceConflictException sequenceTaken(UUID instanceId, int sequence) {
		return new ProcessInstanceConflictException("Flow node instance " + sequence + " of process instance " + instanceId
				+ " is already recorded: another step of the same instance was recorded concurrently");
	}
}
