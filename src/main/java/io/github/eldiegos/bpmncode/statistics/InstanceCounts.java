package io.github.eldiegos.bpmncode.statistics;

/**
 * Instancias de una versión de un proceso según su estado.
 * <p>
 * Responsabilidad: contar las instancias activas y completadas, p. ej. para un catálogo de procesos.
 * <p>
 * Invariantes: ninguna cifra es negativa.
 *
 * @param running instancias activas
 * @param completed instancias completadas
 * @see ProcessExecutionStatistics#countsByVersion()
 */
public record InstanceCounts(long running, long completed) {

	/** Sin instancias. */
	public static final InstanceCounts NONE = new InstanceCounts(0, 0);

	public InstanceCounts {
		if (running < 0 || completed < 0) {
			throw new IllegalArgumentException("Instance counts are never negative");
		}
	}

	/** Total de instancias. */
	public long total() {
		return running + completed;
	}
}
