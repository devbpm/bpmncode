package io.github.eldiegos.bpmncode.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Recurso BPMN ({@code bpmn:resource}) descubierto a partir de un tipo anotado con {@code @ProcessResource}: un rol de
 * negocio que realiza o puede reclamar tareas humanas.
 * <p>
 * Responsabilidad: identificar el rol, el tipo Java que lo declara y sus parámetros.
 * <p>
 * Invariantes: identificador y nombre nunca están vacíos; los parámetros no repiten nombre y siguen el orden de los
 * componentes del record (ninguno si el tipo no es un record).
 *
 * @param id identificador estable, p. ej. {@code RES-EXPENSE-APPROVER}
 * @param name nombre canónico
 * @param documentation texto de negocio, posiblemente vacío
 * @param definedBy nombre canónico del tipo Java que lo declara (extensión {@code pac:definedBy})
 * @param parameters parámetros del recurso ({@code bpmn:resourceParameter})
 * @see ResourceRole.Reference
 */
public record ResourceDefinition(String id, String name, String documentation, String definedBy,
		List<Parameter> parameters) {

	public ResourceDefinition {
		for (var value : new String[] { id, name, definedBy }) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Resource id, name and type are mandatory");
			}
		}
		documentation = documentation == null ? "" : documentation;
		parameters = List.copyOf(parameters);
		var names = new HashSet<String>();
		parameters.forEach(parameter -> {
			if (!names.add(parameter.name())) {
				throw new IllegalArgumentException("Resource " + id + " repeats the parameter " + parameter.name());
			}
		});
	}

	/**
	 * Parámetro de un recurso ({@code bpmn:resourceParameter}): un componente del record que lo declara.
	 * <p>
	 * Responsabilidad: describir un dato que concreta el rol, p. ej. el departamento del responsable.
	 * <p>
	 * Invariantes: el nombre nunca está vacío; un componente {@code Optional} no es obligatorio.
	 *
	 * @param name nombre del parámetro (el del componente)
	 * @param type estructura del parámetro: el tipo Java del componente (atributo {@code type})
	 * @param required si es obligatorio (atributo {@code isRequired})
	 * @see ResourceDefinition#parameters()
	 */
	public record Parameter(String name, ItemDefinition type, boolean required) {

		public Parameter {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("A resource parameter needs a name");
			}
			Objects.requireNonNull(type, "type");
		}
	}
}
