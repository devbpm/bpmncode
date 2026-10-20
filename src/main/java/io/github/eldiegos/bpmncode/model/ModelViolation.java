package io.github.eldiegos.bpmncode.model;

/**
 * Problema encontrado al leer las definiciones de proceso anotadas.
 * <p>
 * Responsabilidad: describir un diagnóstico técnico para desarrolladores (en inglés, como los logs) con su clase y su
 * ubicación en el código.
 * <p>
 * Invariantes: la clase clasifica el problema; el mensaje no se traduce.
 *
 * @param kind clase de problema
 * @param location proceso, clase o método donde se encontró
 * @param message explicación técnica
 * @see ProcessModelException
 */
public record ModelViolation(ViolationKind kind, String location, String message) {

	@Override
	public String toString() {
		return "[" + kind + "] " + location + ": " + message;
	}
}
