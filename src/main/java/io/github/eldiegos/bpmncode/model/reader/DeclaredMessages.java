package io.github.eldiegos.bpmncode.model.reader;

import static io.github.eldiegos.bpmncode.model.reader.ModelDiagnostics.typeName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import io.github.eldiegos.bpmncode.annotation.MessageVersion;
import io.github.eldiegos.bpmncode.model.ItemDefinition;
import io.github.eldiegos.bpmncode.model.Message;
import io.github.eldiegos.bpmncode.model.MessageContractLock;
import io.github.eldiegos.bpmncode.model.MessageVersionDefinition;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ViolationKind;
import io.github.eldiegos.bpmncode.runtime.ProcessMessage;

/**
 * Mensajes declarados con {@code @Message} y sus versiones ({@code @MessageVersion}).
 * <p>
 * Responsabilidad: descubrir las familias de versiones (una {@code sealed interface} y sus records) o los mensajes de
 * una sola versión (un record), generar el esquema de cada versión, compararlo con {@code pac/message-contracts.lock}
 * y resolver qué mensaje y qué versiones corresponden a un tipo Java usado como {@code messageRef}.
 * <p>
 * Invariantes: cada mensaje tiene un identificador válido y único y al menos una versión; cada versión es un record que
 * implementa {@link ProcessMessage} y cuyo esquema puede generarse; si hay fichero de bloqueo, toda versión está
 * congelada en él con la misma huella y él no congela versiones que ya no existen.
 *
 * @param byType referencia que resuelve cada tipo Java: la familia (todas sus versiones) o el record de una versión
 * @param messages mensajes válidos
 * @see Message
 */
record DeclaredMessages(Map<Class<?>, MessageReference> byType, List<Message> messages) {

	/**
	 * Lee los mensajes declarados.
	 *
	 * @param classes clases con metadatos, en orden canónico
	 * @param lock huellas congeladas, si la aplicación tiene {@code pac/message-contracts.lock}
	 * @param diagnostics colector de problemas
	 * @return los mensajes válidos
	 */
	static DeclaredMessages read(List<Class<?>> classes, Optional<MessageContractLock> lock, ModelDiagnostics diagnostics) {
		var byType = new LinkedHashMap<Class<?>, MessageReference>();
		var messages = new ArrayList<Message>();
		var versionTypes = new HashSet<Class<?>>();
		for (var type : classes) {
			var message = type.getAnnotation(io.github.eldiegos.bpmncode.annotation.Message.class);
			if (message == null) {
				continue;
			}
			var location = "Message " + type.getName();
			if (!(diagnostics.isValidId(message.id(), "message", location) && diagnostics.claimBpmnId(message.id(), location)
					&& diagnostics.hasName(message.name(), location))) {
				continue;
			}
			var members = versionTypesOf(type, location, diagnostics);
			versionTypes.addAll(members);
			versions(message.id(), type, members, location, diagnostics).ifPresent(versions -> {
				var declared = new Message(message.id(), message.name(), message.documentation(),
						ItemDefinition.of(message.id(), type), versions);
				messages.add(declared);
				var all = versions.stream().map(MessageVersionDefinition::version).toList();
				byType.put(type, new MessageReference(declared, all, !type.isInterface()));
				for (var member : members) {
					var version = declared.versionOf(member).orElseThrow().version();
					byType.putIfAbsent(member, new MessageReference(declared, List.of(version), true));
				}
			});
		}
		classes.stream()
				.filter(type -> type.isAnnotationPresent(MessageVersion.class) && !versionTypes.contains(type))
				.forEach(type -> diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, "Message " + type.getName(),
						"@MessageVersion is only allowed on a record that implements a sealed interface annotated with"
								+ " @Message, or on a record annotated with @Message"));
		messages.sort(Comparator.comparing(Message::id));
		lock.ifPresent(frozen -> checkLock(messages, frozen, diagnostics));
		return new DeclaredMessages(Map.copyOf(byType), List.copyOf(messages));
	}

	/** Records de las versiones: los permitidos por la familia sellada o el propio record. */
	private static List<Class<?>> versionTypesOf(Class<?> type, String location, ModelDiagnostics diagnostics) {
		if (type.isRecord()) {
			return List.of(type);
		}
		if (type.isInterface() && type.isSealed()) {
			return Arrays.stream(type.getPermittedSubclasses()).sorted(Comparator.comparing(Class::getName))
					.<Class<?>> map(member -> member).toList();
		}
		diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, location,
				"@Message goes on a sealed interface (one record per version) or on a record (a single version)");
		return List.of();
	}

	private static Optional<List<MessageVersionDefinition>> versions(String messageId, Class<?> family,
			List<Class<?>> members, String location, ModelDiagnostics diagnostics) {
		var before = diagnostics.count();
		var versions = new ArrayList<MessageVersionDefinition>();
		var numbers = new HashMap<Integer, Class<?>>();
		for (var member : members) {
			var memberLocation = "Message " + member.getName();
			if (!member.isRecord()) {
				diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, memberLocation,
						"every version of " + messageId + " must be a record");
				continue;
			}
			if (!ProcessMessage.class.isAssignableFrom(member)) {
				diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, memberLocation, "every version of " + messageId
						+ " must implement ProcessMessage (a 'MessageHeader header' component)");
				continue;
			}
			var annotation = Optional.ofNullable(member.getAnnotation(MessageVersion.class));
			if (annotation.isEmpty() && member != family) {
				diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, memberLocation,
						"every version of " + messageId + " needs @MessageVersion");
				continue;
			}
			var number = annotation.map(MessageVersion::value).orElse(1);
			if (number < 1) {
				diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, memberLocation,
						"@MessageVersion must be greater than zero");
				continue;
			}
			var previous = numbers.putIfAbsent(number, member);
			if (previous != null) {
				diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, memberLocation, "version " + number + " of "
						+ messageId + " is already declared by " + previous.getName());
				continue;
			}
			switch (MessageSchemaWriter.write(member, messageId, number)) {
				case MessageSchemaWriter.Generated.Schema generated -> versions.add(new MessageVersionDefinition(number,
						ItemDefinition.ofVersion(messageId, number, member), generated.schema()));
				case MessageSchemaWriter.Generated.Unsupported unsupported -> diagnostics.report(
						ViolationKind.INVALID_MESSAGE_VERSION, memberLocation, unsupported.reason());
			}
		}
		if (versions.isEmpty() && diagnostics.count() == before) {
			diagnostics.report(ViolationKind.INVALID_MESSAGE_VERSION, location, messageId + " declares no version");
		}
		return diagnostics.count() == before ? Optional.of(versions) : Optional.empty();
	}

	/** Compara cada versión con su huella congelada. */
	private static void checkLock(List<Message> messages, MessageContractLock lock, ModelDiagnostics diagnostics) {
		var declared = new TreeSet<String>();
		for (var message : messages) {
			for (var version : message.versions()) {
				var key = version.lockKey(message.id());
				declared.add(key);
				var location = "Message " + version.itemDefinition().structureRef();
				var frozen = lock.fingerprints().get(key);
				if (frozen == null) {
					diagnostics.report(ViolationKind.MESSAGE_CONTRACT_NOT_LOCKED, location, key + " is not frozen in "
							+ MessageContractLock.LOCATION + "; add the line: "
							+ MessageContractLock.lineOf(new MessageVersionRef(message.id(), version.version()), version.schema()));
				}
				else if (!frozen.equals(version.schema().fingerprint())) {
					diagnostics.report(ViolationKind.MESSAGE_CONTRACT_CHANGED, location, "the contract of " + key
							+ " changed (frozen " + frozen + ", now " + version.schema().fingerprint() + "): a published"
							+ " version never changes, declare a new @MessageVersion instead");
				}
			}
		}
		lock.fingerprints().keySet().stream().filter(key -> !declared.contains(key)).sorted()
				.forEach(key -> diagnostics.report(ViolationKind.MESSAGE_CONTRACT_REMOVED, MessageContractLock.LOCATION,
						key + " is frozen but no longer declared: messages of that version may still be in flight"));
	}

	/**
	 * Resuelve el mensaje de un tipo Java usado como {@code messageRef}.
	 *
	 * @param type la familia o el record de una versión
	 * @param location dónde se referencia
	 * @param diagnostics colector de problemas
	 * @return el mensaje y sus versiones, o vacío (con el problema registrado) si el tipo no es un mensaje
	 */
	Optional<MessageReference> resolve(Class<?> type, String location, ModelDiagnostics diagnostics) {
		var reference = Optional.ofNullable(byType.get(type));
		if (reference.isEmpty()) {
			diagnostics.report(ViolationKind.UNKNOWN_MESSAGE_REF, location, "messageRef " + typeName(type)
					+ " is neither a @Message nor one of its @MessageVersion records");
		}
		return reference;
	}

	/**
	 * Devuelve los mensajes ordenados.
	 *
	 * @return los mensajes por identificador
	 */
	List<Message> sorted() {
		return messages;
	}

	/**
	 * Mensaje y versiones a los que remite un tipo Java.
	 * <p>
	 * Responsabilidad: decir si un {@code messageRef} nombra una versión concreta (lo que exige un evento de fin) o la
	 * familia entera (lo que acepta un evento de inicio).
	 * <p>
	 * Invariantes: una referencia concreta tiene exactamente una versión.
	 *
	 * @param message mensaje
	 * @param versions versiones que nombra el tipo
	 * @param concrete si el tipo es el record de una versión
	 * @see DeclaredMessages#resolve(Class, String, ModelDiagnostics)
	 */
	record MessageReference(Message message, List<Integer> versions, boolean concrete) {

		MessageReference {
			versions = List.copyOf(versions);
		}
	}
}
