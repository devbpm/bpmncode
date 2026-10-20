package io.github.eldiegos.bpmncode.model;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.util.ClassUtils;

/**
 * Catálogo de los contratos de mensaje entre procesos.
 * <p>
 * Responsabilidad: reunir, por cada mensaje, sus versiones con sus campos, su huella y si está congelada en
 * {@code pac/message-contracts.lock}, qué versiones de proceso publican cada versión y qué eventos de inicio aceptan
 * el mensaje; es lo que una pantalla de gobierno o una herramienta necesita para mostrar los contratos sin recorrer el
 * modelo por su cuenta.
 * <p>
 * Invariantes: todo sale del modelo descubierto y del fichero de bloqueo que se le da; los mensajes, versiones,
 * publicadores y consumidores van en el orden del modelo; los campos son los componentes del record de cada versión, en
 * su orden; no contiene textos traducidos.
 *
 * @see MessageContractLock
 */
public class MessageContractCatalog {

	private final List<Contract> contracts;
	private final boolean lockPresent;

	/**
	 * Construye el catálogo.
	 *
	 * @param definitions modelo validado
	 * @param lock huellas congeladas, o vacío si la aplicación no tiene fichero de bloqueo
	 */
	public MessageContractCatalog(Definitions definitions, Optional<MessageContractLock> lock) {
		this.lockPresent = lock.isPresent();
		this.contracts = definitions.messages().stream().map(message -> contract(definitions, message, lock)).toList();
	}

	/**
	 * Devuelve los contratos.
	 *
	 * @return un contrato por mensaje, por identificador
	 */
	public List<Contract> contracts() {
		return contracts;
	}

	/**
	 * Busca el contrato de un mensaje.
	 *
	 * @param messageId identificador del mensaje
	 * @return el contrato, o vacío si no existe
	 */
	public Optional<Contract> contract(String messageId) {
		return contracts.stream().filter(contract -> contract.message().id().equals(messageId)).findFirst();
	}

	/**
	 * Indica si la aplicación tiene fichero de bloqueo (y, por tanto, sus contratos se comprueban al arrancar).
	 *
	 * @return {@code true} si hay {@code pac/message-contracts.lock}
	 */
	public boolean lockPresent() {
		return lockPresent;
	}

	private static Contract contract(Definitions definitions, Message message, Optional<MessageContractLock> lock) {
		var versions = message.versions().stream().map(version -> {
			var ref = new MessageVersionRef(message.id(), version.version());
			var status = lock.map(frozen -> frozen.freezes(ref, version.schema()) ? LockStatus.FROZEN : LockStatus.NOT_FROZEN)
					.orElse(LockStatus.NO_LOCK_FILE);
			return new Version(ref, version, fieldsOf(version), status, MessageContractLock.lineOf(ref, version.schema()),
					publishers(definitions, ref));
		}).toList();
		return new Contract(message, versions, consumers(definitions, message.id()));
	}

	private static List<Publisher> publishers(Definitions definitions, MessageVersionRef version) {
		return definitions.processes().stream()
				.flatMap(process -> process.flowNodes().stream()
						.filter(node -> node.publishedMessage().filter(version::equals).isPresent())
						.map(node -> new Publisher(process.ref(), node)))
				.toList();
	}

	private static List<Consumer> consumers(Definitions definitions, String messageId) {
		return definitions.processes().stream()
				.flatMap(process -> process.flowNodes().stream()
						.filter(FlowNode.StartEvent.class::isInstance)
						.map(FlowNode.StartEvent.class::cast)
						.flatMap(start -> start.messageDefinition()
								.filter(accepted -> accepted.messageRef().equals(messageId))
								.map(accepted -> new Consumer(process.ref(), start, accepted.versions())).stream()))
				.toList();
	}

	/** Campos del record de una versión, leídos por reflexión; vacío si el tipo ya no está en el classpath. */
	private static List<Field> fieldsOf(MessageVersionDefinition version) {
		var structure = version.itemDefinition().structureRef();
		if (!ClassUtils.isPresent(structure, ClassUtils.getDefaultClassLoader())) {
			return List.of();
		}
		var type = ClassUtils.resolveClassName(structure, ClassUtils.getDefaultClassLoader());
		return type.isRecord() ? Arrays.stream(type.getRecordComponents()).map(MessageContractCatalog::field).toList()
				: List.of();
	}

	private static Field field(RecordComponent component) {
		var type = component.getType();
		var optional = false;
		if (type == Optional.class && component.getGenericType() instanceof ParameterizedType parameterized
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> wrapped) {
			type = wrapped;
			optional = true;
		}
		var constants = type.isEnum()
				? Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList()
				: List.<String> of();
		return new Field(component.getName(), type.getSimpleName(), optional, component.getName().equals("header"), constants);
	}

	/**
	 * Estado de una versión frente al fichero de bloqueo.
	 * <p>
	 * Responsabilidad: decir si la huella de una versión está congelada.
	 * <p>
	 * Invariantes: con fichero de bloqueo, la aplicación no arranca si una versión no está congelada con su huella, así
	 * que {@link #NOT_FROZEN} solo aparece si el catálogo se construye fuera del arranque (p. ej. en una prueba).
	 *
	 * @see Version#lockStatus()
	 */
	public enum LockStatus {

		/** Congelada con la huella de su esquema actual. */
		FROZEN,

		/** El fichero existe pero no la congela con esa huella. */
		NOT_FROZEN,

		/** La aplicación no tiene fichero de bloqueo. */
		NO_LOCK_FILE
	}

	/**
	 * Contrato de un mensaje.
	 * <p>
	 * Responsabilidad: reunir el mensaje, sus versiones y quién lo consume.
	 * <p>
	 * Invariantes: las listas son inmutables.
	 *
	 * @param message mensaje
	 * @param versions sus versiones
	 * @param consumers eventos de inicio que lo aceptan
	 * @see MessageContractCatalog
	 */
	public record Contract(Message message, List<Version> versions, List<Consumer> consumers) {

		public Contract {
			Objects.requireNonNull(message, "message");
			versions = List.copyOf(versions);
			consumers = List.copyOf(consumers);
		}
	}

	/**
	 * Una versión de un contrato.
	 * <p>
	 * Responsabilidad: describir una versión: su referencia, su definición (record y esquema), sus campos, su estado de
	 * congelación, la línea que la congela y quién la publica.
	 * <p>
	 * Invariantes: las listas son inmutables; {@code lockLine} es la línea exacta para {@code message-contracts.lock}.
	 *
	 * @param ref mensaje y versión
	 * @param definition versión del modelo (record y esquema)
	 * @param fields campos del record
	 * @param lockStatus estado frente al fichero de bloqueo
	 * @param lockLine línea que la congela
	 * @param publishers eventos de fin y tareas de envío que la publican
	 * @see Contract
	 */
	public record Version(MessageVersionRef ref, MessageVersionDefinition definition, List<Field> fields,
			LockStatus lockStatus, String lockLine, List<Publisher> publishers) {

		public Version {
			Objects.requireNonNull(ref, "ref");
			Objects.requireNonNull(definition, "definition");
			fields = List.copyOf(fields);
			Objects.requireNonNull(lockStatus, "lockStatus");
			publishers = List.copyOf(publishers);
		}
	}

	/**
	 * Nodo que publica una versión: un evento de fin de mensaje o una tarea de envío.
	 * <p>
	 * Responsabilidad: situar un publicador en su versión de proceso.
	 * <p>
	 * Invariantes: el nodo pertenece a esa versión y publica exactamente esa versión del mensaje.
	 *
	 * @param process proceso y versión
	 * @param flowNode evento de fin o tarea de envío
	 * @see Version#publishers()
	 * @see FlowNode#publishedMessage()
	 */
	public record Publisher(ProcessDefinitionRef process, FlowNode flowNode) {
	}

	/**
	 * Evento de inicio que acepta un mensaje.
	 * <p>
	 * Responsabilidad: situar un consumidor en su versión de proceso y decir qué versiones acepta.
	 * <p>
	 * Invariantes: las versiones aceptadas van ordenadas y sin repetir.
	 *
	 * @param process proceso y versión
	 * @param startEvent evento de inicio
	 * @param acceptedVersions versiones aceptadas
	 * @see Contract#consumers()
	 */
	public record Consumer(ProcessDefinitionRef process, FlowNode.StartEvent startEvent, List<Integer> acceptedVersions) {

		public Consumer {
			acceptedVersions = List.copyOf(acceptedVersions);
		}
	}

	/**
	 * Campo de una versión de mensaje.
	 * <p>
	 * Responsabilidad: describir un componente del record de la versión.
	 * <p>
	 * Invariantes: {@code type} es el nombre simple del tipo (el envuelto, si es {@code Optional}); {@code enumConstants}
	 * solo tiene valores si el tipo es un {@code enum}; {@code header} marca la cabecera común ({@code MessageHeader}).
	 *
	 * @param name nombre del componente
	 * @param type nombre simple de su tipo
	 * @param optional si es {@code Optional}
	 * @param header si es la cabecera común
	 * @param enumConstants constantes, si es un {@code enum}
	 * @see Version#fields()
	 */
	public record Field(String name, String type, boolean optional, boolean header, List<String> enumConstants) {

		public Field {
			enumConstants = List.copyOf(enumConstants);
		}
	}
}
