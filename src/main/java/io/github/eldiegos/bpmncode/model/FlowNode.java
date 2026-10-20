package io.github.eldiegos.bpmncode.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.eldiegos.bpmncode.model.CodeReference.MethodReference;

/**
 * Nodo de flujo BPMN ({@code bpmn:flowNode}) descubierto a partir de un método anotado.
 * <p>
 * Responsabilidad: describir un nodo del proceso con su identificador estable, su nombre canónico y el método que lo
 * implementa, siguiendo la jerarquía de BPMN: {@link Event}, {@link Activity} ({@link Task}) y {@link Gateway}.
 * <p>
 * Invariantes: cada variante solo lleva los datos que le corresponden; una tarea de reglas de negocio siempre
 * referencia alguna regla; los eventos se definen por mensaje, temporizador (inicio) o error (fin), o no tienen
 * definición.
 *
 * @see ProcessDefinition
 */
public sealed interface FlowNode permits FlowNode.Event, FlowNode.Activity, FlowNode.Gateway {

	/** Identificador estable del nodo (atributo {@code id}). */
	String id();

	/** Proceso al que pertenece. */
	String processId();

	/** Nombre canónico (atributo {@code name}). */
	String name();

	/** Texto de negocio (elemento {@code bpmn:documentation}), posiblemente vacío. */
	String documentation();

	/** Método Java que implementa el nodo (extensión {@code pac:implementation}). */
	MethodReference implementation();

	/** Elemento BPMN que representa el nodo. */
	BpmnElementType elementType();

	/** Versiones de las reglas que aplica el nodo (extensión {@code pac:businessRuleRef}); vacío si no aplica ninguna. */
	default List<BusinessRuleRef> businessRuleRefs() {
		return List.of();
	}

	/**
	 * Versión del mensaje que publica el nodo: un evento de fin de mensaje o una tarea de envío.
	 *
	 * @return el mensaje y su versión, o vacío si el nodo no publica ningún mensaje
	 */
	default Optional<MessageVersionRef> publishedMessage() {
		return Optional.empty();
	}

	/**
	 * Evento BPMN ({@code bpmn:event}): inicio o fin del proceso.
	 * <p>
	 * Responsabilidad: reunir lo común a los eventos, su definición opcional.
	 * <p>
	 * Invariantes: un evento sin definición es un evento simple (none event).
	 *
	 * @see EventDefinition
	 */
	sealed interface Event extends FlowNode permits StartEvent, EndEvent {

		/** Definición del evento; vacía en un evento simple. */
		Optional<? extends EventDefinition> eventDefinition();

		/**
		 * Mensaje que recibe (inicio) o publica (fin) el evento.
		 *
		 * @return el identificador del mensaje, o vacío si no es un evento de mensaje
		 */
		default Optional<String> messageRef() {
			return messageDefinition().map(MessageEventDefinition::messageRef);
		}

		/**
		 * Definición de mensaje del evento, con sus versiones.
		 *
		 * @return la definición, o vacío si no es un evento de mensaje
		 */
		default Optional<MessageEventDefinition> messageDefinition() {
			return eventDefinition().filter(MessageEventDefinition.class::isInstance).map(MessageEventDefinition.class::cast);
		}
	}

	/**
	 * Evento de inicio ({@code bpmn:startEvent}).
	 * <p>
	 * Responsabilidad: representar el punto por el que nace cada instancia.
	 * <p>
	 * Invariantes: si lo dispara un mensaje, su definición es de mensaje; si lo dispara un temporizador, de temporizador;
	 * si lo inicia una llamada directa, está vacía.
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name nombre canónico
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que lo implementa
	 * @param eventDefinition mensaje o temporizador que inicia el proceso, si lo hay
	 * @see FlowNode
	 */
	record StartEvent(String id, String processId, String name, String documentation, MethodReference implementation,
			Optional<EventDefinition> eventDefinition) implements Event {

		public StartEvent {
			documentation = checked(id, processId, name, documentation, implementation);
			Objects.requireNonNull(eventDefinition, "eventDefinition");
		}

		/**
		 * Temporizador que inicia el proceso.
		 *
		 * @return la definición del temporizador, o vacío si no es un evento de inicio de temporizador
		 */
		public Optional<TimerEventDefinition> timer() {
			return eventDefinition.filter(TimerEventDefinition.class::isInstance).map(TimerEventDefinition.class::cast);
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.START_EVENT;
		}
	}

	/**
	 * Evento de fin ({@code bpmn:endEvent}); el nodo en el que termina una instancia es su resultado.
	 * <p>
	 * Responsabilidad: representar cada desenlace posible del proceso.
	 * <p>
	 * Invariantes: si publica un mensaje, su definición es de mensaje (con una sola versión); si termina con un error, de
	 * error; si es un evento de fin simple, está vacía. Nunca es de temporizador.
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name nombre canónico
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que lo implementa
	 * @param eventDefinition mensaje que publica o error con el que termina, si lo hay
	 * @see FlowNode
	 */
	record EndEvent(String id, String processId, String name, String documentation, MethodReference implementation,
			Optional<EventDefinition> eventDefinition) implements Event {

		public EndEvent {
			documentation = checked(id, processId, name, documentation, implementation);
			Objects.requireNonNull(eventDefinition, "eventDefinition");
			if (eventDefinition.filter(TimerEventDefinition.class::isInstance).isPresent()) {
				throw new IllegalArgumentException("End event " + id + " cannot be a timer event");
			}
		}

		/**
		 * Error con el que termina el proceso en este evento.
		 *
		 * @return la definición del error, o vacío si no es un evento de fin de error
		 */
		public Optional<ErrorEventDefinition> error() {
			return eventDefinition.filter(ErrorEventDefinition.class::isInstance).map(ErrorEventDefinition.class::cast);
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.END_EVENT;
		}

		@Override
		public Optional<MessageVersionRef> publishedMessage() {
			return messageDefinition().map(message -> new MessageVersionRef(message.messageRef(), message.publishedVersion()));
		}
	}

	/**
	 * Actividad BPMN ({@code bpmn:activity}): trabajo que se realiza en el proceso.
	 * <p>
	 * Responsabilidad: agrupar las actividades; hoy todas son tareas atómicas.
	 * <p>
	 * Invariantes: toda actividad es una {@link Task}.
	 *
	 * @see Task
	 */
	sealed interface Activity extends FlowNode permits Task {
	}

	/**
	 * Tarea BPMN ({@code bpmn:task}): actividad atómica.
	 * <p>
	 * Responsabilidad: agrupar las tareas de servicio, humanas, de reglas de negocio y de envío.
	 * <p>
	 * Invariantes: una tarea tiene exactamente un flujo de salida.
	 *
	 * @see ServiceTask
	 * @see UserTask
	 * @see BusinessRuleTask
	 * @see SendTask
	 */
	sealed interface Task extends Activity permits ServiceTask, UserTask, BusinessRuleTask, SendTask {

		/** Bucle estándar de la tarea ({@code bpmn:standardLoopCharacteristics}); vacío si la tarea no se repite. */
		Optional<StandardLoopCharacteristics> loopCharacteristics();
	}

	/**
	 * Tarea de servicio ({@code bpmn:serviceTask}): trabajo automático del sistema.
	 * <p>
	 * Responsabilidad: representar un trabajo automático y las reglas que aplica.
	 * <p>
	 * Invariantes: las reglas no se repiten.
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name nombre canónico
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que la implementa
	 * @param businessRuleRefs versiones de las reglas que aplica
	 * @param loopCharacteristics bucle estándar, si la tarea se repite
	 * @see FlowNode
	 */
	record ServiceTask(String id, String processId, String name, String documentation, MethodReference implementation,
			List<BusinessRuleRef> businessRuleRefs, Optional<StandardLoopCharacteristics> loopCharacteristics)
			implements Task {

		public ServiceTask {
			documentation = checked(id, processId, name, documentation, implementation);
			businessRuleRefs = List.copyOf(businessRuleRefs);
			Objects.requireNonNull(loopCharacteristics, "loopCharacteristics");
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.SERVICE_TASK;
		}
	}

	/**
	 * Tarea humana ({@code bpmn:userTask}): trabajo que completa una persona.
	 * <p>
	 * Responsabilidad: representar un trabajo humano, las reglas que aplica y quién puede o debe realizarlo.
	 * <p>
	 * Invariantes: las reglas no se repiten; los roles describen el modelo, no asignan la tarea.
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name nombre canónico
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que la implementa
	 * @param businessRuleRefs versiones de las reglas que aplica
	 * @param loopCharacteristics bucle estándar, si la tarea se repite
	 * @param potentialOwners quienes pueden reclamarla y completarla ({@code bpmn:potentialOwner})
	 * @param humanPerformers quienes la realizan ({@code bpmn:humanPerformer})
	 * @see FlowNode
	 */
	record UserTask(String id, String processId, String name, String documentation, MethodReference implementation,
			List<BusinessRuleRef> businessRuleRefs, Optional<StandardLoopCharacteristics> loopCharacteristics,
			List<ResourceRole> potentialOwners, List<ResourceRole> humanPerformers) implements Task {

		public UserTask {
			documentation = checked(id, processId, name, documentation, implementation);
			businessRuleRefs = List.copyOf(businessRuleRefs);
			Objects.requireNonNull(loopCharacteristics, "loopCharacteristics");
			potentialOwners = List.copyOf(potentialOwners);
			humanPerformers = List.copyOf(humanPerformers);
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.USER_TASK;
		}
	}

	/**
	 * Tarea de reglas de negocio ({@code bpmn:businessRuleTask}).
	 * <p>
	 * Responsabilidad: representar la evaluación de una o más reglas identificadas.
	 * <p>
	 * Invariantes: referencia al menos una regla.
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name nombre canónico
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que la implementa
	 * @param businessRuleRefs versiones de las reglas que evalúa
	 * @param loopCharacteristics bucle estándar, si la tarea se repite
	 * @see FlowNode
	 */
	record BusinessRuleTask(String id, String processId, String name, String documentation,
			MethodReference implementation, List<BusinessRuleRef> businessRuleRefs,
			Optional<StandardLoopCharacteristics> loopCharacteristics) implements Task {

		public BusinessRuleTask {
			documentation = checked(id, processId, name, documentation, implementation);
			businessRuleRefs = List.copyOf(businessRuleRefs);
			Objects.requireNonNull(loopCharacteristics, "loopCharacteristics");
			if (businessRuleRefs.isEmpty()) {
				throw new IllegalArgumentException("Business rule task " + id + " must reference at least one rule");
			}
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.BUSINESS_RULE_TASK;
		}
	}

	/**
	 * Tarea de envío ({@code bpmn:sendTask}): publica un mensaje y el proceso continúa.
	 * <p>
	 * Responsabilidad: representar el envío de una versión concreta de un {@link Message} a mitad del proceso y las
	 * reglas que aplica.
	 * <p>
	 * Invariantes: envía exactamente una versión de un mensaje declarado; las reglas no se repiten.
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name nombre canónico
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que la implementa
	 * @param businessRuleRefs versiones de las reglas que aplica
	 * @param loopCharacteristics bucle estándar, si la tarea se repite
	 * @param message mensaje y versión que envía (atributo {@code messageRef})
	 * @see FlowNode
	 */
	record SendTask(String id, String processId, String name, String documentation, MethodReference implementation,
			List<BusinessRuleRef> businessRuleRefs, Optional<StandardLoopCharacteristics> loopCharacteristics,
			MessageVersionRef message) implements Task {

		public SendTask {
			documentation = checked(id, processId, name, documentation, implementation);
			businessRuleRefs = List.copyOf(businessRuleRefs);
			Objects.requireNonNull(loopCharacteristics, "loopCharacteristics");
			Objects.requireNonNull(message, "message");
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.SEND_TASK;
		}

		@Override
		public Optional<MessageVersionRef> publishedMessage() {
			return Optional.of(message);
		}
	}

	/**
	 * Gateway BPMN ({@code bpmn:gateway}): punto donde el flujo se bifurca o se une.
	 * <p>
	 * Responsabilidad: agrupar los gateways; hoy solo existe el exclusivo.
	 * <p>
	 * Invariantes: todo gateway es un {@link ExclusiveGateway}.
	 *
	 * @see ExclusiveGateway
	 */
	sealed interface Gateway extends FlowNode permits ExclusiveGateway {
	}

	/**
	 * Gateway exclusivo ({@code bpmn:exclusiveGateway}).
	 * <p>
	 * Responsabilidad: representar el punto donde el proceso elige exactamente una de varias ramas.
	 * <p>
	 * Invariantes: las ramas son los flujos condicionales que salen del nodo (véase {@link SequenceFlow.Conditional}).
	 *
	 * @param id identificador estable del nodo
	 * @param processId proceso al que pertenece
	 * @param name la pregunta que responde
	 * @param documentation texto de negocio, posiblemente vacío
	 * @param implementation método que lo implementa
	 * @param businessRuleRefs versiones de las reglas en las que se basa
	 * @see FlowNode
	 */
	record ExclusiveGateway(String id, String processId, String name, String documentation,
			MethodReference implementation, List<BusinessRuleRef> businessRuleRefs) implements Gateway {

		public ExclusiveGateway {
			documentation = checked(id, processId, name, documentation, implementation);
			businessRuleRefs = List.copyOf(businessRuleRefs);
		}

		@Override
		public BpmnElementType elementType() {
			return BpmnElementType.EXCLUSIVE_GATEWAY;
		}
	}

	/** Comprueba los datos comunes y devuelve la documentación normalizada (vacía en lugar de nula). */
	private static String checked(String id, String processId, String name, String documentation,
			MethodReference implementation) {
		for (var value : new String[] { id, processId, name }) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Flow node id, process id and name are mandatory");
			}
		}
		Objects.requireNonNull(implementation, "implementation");
		return documentation == null ? "" : documentation;
	}
}
