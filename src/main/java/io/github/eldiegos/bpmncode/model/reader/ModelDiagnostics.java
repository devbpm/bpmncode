package io.github.eldiegos.bpmncode.model.reader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.eldiegos.bpmncode.model.ModelViolation;
import io.github.eldiegos.bpmncode.model.ViolationKind;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessModelException;

/**
 * Colector de los problemas encontrados al leer el modelo.
 * <p>
 * Responsabilidad: acumular violaciones y comprobar las reglas de identidad comunes a todos los elementos (identificador
 * presente, NCName válido, nombre legible, identificador BPMN único).
 * <p>
 * Invariantes: nunca descarta una violación; un identificador BPMN registrado no se repite, salvo en otra versión del
 * mismo proceso.
 *
 * @see ModelViolation
 */
final class ModelDiagnostics {

	/** Los identificadores se convierten en ids de elementos BPMN, así que deben ser NCNames XML válidos. */
	private static final Pattern NCNAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

	private final List<ModelViolation> violations = new ArrayList<>();
	private final Map<String, Claim> bpmnIds = new HashMap<>();

	/**
	 * Registra un problema.
	 *
	 * @param kind clase de problema
	 * @param location dónde se encontró
	 * @param message explicación técnica
	 */
	void report(ViolationKind kind, String location, String message) {
		violations.add(new ModelViolation(kind, location, message));
	}

	/**
	 * Comprueba que un identificador está presente y es un NCName.
	 *
	 * @param id identificador
	 * @param element tipo de elemento, para el mensaje
	 * @param location dónde se declara
	 * @return {@code true} si es válido
	 */
	boolean isValidId(String id, String element, String location) {
		if (id == null || id.isBlank()) {
			report(ViolationKind.UNIDENTIFIED_ELEMENT, location, element + " without identifier");
			return false;
		}
		if (!NCNAME.matcher(id).matches()) {
			report(ViolationKind.INVALID_ID, location, "identifier '" + id
					+ "' is invalid: it must start with a letter or '_' and contain only letters, digits, '-', '_' or '.'");
			return false;
		}
		return true;
	}

	/**
	 * Comprueba que hay un nombre legible.
	 *
	 * @param name nombre
	 * @param location dónde se declara
	 * @return {@code true} si el nombre está presente
	 */
	boolean hasName(String name, String location) {
		if (name == null || name.isBlank()) {
			report(ViolationKind.UNIDENTIFIED_ELEMENT, location, "the readable name is missing");
			return false;
		}
		return true;
	}

	/**
	 * Nombre de un tipo tal como lo escribe el código ({@code Outer.Inner}), el mismo que usa el procesador de
	 * anotaciones al compilar; el binario ({@code Outer$Inner}) solo si el tipo no tiene nombre canónico.
	 *
	 * @param type tipo referenciado
	 * @return su nombre canónico o, si no lo tiene, el binario
	 */
	static String typeName(Class<?> type) {
		var canonical = type.getCanonicalName();
		return canonical != null ? canonical : type.getName();
	}

	/**
	 * Reserva un identificador BPMN global (reglas y eventos), que no puede repetirse en ningún otro elemento.
	 *
	 * @param id identificador
	 * @param location dónde se declara
	 * @return {@code true} si no estaba ya reservado
	 */
	boolean claimBpmnId(String id, String location) {
		return claim(id, new Claim(Optional.empty(), location));
	}

	/**
	 * Reserva un identificador BPMN que pertenece a una versión de un proceso (el propio proceso, sus nodos, sus
	 * flujos de secuencia y los flujos de mensaje que salen de él).
	 * <p>
	 * El mismo identificador puede repetirse en otra versión del mismo proceso: cada vista BPMN muestra una sola versión
	 * de cada proceso, así que dentro de un documento sigue siendo único.
	 *
	 * @param id identificador
	 * @param owner versión del proceso que lo declara
	 * @param location dónde se declara
	 * @return {@code true} si no estaba ya reservado por otro proceso, por la misma versión o por un elemento global
	 */
	boolean claimBpmnId(String id, ProcessDefinitionRef owner, String location) {
		return claim(id, new Claim(Optional.of(owner), location));
	}

	private boolean claim(String id, Claim claim) {
		var existing = bpmnIds.get(id);
		if (existing == null) {
			bpmnIds.put(id, claim);
			return true;
		}
		var otherVersionOfSameProcess = existing.owner()
				.flatMap(owner -> claim.owner().filter(other -> owner.processId().equals(other.processId())
						&& owner.version() != other.version()))
				.isPresent();
		if (!otherVersionOfSameProcess) {
			report(ViolationKind.DUPLICATE_ID, claim.location(),
					"identifier " + id + " is already declared at " + existing.location());
			return false;
		}
		return true;
	}

	/**
	 * Reserva de un identificador.
	 * <p>
	 * Responsabilidad: recordar quién declaró un identificador y dónde.
	 * <p>
	 * Invariantes: sin propietario, el identificador es global (regla o evento).
	 *
	 * @param owner versión del proceso que lo declara, si pertenece a un proceso
	 * @param location dónde se declaró
	 * @see ModelDiagnostics#claimBpmnId(String, ProcessDefinitionRef, String)
	 */
	private record Claim(Optional<ProcessDefinitionRef> owner, String location) {
	}

	/** Número de problemas registrados hasta ahora. */
	int count() {
		return violations.size();
	}

	/**
	 * Termina la lectura si hubo problemas.
	 *
	 * @throws ProcessModelException con todos los problemas, si hay alguno
	 */
	void throwIfAny() {
		if (!violations.isEmpty()) {
			throw new ProcessModelException(violations);
		}
	}
}
