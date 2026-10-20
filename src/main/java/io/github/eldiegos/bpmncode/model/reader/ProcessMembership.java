package io.github.eldiegos.bpmncode.model.reader;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Pertenencia de una clase a un proceso.
 * <p>
 * Responsabilidad: decir si los nodos que declara una clase pertenecen a un proceso conocido.
 * <p>
 * Invariantes: una clase es miembro de un proceso existente, no declara ninguno, o su declaración tiene un problema ya
 * registrado; no hay valores centinela.
 *
 * @see DeclaredProcesses#membershipOf(Class, ModelDiagnostics)
 */
sealed interface ProcessMembership permits ProcessMembership.Member, ProcessMembership.Undeclared, ProcessMembership.Unresolved {

	/**
	 * La clase aporta nodos a un proceso existente.
	 * <p>
	 * Responsabilidad: identificar la versión del proceso a la que aporta nodos.
	 * <p>
	 * Invariantes: el proceso está declarado.
	 *
	 * @param ref versión del proceso a la que pertenece
	 * @see ProcessMembership
	 */
	record Member(ProcessDefinitionRef ref) implements ProcessMembership {
	}

	/**
	 * La clase no declara {@code @Process} ni {@code @ProcessPart}.
	 * <p>
	 * Responsabilidad: permitir informar de los nodos huérfanos.
	 * <p>
	 * Invariantes: sin datos.
	 *
	 * @see ProcessMembership
	 */
	record Undeclared() implements ProcessMembership {
	}

	/**
	 * La declaración de la clase tiene un problema ya registrado (proceso desconocido o ambiguo).
	 * <p>
	 * Responsabilidad: evitar informar dos veces del mismo problema.
	 * <p>
	 * Invariantes: sin datos.
	 *
	 * @see ProcessMembership
	 */
	record Unresolved() implements ProcessMembership {
	}
}
