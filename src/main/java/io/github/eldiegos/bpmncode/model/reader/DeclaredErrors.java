package io.github.eldiegos.bpmncode.model.reader;

import static io.github.eldiegos.bpmncode.model.reader.ModelDiagnostics.typeName;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.eldiegos.bpmncode.annotation.ProcessError;
import io.github.eldiegos.bpmncode.model.ErrorDefinition;
import io.github.eldiegos.bpmncode.model.ItemDefinition;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Errores declarados con {@code @ProcessError}.
 * <p>
 * Responsabilidad: validar cada error (excepción Java, identificador único, nombre y código) y resolver el tipo que un
 * {@code @ErrorEndEvent} usa como {@code errorRef}.
 * <p>
 * Invariantes: solo contiene errores válidos, ordenados por identificador; cada tipo resuelve a un único error.
 *
 * @param byType error de cada excepción anotada
 * @param errors errores válidos
 * @see AnnotationProcessModelReader
 */
record DeclaredErrors(Map<Class<?>, ErrorDefinition> byType, List<ErrorDefinition> errors) {

	/**
	 * Lee los errores declarados.
	 *
	 * @param classes clases con metadatos, en orden canónico
	 * @param diagnostics colector de problemas
	 * @return los errores válidos
	 */
	static DeclaredErrors read(List<Class<?>> classes, ModelDiagnostics diagnostics) {
		var byType = new LinkedHashMap<Class<?>, ErrorDefinition>();
		var errors = new ArrayList<ErrorDefinition>();
		for (var type : classes) {
			var error = type.getAnnotation(ProcessError.class);
			if (error == null) {
				continue;
			}
			var location = "Error " + type.getName();
			var valid = diagnostics.isValidId(error.id(), "error", location) && diagnostics.claimBpmnId(error.id(), location)
					&& diagnostics.hasName(error.name(), location);
			if (!Throwable.class.isAssignableFrom(type)) {
				diagnostics.report(ViolationKind.INVALID_ERROR, location, "@ProcessError goes on an exception (a subclass"
						+ " of Throwable)");
				valid = false;
			}
			if (error.errorCode().isBlank()) {
				diagnostics.report(ViolationKind.INVALID_ERROR, location, "@ProcessError does not declare its errorCode");
				valid = false;
			}
			if (valid) {
				var declared = new ErrorDefinition(error.id(), error.name(), error.errorCode(), error.documentation(),
						ItemDefinition.of(error.id(), type));
				errors.add(declared);
				byType.put(type, declared);
			}
		}
		errors.sort(Comparator.comparing(ErrorDefinition::id));
		return new DeclaredErrors(Map.copyOf(byType), List.copyOf(errors));
	}

	/**
	 * Resuelve el error de un tipo Java usado como {@code errorRef}.
	 *
	 * @param type la excepción
	 * @param location dónde se referencia
	 * @param diagnostics colector de problemas
	 * @return el error, o vacío (con el problema registrado) si el tipo no es un error declarado
	 */
	Optional<ErrorDefinition> resolve(Class<?> type, String location, ModelDiagnostics diagnostics) {
		var error = Optional.ofNullable(byType.get(type));
		if (error.isEmpty()) {
			diagnostics.report(ViolationKind.UNKNOWN_ERROR_REF, location, "errorRef " + typeName(type)
					+ " is not an exception annotated with @ProcessError");
		}
		return error;
	}
}
