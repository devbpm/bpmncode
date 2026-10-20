package io.github.eldiegos.bpmncode.processor;

import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE_VERSION;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_MESSAGE;

import java.util.HashMap;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Reglas de compilación de los contratos de mensaje ({@code @Message} y {@code @MessageVersion}).
 * <p>
 * Responsabilidad: comprobar la forma de una familia de versiones: {@code @Message} en una {@code sealed interface} o en
 * un record; cada versión, un record con {@code @MessageVersion} que implementa {@code ProcessMessage}; números de
 * versión positivos y sin repetir.
 * <p>
 * Invariantes: solo usa el tipo anotado y los tipos que permite su {@code sealed interface}, que el compilador conoce
 * siempre; el esquema de cada versión y su huella los genera y compara el lector del modelo al arrancar.
 *
 * @see ProcessAnnotationProcessor
 */
final class MessageRules {

	private final Elements elements;
	private final Types types;
	private final Problems problems;

	MessageRules(Elements elements, Types types, Problems problems) {
		this.elements = elements;
		this.types = types;
		this.problems = problems;
	}

	/**
	 * Comprueba un mensaje.
	 *
	 * @param type tipo anotado con {@code @Message}
	 * @param message la anotación
	 */
	void checkMessage(TypeElement type, DeclaredAnnotation message) {
		Identity.check(message, "message", type, problems);
		if (type.getKind() == ElementKind.RECORD) {
			// Mensaje de una sola versión: el propio record.
			if (!isProcessMessage(type)) {
				problems.error(type, message, "every version of " + message.string("id")
						+ " must implement ProcessMessage (a 'MessageHeader header' component)");
			}
			return;
		}
		if (type.getKind() != ElementKind.INTERFACE || !type.getModifiers().contains(Modifier.SEALED)) {
			problems.error(type, message, "@Message goes on a sealed interface (one record per version) or on a record"
					+ " (a single version)");
			return;
		}
		var numbers = new HashMap<Integer, String>();
		for (var permitted : type.getPermittedSubclasses()) {
			var member = (TypeElement) ((DeclaredType) permitted).asElement();
			var version = DeclaredAnnotation.on(member, MESSAGE_VERSION, elements);
			if (version.isEmpty()) {
				problems.error(type, message, "every version of " + message.string("id") + " needs @MessageVersion: "
						+ member.getSimpleName() + " has none");
				continue;
			}
			if (member.getKind() == ElementKind.RECORD && !isProcessMessage(member)) {
				problems.error(type, message, "every version of " + message.string("id") + " must implement ProcessMessage"
						+ " (a 'MessageHeader header' component): " + member.getSimpleName() + " does not");
			}
			var previous = numbers.putIfAbsent(version.get().integer("value"), member.getSimpleName().toString());
			if (previous != null) {
				problems.error(type, message, "version " + version.get().integer("value") + " of " + message.string("id")
						+ " is declared by both " + previous + " and " + member.getSimpleName());
			}
		}
	}

	/**
	 * Comprueba una versión de mensaje.
	 *
	 * @param type tipo anotado con {@code @MessageVersion}
	 * @param version la anotación
	 */
	void checkVersion(TypeElement type, DeclaredAnnotation version) {
		if (type.getKind() != ElementKind.RECORD) {
			problems.error(type, version, "a message version must be a record");
		}
		if (version.integer("value") < 1) {
			problems.error(type, version, "@MessageVersion must be greater than zero");
		}
		var ownMessage = DeclaredAnnotation.on(type, MESSAGE, elements).isPresent();
		var family = type.getInterfaces().stream()
				.map(implemented -> (TypeElement) ((DeclaredType) implemented).asElement())
				.anyMatch(implemented -> DeclaredAnnotation.on(implemented, MESSAGE, elements).isPresent()
						&& implemented.getPermittedSubclasses().stream()
								.anyMatch(permitted -> types.isSameType(types.erasure(permitted), types.erasure(type.asType()))));
		if (!ownMessage && !family) {
			problems.error(type, version, "@MessageVersion is only allowed on a record that implements a sealed interface"
					+ " annotated with @Message, or on a record annotated with @Message");
		}
	}

	private boolean isProcessMessage(TypeElement type) {
		var processMessage = elements.getTypeElement(PROCESS_MESSAGE);
		// Sin el framework en el classpath no hay contrato que comprobar.
		return processMessage == null || types.isAssignable(type.asType(), types.erasure(processMessage.asType()));
	}
}
