package io.github.eldiegos.bpmncode.runtime;

/**
 * Estado guardado del ciclo de vida de una instancia.
 * <p>
 * Responsabilidad: discriminar en el almacén entre instancias en curso y completadas; a las vistas se expone como
 * {@link ProcessInstanceSummary}.
 * <p>
 * Invariantes: el resultado de negocio no es un estado: es el evento de fin alcanzado.
 *
 * @see ProcessInstanceRecord
 */
public enum ProcessInstanceState {
	ACTIVE,
	COMPLETED
}
