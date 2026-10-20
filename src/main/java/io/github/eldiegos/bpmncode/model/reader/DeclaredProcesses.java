package io.github.eldiegos.bpmncode.model.reader;

import java.util.List;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.ProcessPart;
import io.github.eldiegos.bpmncode.model.ViolationKind;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Procesos declarados con {@code @Process}.
 * <p>
 * Responsabilidad: descubrir las cabeceras de proceso (una por versión) y decidir a qué versión de proceso pertenece
 * cada clase.
 * <p>
 * Invariantes: cada versión de un proceso está definida por una sola clase y tiene un identificador válido; un mismo
 * identificador de proceso puede tener varias versiones, cada una en su clase.
 *
 * @param byRef cabeceras por versión de proceso, ordenadas
 * @see ProcessMembership
 */
record DeclaredProcesses(SortedMap<ProcessDefinitionRef, Header> byRef) {

	/**
	 * Cabecera de una versión de un proceso declarada.
	 * <p>
	 * Responsabilidad: recordar la anotación, la clase que la define y las clases que le aportan nodos.
	 * <p>
	 * Invariantes: {@code implementedBy} solo crece durante la lectura.
	 *
	 * @param annotation anotación {@code @Process}
	 * @param type clase que define la versión del proceso
	 * @param implementedBy clases que aportan nodos, ordenadas
	 * @see DeclaredProcesses
	 */
	record Header(Process annotation, Class<?> type, SortedSet<String> implementedBy) {

		/** Versión del proceso que declara esta cabecera. */
		ProcessDefinitionRef ref() {
			return ProcessDefinitionRef.of(annotation.id(), annotation.version());
		}
	}

	/**
	 * Lee los procesos declarados.
	 *
	 * @param classes clases candidatas
	 * @param diagnostics colector de problemas
	 * @return las cabeceras válidas
	 */
	static DeclaredProcesses read(List<Class<?>> classes, ModelDiagnostics diagnostics) {
		var headers = new TreeMap<ProcessDefinitionRef, Header>();
		for (var type : classes) {
			var process = type.getAnnotation(Process.class);
			if (process == null) {
				continue;
			}
			var location = "Class " + type.getName();
			if (!diagnostics.isValidId(process.id(), "process", location)) {
				continue;
			}
			var named = diagnostics.hasName(process.name(), location);
			if (process.version() < 1) {
				diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location, "the process version must be >= 1");
				continue;
			}
			var ref = ProcessDefinitionRef.of(process.id(), process.version());
			var existing = headers.get(ref);
			if (existing != null) {
				diagnostics.report(ViolationKind.AMBIGUOUS_REFERENCE, location, "process " + ref
						+ " is already defined by " + existing.type().getName() + "; use @ProcessPart or a new version");
				continue;
			}
			if (diagnostics.claimBpmnId(process.id(), ref, location) && named) {
				headers.put(ref, new Header(process, type, new TreeSet<>()));
			}
		}
		return new DeclaredProcesses(headers);
	}

	/**
	 * Decide a qué versión de proceso pertenece una clase.
	 *
	 * @param type clase
	 * @param diagnostics colector de problemas
	 * @return la pertenencia
	 */
	ProcessMembership membershipOf(Class<?> type, ModelDiagnostics diagnostics) {
		var process = type.getAnnotation(Process.class);
		var component = type.getAnnotation(ProcessPart.class);
		var location = "Class " + type.getName();
		if (process != null && component != null) {
			diagnostics.report(ViolationKind.AMBIGUOUS_REFERENCE, location,
					"declares both @Process and @ProcessPart");
			return new ProcessMembership.Unresolved();
		}
		if (process != null) {
			var ref = process.version() < 1 ? null : ProcessDefinitionRef.of(process.id(), process.version());
			return ref != null && byRef.containsKey(ref) ? new ProcessMembership.Member(ref)
					: new ProcessMembership.Unresolved();
		}
		if (component != null) {
			return componentMembership(component, location, diagnostics);
		}
		return new ProcessMembership.Undeclared();
	}

	private ProcessMembership componentMembership(ProcessPart component, String location, ModelDiagnostics diagnostics) {
		var versions = byRef.keySet().stream().filter(key -> key.processId().equals(component.processRef())).toList();
		if (versions.isEmpty()) {
			diagnostics.report(ViolationKind.UNKNOWN_PROCESS_REF, location,
					"@ProcessPart references the unknown process '" + component.processRef() + "'");
			return new ProcessMembership.Unresolved();
		}
		if (component.version() == 0) {
			if (versions.size() > 1) {
				diagnostics.report(ViolationKind.AMBIGUOUS_REFERENCE, location, "process " + component.processRef()
						+ " has several versions " + versions + "; @ProcessPart must declare its version");
				return new ProcessMembership.Unresolved();
			}
			return new ProcessMembership.Member(versions.getFirst());
		}
		var ref = versions.stream().filter(candidate -> candidate.version() == component.version()).findFirst();
		if (ref.isEmpty()) {
			diagnostics.report(ViolationKind.UNKNOWN_PROCESS_REF, location, "@ProcessPart references the unknown "
					+ "version " + component.version() + " of process '" + component.processRef() + "'");
			return new ProcessMembership.Unresolved();
		}
		return new ProcessMembership.Member(ref.get());
	}
}
