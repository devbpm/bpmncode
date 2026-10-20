package io.github.eldiegos.bpmncode.model.reader;

import static io.github.eldiegos.bpmncode.model.reader.ModelDiagnostics.typeName;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.eldiegos.bpmncode.annotation.ProcessResource;
import io.github.eldiegos.bpmncode.model.ItemDefinition;
import io.github.eldiegos.bpmncode.model.ResourceDefinition;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Recursos declarados con {@code @ProcessResource}.
 * <p>
 * Responsabilidad: validar cada recurso (identificador único y nombre), leer sus parámetros de los componentes del
 * record y resolver el tipo que un rol de una tarea humana usa como {@code resourceRef}.
 * <p>
 * Invariantes: solo contiene recursos válidos, ordenados por identificador; cada tipo resuelve a un único recurso.
 *
 * @param byType recurso de cada tipo anotado
 * @param resources recursos válidos
 * @see AnnotationProcessModelReader
 */
record DeclaredResources(Map<Class<?>, ResourceDefinition> byType, List<ResourceDefinition> resources) {

	/**
	 * Lee los recursos declarados.
	 *
	 * @param classes clases con metadatos, en orden canónico
	 * @param diagnostics colector de problemas
	 * @return los recursos válidos
	 */
	static DeclaredResources read(List<Class<?>> classes, ModelDiagnostics diagnostics) {
		var byType = new LinkedHashMap<Class<?>, ResourceDefinition>();
		var resources = new ArrayList<ResourceDefinition>();
		for (var type : classes) {
			var resource = type.getAnnotation(ProcessResource.class);
			if (resource == null) {
				continue;
			}
			var location = "Resource " + type.getName();
			if (diagnostics.isValidId(resource.id(), "resource", location) && diagnostics.claimBpmnId(resource.id(), location)
					&& diagnostics.hasName(resource.name(), location)) {
				var declared = new ResourceDefinition(resource.id(), resource.name(), resource.documentation(),
						type.getCanonicalName(), parameters(resource.id(), type));
				resources.add(declared);
				byType.put(type, declared);
			}
		}
		resources.sort(Comparator.comparing(ResourceDefinition::id));
		return new DeclaredResources(Map.copyOf(byType), List.copyOf(resources));
	}

	/** Parámetros de un recurso: los componentes del record, en su orden; ninguno si el tipo no es un record. */
	private static List<ResourceDefinition.Parameter> parameters(String resourceId, Class<?> type) {
		if (!type.isRecord()) {
			return List.of();
		}
		return Arrays.stream(type.getRecordComponents()).map(component -> new ResourceDefinition.Parameter(
				component.getName(), new ItemDefinition("ItemDefinition_" + resourceId + "_" + component.getName(),
						valueType(component).getCanonicalName()),
				component.getType() != Optional.class)).toList();
	}

	/** Tipo del valor de un componente: el de dentro si es {@code Optional<T>}. */
	private static Class<?> valueType(RecordComponent component) {
		if (component.getType() == Optional.class && component.getGenericType() instanceof ParameterizedType generic
				&& generic.getActualTypeArguments()[0] instanceof Class<?> value) {
			return value;
		}
		return component.getType();
	}

	/**
	 * Resuelve el recurso de un tipo Java usado como {@code resourceRef}.
	 *
	 * @param type el tipo
	 * @param location dónde se referencia
	 * @param diagnostics colector de problemas
	 * @return el recurso, o vacío (con el problema registrado) si el tipo no es un recurso declarado
	 */
	Optional<ResourceDefinition> resolve(Class<?> type, String location, ModelDiagnostics diagnostics) {
		var resource = Optional.ofNullable(byType.get(type));
		if (resource.isEmpty()) {
			diagnostics.report(ViolationKind.UNKNOWN_RESOURCE_REF, location, "resourceRef " + typeName(type)
					+ " is not a type annotated with @ProcessResource");
		}
		return resource;
	}
}
