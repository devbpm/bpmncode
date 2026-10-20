package io.github.eldiegos.bpmncode.runtime;

import java.util.UUID;

/**
 * Dos avances concurrentes de la misma instancia: el segundo se basó en un estado que ya no es el guardado.
 * <p>
 * Responsabilidad: hacer visible el fallo del bloqueo optimista, para que quien llama vuelva a leer la instancia o
 * informe al usuario.
 * <p>
 * Invariantes: el almacén no ha guardado nada del avance rechazado.
 *
 * @see ProcessInstanceStore#update(ProcessInstanceRecord)
 */
public class ProcessInstanceConflictException extends RuntimeException {

	/**
	 * Crea la excepción.
	 *
	 * @param instanceId instancia
	 * @param version versión que se leyó
	 */
	public ProcessInstanceConflictException(UUID instanceId, long version) {
		super("Process instance " + instanceId + " changed after version " + version + " was read, or no longer exists");
	}
}
