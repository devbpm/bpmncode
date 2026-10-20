package io.github.eldiegos.bpmncode.model;

import java.util.Comparator;
import java.util.Objects;

/**
 * Identidad de una versión concreta de un proceso: identificador estable más número de versión.
 * <p>
 * Responsabilidad: distinguir versiones del mismo proceso que conviven (por ejemplo, la versión 1 sigue atendiendo a
 * clientes antiguos mientras la 2 exige datos de inicio nuevos) y fijar a qué versión pertenece cada instancia.
 * <p>
 * Invariantes: el identificador no está vacío y la versión es mayor que cero; se ordena por identificador y después
 * por versión, de modo que la última versión de un proceso es la mayor.
 *
 * @param processId identificador estable del proceso, común a todas sus versiones
 * @param version número de versión, mayor que cero
 * @see ProcessDefinitionRegistry#processDefinition(ProcessDefinitionRef)
 */
public record ProcessDefinitionRef(String processId, int version) implements Comparable<ProcessDefinitionRef> {

	private static final Comparator<ProcessDefinitionRef> ORDER = Comparator.comparing(ProcessDefinitionRef::processId)
			.thenComparingInt(ProcessDefinitionRef::version);

	public ProcessDefinitionRef {
		Objects.requireNonNull(processId, "processId");
		if (processId.isBlank()) {
			throw new IllegalArgumentException("A process definition reference needs a process id");
		}
		if (version < 1) {
			throw new IllegalArgumentException("Process " + processId + " must have a version >= 1");
		}
	}

	/**
	 * Clave de una versión de un proceso.
	 *
	 * @param processId identificador del proceso
	 * @param version número de versión
	 * @return la clave
	 */
	public static ProcessDefinitionRef of(String processId, int version) {
		return new ProcessDefinitionRef(processId, version);
	}

	@Override
	public int compareTo(ProcessDefinitionRef other) {
		return ORDER.compare(this, other);
	}

	@Override
	public String toString() {
		return processId + " v" + version;
	}
}
