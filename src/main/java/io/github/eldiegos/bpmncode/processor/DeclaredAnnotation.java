package io.github.eldiegos.bpmncode.processor;

import java.util.List;
import java.util.Optional;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * Anotación tal como la ve el compilador, con sus valores por defecto.
 * <p>
 * Responsabilidad: leer los atributos de una anotación del framework o de Spring sin cargar su clase, que no está en el
 * classpath del procesador.
 * <p>
 * Invariantes: los atributos que no se escriben toman su valor por defecto; un atributo que no existe o tiene otro tipo
 * se lee como vacío.
 *
 * @param mirror anotación escrita en el código
 * @param elements utilidades del compilador, para los valores por defecto
 * @see ProcessAnnotationProcessor
 */
record DeclaredAnnotation(AnnotationMirror mirror, Elements elements) {

	/** Nombre cualificado del tipo de la anotación. */
	String name() {
		return nameOf(mirror);
	}

	static String nameOf(AnnotationMirror mirror) {
		return ((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName().toString();
	}

	/**
	 * Busca una anotación escrita directamente en un elemento.
	 *
	 * @param element elemento anotado
	 * @param name nombre cualificado de la anotación
	 * @param elements utilidades del compilador
	 * @return la anotación, o vacío si el elemento no la lleva
	 */
	static Optional<DeclaredAnnotation> on(Element element, String name, Elements elements) {
		return element.getAnnotationMirrors().stream().filter(mirror -> nameOf(mirror).equals(name)).findFirst()
				.map(mirror -> new DeclaredAnnotation(mirror, elements));
	}

	String string(String attribute) {
		return value(attribute, true).map(AnnotationValue::getValue).filter(String.class::isInstance).map(String.class::cast)
				.orElse("");
	}

	int integer(String attribute) {
		return value(attribute, true).map(AnnotationValue::getValue).filter(Integer.class::isInstance)
				.map(Integer.class::cast).orElse(0);
	}

	/** Tipo de un atributo {@code Class<?>}, o vacío si no lo tiene. */
	Optional<TypeMirror> type(String attribute) {
		return value(attribute, true).map(AnnotationValue::getValue).filter(TypeMirror.class::isInstance)
				.map(TypeMirror.class::cast);
	}

	List<String> strings(String attribute) {
		return array(attribute, true).stream().map(AnnotationValue::getValue).filter(String.class::isInstance)
				.map(String.class::cast).toList();
	}

	List<DeclaredAnnotation> annotations(String attribute) {
		return array(attribute, true).stream().map(AnnotationValue::getValue).filter(AnnotationMirror.class::isInstance)
				.map(value -> new DeclaredAnnotation((AnnotationMirror) value, elements)).toList();
	}

	/** Indica si el atributo se escribió en el código (no se dejó su valor por defecto). */
	boolean isWritten(String attribute) {
		return value(attribute, false).isPresent();
	}

	private Optional<? extends AnnotationValue> value(String attribute, boolean withDefaults) {
		var values = withDefaults ? elements.getElementValuesWithDefaults(mirror) : mirror.getElementValues();
		return values.entrySet().stream().filter(entry -> entry.getKey().getSimpleName().contentEquals(attribute))
				.map(entry -> entry.getValue()).findFirst();
	}

	@SuppressWarnings("unchecked")
	private List<? extends AnnotationValue> array(String attribute, boolean withDefaults) {
		return value(attribute, withDefaults).map(AnnotationValue::getValue).filter(List.class::isInstance)
				.map(list -> (List<? extends AnnotationValue>) list).orElse(List.of());
	}
}
