package io.github.eldiegos.bpmncode.runtime;

import java.util.Objects;
import java.util.UUID;

/**
 * Referencia a una instancia de proceso en curso.
 * <p>
 * Responsabilidad: pasar entre los nodos de un proceso la identidad de la instancia que se está ejecutando.
 * <p>
 * Invariantes: todos sus campos tienen valor; solo la crea {@link ProcessJournal}.
 *
 * @param instanceId identificador de la instancia
 * @param processId proceso que ejecuta
 * @param correlationKey correlación de negocio
 * @see ProcessJournal
 */
public record ProcessInstanceRef(UUID instanceId, String processId, CorrelationKey correlationKey) {

	public ProcessInstanceRef {
		Objects.requireNonNull(instanceId, "instanceId");
		Objects.requireNonNull(processId, "processId");
		Objects.requireNonNull(correlationKey, "correlationKey");
	}
}
