package io.github.eldiegos.bpmncode.model;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Referencia al código Java que implementa un nodo de proceso o una regla de negocio.
 * <p>
 * Responsabilidad: llevar la trazabilidad del modelo hasta el código, ya sea un tipo o un método concreto.
 * <p>
 * Invariantes: una referencia es a un tipo ({@link TypeReference}) o a un método ({@link MethodReference}); nunca hay
 * una referencia «a método» sin firma.
 *
 * @see FlowNode#implementation()
 * @see BusinessRuleDefinition#implementation()
 */
public sealed interface CodeReference permits CodeReference.TypeReference, CodeReference.MethodReference {

	/** Nombre completo de la clase referenciada. */
	String className();

	/**
	 * Crea una referencia a un tipo.
	 *
	 * @param type tipo referenciado
	 * @return la referencia
	 */
	static TypeReference ofType(Class<?> type) {
		return new TypeReference(type.getName());
	}

	/**
	 * Crea una referencia a un método, con los nombres simples de sus parámetros.
	 *
	 * @param method método referenciado
	 * @return la referencia
	 */
	static MethodReference ofMethod(Method method) {
		var parameters = Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
				.collect(Collectors.joining(", "));
		return new MethodReference(method.getDeclaringClass().getName(), method.getName() + "(" + parameters + ")");
	}

	/**
	 * Referencia a un tipo Java.
	 * <p>
	 * Responsabilidad: señalar la clase que implementa un elemento sin bajar a un método.
	 * <p>
	 * Invariantes: el nombre de clase nunca está vacío.
	 *
	 * @param className nombre completo de la clase
	 * @see CodeReference
	 */
	record TypeReference(String className) implements CodeReference {

		public TypeReference {
			requireText(className, "className");
		}

		@Override
		public String toString() {
			return className;
		}
	}

	/**
	 * Referencia a un método Java.
	 * <p>
	 * Responsabilidad: señalar exactamente el método que implementa un nodo o una regla.
	 * <p>
	 * Invariantes: clase y firma nunca están vacías.
	 *
	 * @param className nombre completo de la clase que declara el método
	 * @param signature nombre del método con los nombres simples de sus parámetros, p. ej. {@code register(Request)}
	 * @see CodeReference
	 */
	record MethodReference(String className, String signature) implements CodeReference {

		public MethodReference {
			requireText(className, "className");
			requireText(signature, "signature");
		}

		@Override
		public String toString() {
			return className + "#" + signature;
		}
	}

	private static void requireText(String value, String name) {
		if (Objects.requireNonNull(value, name).isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
	}
}
