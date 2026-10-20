package io.github.eldiegos.bpmncode.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Mensaje BPMN ({@code bpmn:message}) descubierto a partir de {@link io.github.eldiegos.bpmncode.annotation.Message}.
 * <p>
 * Responsabilidad: ligar el identificador BPMN de un mensaje con el tipo Java de su familia de versiones, mediante su
 * {@link ItemDefinition}, y con cada una de sus versiones.
 * <p>
 * Invariantes: identificador y nombre nunca están vacíos; la documentación nunca es nula; hay al menos una versión, con
 * números distintos y ordenadas de menor a mayor.
 *
 * @param id identificador estable, p. ej. {@code EV-PERSON-REGISTERED}
 * @param name nombre canónico
 * @param documentation texto de negocio, posiblemente vacío
 * @param itemDefinition estructura del mensaje: la {@code sealed interface} de la familia (o el record, si solo hay una
 *        versión)
 * @param versions versiones del mensaje
 * @see MessageFlow
 */
public record Message(String id, String name, String documentation, ItemDefinition itemDefinition,
		List<MessageVersionDefinition> versions) {

	public Message {
		if (id == null || id.isBlank() || name == null || name.isBlank()) {
			throw new IllegalArgumentException("Message id and name are mandatory");
		}
		Objects.requireNonNull(itemDefinition, "itemDefinition");
		documentation = documentation == null ? "" : documentation;
		versions = versions.stream().sorted(Comparator.comparingInt(MessageVersionDefinition::version)).toList();
		if (versions.isEmpty() || versions.stream().map(MessageVersionDefinition::version).distinct().count() != versions.size()) {
			throw new IllegalArgumentException("Message " + id + " needs distinct versions");
		}
	}

	/**
	 * Busca una versión.
	 *
	 * @param version número de versión
	 * @return la versión, o vacío si el mensaje no la declara
	 */
	public Optional<MessageVersionDefinition> version(int version) {
		return versions.stream().filter(candidate -> candidate.version() == version).findFirst();
	}

	/**
	 * Busca la versión que transporta un tipo Java.
	 *
	 * @param type record de la versión
	 * @return la versión, o vacío si el tipo no es una versión de este mensaje
	 */
	public Optional<MessageVersionDefinition> versionOf(Class<?> type) {
		return versions.stream().filter(candidate -> candidate.itemDefinition().structureRef()
				.equals(type.getCanonicalName())).findFirst();
	}

	/**
	 * Versión más reciente.
	 *
	 * @return la de mayor número
	 */
	public MessageVersionDefinition latestVersion() {
		return versions.getLast();
	}
}
