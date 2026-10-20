package io.github.eldiegos.bpmncode.model;

import java.util.Objects;

/**
 * Error BPMN ({@code bpmn:error}) descubierto a partir de una excepción anotada con {@code @ProcessError}.
 * <p>
 * Responsabilidad: identificar un desenlace fallido de negocio, su código y la excepción Java que lo representa.
 * <p>
 * Invariantes: identificador, nombre y código nunca están vacíos; el tipo es una excepción.
 *
 * @param id identificador estable, p. ej. {@code ERR-CREDIT-REJECTED}
 * @param name nombre canónico
 * @param errorCode código del error (atributo {@code errorCode})
 * @param documentation texto de negocio, posiblemente vacío
 * @param itemDefinition estructura del error: la excepción Java (atributo {@code structureRef})
 * @see ErrorEventDefinition
 */
public record ErrorDefinition(String id, String name, String errorCode, String documentation,
		ItemDefinition itemDefinition) {

	public ErrorDefinition {
		for (var value : new String[] { id, name, errorCode }) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Error id, name and errorCode are mandatory");
			}
		}
		Objects.requireNonNull(itemDefinition, "itemDefinition");
		documentation = documentation == null ? "" : documentation;
	}
}
