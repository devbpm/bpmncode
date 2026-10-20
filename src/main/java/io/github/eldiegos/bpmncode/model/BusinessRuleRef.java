package io.github.eldiegos.bpmncode.model;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Identidad de una versión concreta de una regla de negocio: identificador estable más número de versión.
 * <p>
 * Responsabilidad: distinguir versiones de la misma regla que conviven (por ejemplo, la edad mínima de 16 años que
 * sigue aplicando la versión 1 del alta y la de 18 que aplica la versión 2) y dejar en la evidencia exactamente qué
 * versión se aplicó.
 * <p>
 * Invariantes: el identificador no está vacío y la versión es mayor que cero; se ordena por identificador y después
 * por versión; la versión de una regla es independiente de la de los procesos que la usan.
 *
 * @param id identificador estable de la regla, común a todas sus versiones
 * @param version número de versión, mayor que cero
 * @see BusinessRuleDefinition#ref()
 */
public record BusinessRuleRef(String id, int version) implements Comparable<BusinessRuleRef> {

	/** Separador de la forma de referencia {@code R-PER-001@2}, la que usan las anotaciones y la evidencia. */
	public static final String SEPARATOR = "@";

	private static final Comparator<BusinessRuleRef> ORDER = Comparator.comparing(BusinessRuleRef::id).thenComparingInt(BusinessRuleRef::version);

	public BusinessRuleRef {
		Objects.requireNonNull(id, "id");
		if (id.isBlank()) {
			throw new IllegalArgumentException("A business rule reference needs a rule id");
		}
		if (version < 1) {
			throw new IllegalArgumentException("Rule " + id + " must have a version >= 1");
		}
	}

	/**
	 * Clave de una versión de una regla.
	 *
	 * @param id identificador de la regla
	 * @param version número de versión
	 * @return la clave
	 */
	public static BusinessRuleRef of(String id, int version) {
		return new BusinessRuleRef(id, version);
	}

	/** Forma de referencia, p. ej. {@code R-PER-001@2}: la que se escribe en {@code businessRuleRefs = {…}} y en la evidencia. */
	public String reference() {
		return id + SEPARATOR + version;
	}

	/**
	 * Une varias referencias en su forma de referencia, sin repetir y en el orden dado, p. ej. para el detalle de una
	 * evidencia.
	 *
	 * @param rules reglas y versiones
	 * @return p. ej. {@code R-PER-002@1, R-PER-001@2}; vacío si no hay ninguna
	 */
	public static String join(Collection<BusinessRuleRef> rules) {
		return rules.stream().map(BusinessRuleRef::reference).distinct().collect(Collectors.joining(", "));
	}

	@Override
	public int compareTo(BusinessRuleRef other) {
		return ORDER.compare(this, other);
	}

	/** Forma legible, p. ej. {@code R-PER-001 v2}. */
	@Override
	public String toString() {
		return id + " v" + version;
	}
}
