package io.github.eldiegos.bpmncode.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.FlowNode.EndEvent;
import io.github.eldiegos.bpmncode.model.FlowNode.ExclusiveGateway;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import io.github.eldiegos.bpmncode.model.StandardLoopCharacteristics;

/**
 * Estado de ejecución de una instancia de proceso.
 * <p>
 * Responsabilidad: recordar qué definición (y versión) ejecuta la instancia y en qué nodo de flujo está su token, y
 * comprobar que cada avance sigue un flujo de secuencia declarado (conformidad en ejecución). Se carga de un
 * {@link ProcessInstanceRecord} y se vuelve a convertir en uno para guardarla.
 * <p>
 * Invariantes: hay una instancia por proceso y correlación (la garantiza el almacén), lo que hace idempotentes los
 * inicios por mensaje; una instancia completada no avanza; tras un gateway solo se acepta el flujo seleccionado; una
 * tarea con bucle estándar puede repetirse sin flujo, hasta su {@code loopMaximum}; cada instancia avanza siempre según
 * la definición de su propia versión, aunque haya otra más nueva desplegada.
 *
 * @see ProcessJournal
 */
final class ProcessInstance {

	private final UUID id;
	private final String processId;
	private final int processVersion;
	private final CorrelationKey correlationKey;
	private final Instant startedAt;
	private final long version;

	/** Referencia seudónima al sujeto de negocio, p. ej. {@code person:<uuid>}. Nunca datos personales. */
	private Optional<String> businessKey;
	private ProcessInstanceState state;

	/** Nodo de flujo en el que está el token de la instancia. */
	private String currentFlowNodeId;

	/** Iteraciones seguidas del nodo actual: 1 al llegar por un flujo, +1 en cada vuelta de un bucle estándar. */
	private int loopCounter;

	/** Flujo de salida que seleccionó el último gateway; se usa para comprobar el siguiente avance. */
	private Optional<String> selectedSequenceFlowId;

	/** Mensaje que inició la instancia (evento de inicio de mensaje). */
	private Optional<ExchangedMessage> triggeringMessage;

	/** Mensaje que publicó la instancia en su evento de fin. */
	private Optional<ExchangedMessage> publishedMessage;
	private Optional<Instant> endedAt;

	private ProcessInstance(ProcessInstanceRecord stored) {
		this.id = stored.id();
		this.processId = stored.processId();
		this.processVersion = stored.processVersion();
		this.correlationKey = stored.correlationKey();
		this.startedAt = stored.startedAt();
		this.version = stored.version();
		this.businessKey = stored.businessKey();
		this.state = stored.state();
		this.currentFlowNodeId = stored.currentFlowNodeId();
		this.loopCounter = stored.loopCounter();
		this.selectedSequenceFlowId = stored.selectedSequenceFlowId();
		this.triggeringMessage = stored.triggeringMessage();
		this.publishedMessage = stored.publishedMessage();
		this.endedAt = stored.endedAt();
	}

	/**
	 * Inicia una instancia en el evento de inicio de la definición.
	 *
	 * @param definition proceso que ejecutará
	 * @param correlationKey correlación de negocio
	 * @param trigger mensaje que la inicia, si empieza por un mensaje
	 * @param now momento de inicio
	 * @return la instancia activa, aún sin guardar
	 */
	static ProcessInstance start(ProcessDefinition definition, CorrelationKey correlationKey,
			Optional<ExchangedMessage> trigger, Instant now) {
		return new ProcessInstance(new ProcessInstanceRecord(UUID.randomUUID(), definition.id(), definition.version(),
				correlationKey, Optional.empty(), ProcessInstanceState.ACTIVE, definition.startEvent().id(), 1,
				Optional.empty(), trigger, Optional.empty(), now, Optional.empty(), 0));
	}

	/**
	 * Carga una instancia guardada.
	 *
	 * @param stored registro del almacén
	 * @return la instancia
	 */
	static ProcessInstance of(ProcessInstanceRecord stored) {
		return new ProcessInstance(stored);
	}

	/**
	 * Estado que hay que guardar, con la versión que se leyó.
	 *
	 * @return el registro para el almacén
	 */
	ProcessInstanceRecord toRecord() {
		return new ProcessInstanceRecord(id, processId, processVersion, correlationKey, businessKey, state,
				currentFlowNodeId, loopCounter, selectedSequenceFlowId, triggeringMessage, publishedMessage, startedAt,
				endedAt, version);
	}

	/**
	 * Comprueba que la instancia puede avanzar hasta un nodo y lo devuelve.
	 *
	 * @param definition definición desplegada del proceso
	 * @param targetRef nodo al que se quiere avanzar
	 * @return el nodo destino
	 * @throws ProcessConformanceException si la instancia está completada, ejecuta otra versión, el nodo no existe, no
	 *         hay flujo de secuencia declarado o el gateway seleccionó otro flujo
	 */
	FlowNode checkAdvance(ProcessDefinition definition, String targetRef) {
		if (state == ProcessInstanceState.COMPLETED) {
			throw new ProcessConformanceException("Process instance " + id + " is already completed");
		}
		if (definition.version() != processVersion) {
			throw new ProcessConformanceException("Process instance " + id + " runs " + processId + " v"
					+ processVersion + " and cannot be checked against v" + definition.version());
		}
		var target = definition.flowNode(targetRef).orElseThrow(() -> new ProcessConformanceException(
				targetRef + " is not a flow node of " + processId + " v" + processVersion));
		if (targetRef.equals(currentFlowNodeId)) {
			return checkIteration(target);
		}
		var flow = definition.sequenceFlow(currentFlowNodeId, targetRef).orElseThrow(() -> new ProcessConformanceException(
				"No sequence flow " + currentFlowNodeId + " → " + targetRef + " is declared in " + processId));
		switch (flow) {
			case SequenceFlow.Unconditional unconditional -> {
				// Siempre se puede seguir.
			}
			case SequenceFlow.Conditional conditional -> {
				if (!selectedSequenceFlowId.equals(Optional.of(conditional.id()))) {
					throw new ProcessConformanceException("Gateway " + currentFlowNodeId + " selected "
							+ selectedSequenceFlowId.orElse("no sequence flow") + ", not the sequence flow to " + targetRef);
				}
			}
		}
		return target;
	}

	/**
	 * Comprueba otra vuelta del nodo actual: solo una tarea con bucle estándar se repite sin flujo, y solo hasta su máximo.
	 */
	private FlowNode checkIteration(FlowNode current) {
		var loop = current instanceof FlowNode.Task task ? task.loopCharacteristics() : Optional.<StandardLoopCharacteristics> empty();
		if (loop.isEmpty()) {
			throw new ProcessConformanceException(current.id() + " has no standard loop and cannot be repeated without a sequence flow");
		}
		if (!loop.get().allowsAnotherIteration(loopCounter)) {
			throw new ProcessConformanceException(current.id() + " already ran " + loopCounter
					+ " iterations, its loopMaximum");
		}
		return current;
	}

	/** Deja el token en una actividad ya comprobada con {@link #checkAdvance}: otra vuelta si ya estaba en ella. */
	void enter(FlowNode.Activity activity) {
		this.loopCounter = activity.id().equals(currentFlowNodeId) ? loopCounter + 1 : 1;
		this.currentFlowNodeId = activity.id();
		this.selectedSequenceFlowId = Optional.empty();
	}

	/** Deja el token en un gateway ya comprobado, recordando el flujo de salida que selecciona. */
	void select(ExclusiveGateway gateway, SequenceFlow.Conditional selected) {
		this.loopCounter = 1;
		this.currentFlowNodeId = gateway.id();
		this.selectedSequenceFlowId = Optional.of(selected.id());
	}

	/** Completa la instancia en un evento de fin ya comprobado. */
	void complete(EndEvent end, Instant now) {
		this.loopCounter = 1;
		this.currentFlowNodeId = end.id();
		this.selectedSequenceFlowId = Optional.empty();
		this.state = ProcessInstanceState.COMPLETED;
		this.endedAt = Optional.of(now);
	}

	/** Registra el mensaje que publica la instancia al terminar; se llama después de {@link #complete}. */
	void published(MessageVersionRef message, MessageId messageId) {
		this.publishedMessage = Optional.of(new ExchangedMessage(messageId, message, ref(), id, endedAt.orElseThrow()));
	}

	void setBusinessKey(String businessKey) {
		this.businessKey = Optional.ofNullable(businessKey);
	}

	UUID id() {
		return id;
	}

	String processId() {
		return processId;
	}

	ProcessDefinitionRef ref() {
		return ProcessDefinitionRef.of(processId, processVersion);
	}

	int processVersion() {
		return processVersion;
	}

	/** Iteración del nodo actual (el {@code loopCounter} de BPMN). */
	int loopCounter() {
		return loopCounter;
	}

	CorrelationKey correlationKey() {
		return correlationKey;
	}

	Optional<String> businessKey() {
		return businessKey;
	}

	ProcessInstanceRef reference() {
		return new ProcessInstanceRef(id, processId, correlationKey);
	}

	/**
	 * Nodos a los que {@link #checkAdvance} permitiría avanzar ahora, con el mismo criterio.
	 *
	 * @param definition definición de la versión de la instancia
	 * @return el destino del flujo seleccionado por un gateway; si no, los destinos de los flujos de salida y, si el nodo
	 *         actual es una tarea cuyo bucle admite otra vuelta, él mismo; vacío si la instancia está completada
	 */
	List<String> nextFlowNodeIds(ProcessDefinition definition) {
		if (state == ProcessInstanceState.COMPLETED) {
			return List.of();
		}
		if (selectedSequenceFlowId.isPresent()) {
			return definition.sequenceFlows().stream().filter(flow -> flow.id().equals(selectedSequenceFlowId.get()))
					.map(SequenceFlow::targetRef).toList();
		}
		var next = new ArrayList<String>();
		definition.flowNode(currentFlowNodeId)
				.filter(node -> node instanceof FlowNode.Task task && task.loopCharacteristics()
						.map(loop -> loop.allowsAnotherIteration(loopCounter)).orElse(false))
				.ifPresent(node -> next.add(node.id()));
		definition.outgoing(currentFlowNodeId).forEach(flow -> next.add(flow.targetRef()));
		return List.copyOf(next);
	}

	/**
	 * Construye el modelo de lectura de la instancia.
	 *
	 * @param definition definición de la versión de la instancia, o vacía si esa versión ya no está desplegada: la
	 *        instancia se sigue mostrando, pero sin nodos a los que pueda avanzar
	 * @param flowNodeNames nombre canónico de cada nodo de flujo
	 * @return la instancia activa o completada
	 */
	ProcessInstanceSummary toSummary(Optional<ProcessDefinition> definition, Function<String, String> flowNodeNames) {
		var flowNodeName = flowNodeNames.apply(currentFlowNodeId);
		return switch (state) {
			case ACTIVE -> new ProcessInstanceSummary.Active(id, processId, processVersion, correlationKey,
					businessKey, currentFlowNodeId, flowNodeName, startedAt, triggeringMessage,
					definition.map(this::nextFlowNodeIds).orElse(List.of()));
			case COMPLETED -> new ProcessInstanceSummary.Completed(id, processId, processVersion, correlationKey,
					businessKey, currentFlowNodeId, flowNodeName, startedAt, triggeringMessage, endedAt.orElseThrow(),
					publishedMessage);
		};
	}
}
