package io.github.eldiegos.bpmncode.runtime;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.FlowNode.Activity;
import io.github.eldiegos.bpmncode.model.FlowNode.EndEvent;
import io.github.eldiegos.bpmncode.model.FlowNode.ExclusiveGateway;
import io.github.eldiegos.bpmncode.model.FlowNode.StartEvent;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.SequenceFlow;

/**
 * Registro de la ejecución de las instancias de proceso frente a sus definiciones anotadas.
 * <p>
 * Responsabilidad: iniciar instancias, completar sus actividades, seleccionar el flujo de salida de sus gateways y
 * llevarlas a un evento de fin, guardando en el {@link ProcessInstanceStore} el estado de cada instancia y la evidencia de
 * cada nodo de flujo ({@link FlowNodeInstanceRecord}). Las consultas son responsabilidad de {@link ProcessInstanceHistory}.
 * <p>
 * Invariantes: cada avance se comprueba contra el {@link ProcessDefinitionRegistry} (conformidad en ejecución) y cada
 * operación solo acepta el tipo de nodo que le corresponde y no guarda nada si la comprobación falla; si la aplicación
 * gestiona transacciones (Spring Transaction), el journal se une a la de quien lo llama, de modo que con un almacén
 * transaccional evidencias y datos de negocio se confirman o deshacen juntos.
 *
 * @see ProcessInstance#checkAdvance(ProcessDefinition, String)
 */
@Transactional
public class ProcessJournal {

	private static final Logger log = LoggerFactory.getLogger(ProcessJournal.class);

	private final ProcessDefinitionRegistry registry;
	private final ProcessInstanceStore store;
	private final Clock clock;

	ProcessJournal(ProcessDefinitionRegistry registry, ProcessInstanceStore store, Clock clock) {
		this.registry = registry;
		this.store = store;
		this.clock = clock;
	}

	/**
	 * Inicia una instancia de una versión concreta del proceso en su evento de inicio. Cada versión tiene su propio
	 * contrato de inicio, así que quien inicia el proceso decide la versión; la instancia queda ligada a ella hasta el
	 * final aunque se despliegue otra más nueva.
	 *
	 * @param ref proceso y versión que se inician
	 * @param correlationKey correlación de negocio; hay como mucho una instancia por proceso y correlación
	 * @param outcome resultado que se registra para el evento de inicio
	 * @return la referencia a la instancia
	 */
	public ProcessInstanceRef startProcessInstance(ProcessDefinitionRef ref, CorrelationKey correlationKey,
			FlowNodeOutcome outcome) {
		var definition = registry.processDefinition(ref);
		definition.startEvent().messageDefinition().ifPresent(message -> {
			throw new ProcessConformanceException(ref + " starts with the message " + message.messageRef()
					+ ": start it with startProcessInstance(ref, message, outcome)");
		});
		return start(definition, correlationKey, Optional.empty(), outcome).reference();
	}

	/**
	 * Inicia una instancia de una versión concreta del proceso con el mensaje que la dispara. La instancia hereda la
	 * correlación de la cabecera del mensaje y queda registrado qué envío (y qué versión del contrato) la inició.
	 *
	 * @param ref proceso y versión que se inician
	 * @param trigger mensaje recibido; hay como mucho una instancia por proceso y correlación
	 * @param outcome resultado que se registra para el evento de inicio
	 * @return la referencia a la instancia
	 * @throws ProcessConformanceException si el proceso no empieza por un mensaje o no acepta ese mensaje y versión
	 */
	public ProcessInstanceRef startProcessInstance(ProcessDefinitionRef ref, ProcessMessage trigger,
			FlowNodeOutcome outcome) {
		var definition = registry.processDefinition(ref);
		var accepted = definition.startEvent().messageDefinition().orElseThrow(() -> new ProcessConformanceException(
				ref + " does not start with a message"));
		var version = messageVersionOf(trigger);
		if (!version.messageId().equals(accepted.messageRef()) || !accepted.accepts(version.version())) {
			throw new ProcessConformanceException(ref + " accepts " + accepted.messageRef() + " versions "
					+ accepted.versions() + ", not " + version);
		}
		var header = trigger.header();
		var instance = start(definition, header.correlationKey(), Optional.of(ExchangedMessage.of(version, header)),
				outcome);
		log.info("Process instance {} of {} started by {} {} from instance {}", instance.id(), ref, version,
				header.messageId(), header.sourceInstanceId());
		return instance.reference();
	}

	/**
	 * Inicia una instancia con el mensaje que la dispara y registra como resultado del evento de inicio
	 * {@link FlowNodeOutcome#messageReceived(MessageVersionRef) MESSAGE_RECEIVED} con la versión recibida.
	 *
	 * @param ref proceso y versión que se inician
	 * @param trigger mensaje recibido
	 * @return la referencia a la instancia
	 * @throws ProcessConformanceException si el proceso no empieza por un mensaje o no acepta ese mensaje y versión
	 */
	public ProcessInstanceRef startProcessInstance(ProcessDefinitionRef ref, ProcessMessage trigger) {
		return startProcessInstance(ref, trigger, FlowNodeOutcome.messageReceived(messageVersionOf(trigger)));
	}

	/**
	 * Inicia una instancia salvo que ya exista una del mismo proceso con la misma correlación (una entrega repetida del
	 * mensaje, un reintento): así un inicio por mensaje es idempotente sin que quien lo recibe tenga que comprobarlo.
	 *
	 * @param ref proceso y versión que se inician
	 * @param trigger mensaje recibido
	 * @param outcome resultado que se registra para el evento de inicio si se inicia
	 * @return la instancia nueva ({@link ProcessStart.Started}) o la que ya existía ({@link ProcessStart.AlreadyStarted})
	 * @throws ProcessConformanceException si el proceso no empieza por un mensaje o no acepta ese mensaje y versión
	 * @throws DuplicateProcessInstanceException si otra transacción la inicia a la vez (la restricción única del almacén
	 *         sigue siendo la última garantía)
	 */
	public ProcessStart startProcessInstanceIfAbsent(ProcessDefinitionRef ref, ProcessMessage trigger,
			FlowNodeOutcome outcome) {
		var correlationKey = trigger.header().correlationKey();
		var existing = store.instance(ref.processId(), correlationKey);
		if (existing.isPresent()) {
			log.info("Process instance of {} for correlation {} already exists: message {} ignored", ref.processId(),
					correlationKey, trigger.header().messageId());
			return new ProcessStart.AlreadyStarted(ProcessInstance.of(existing.get()).reference());
		}
		return new ProcessStart.Started(startProcessInstance(ref, trigger, outcome));
	}

	/**
	 * Inicia una instancia salvo que ya exista una del mismo proceso con la misma correlación.
	 *
	 * @param ref proceso y versión que se inician
	 * @param correlationKey correlación de negocio
	 * @param outcome resultado que se registra para el evento de inicio si se inicia
	 * @return la instancia nueva ({@link ProcessStart.Started}) o la que ya existía ({@link ProcessStart.AlreadyStarted})
	 * @throws ProcessConformanceException si el proceso empieza por un mensaje
	 */
	public ProcessStart startProcessInstanceIfAbsent(ProcessDefinitionRef ref, CorrelationKey correlationKey,
			FlowNodeOutcome outcome) {
		return store.instance(ref.processId(), correlationKey)
				.<ProcessStart> map(existing -> new ProcessStart.AlreadyStarted(ProcessInstance.of(existing).reference()))
				.orElseGet(() -> new ProcessStart.Started(startProcessInstance(ref, correlationKey, outcome)));
	}

	private ProcessInstance start(ProcessDefinition definition, CorrelationKey correlationKey,
			Optional<ExchangedMessage> trigger, FlowNodeOutcome outcome) {
		var instance = ProcessInstance.start(definition, correlationKey, trigger, clock.instant());
		store.insert(instance.toRecord());
		StartEvent start = definition.startEvent();
		append(instance, start, outcome);
		log.info("Process instance {} of {} started (correlation {})", instance.id(), definition.ref(), correlationKey);
		return instance;
	}

	/**
	 * Prepara la cabecera del mensaje que publicará una instancia: nuevo identificador de envío, la correlación de la
	 * instancia, el momento actual y la instancia como origen.
	 *
	 * @param reference instancia que publica
	 * @return la cabecera
	 */
	@Transactional(readOnly = true)
	public MessageHeader messageHeader(ProcessInstanceRef reference) {
		var instance = load(reference);
		return new MessageHeader(MessageId.newId(), instance.correlationKey(), clock.instant(), instance.ref(), instance.id());
	}

	/**
	 * Recupera una instancia para continuarla.
	 *
	 * @param processId proceso
	 * @param correlationKey correlación de negocio
	 * @return la referencia, o vacío si no existe
	 */
	@Transactional(readOnly = true)
	public Optional<ProcessInstanceRef> resume(String processId, CorrelationKey correlationKey) {
		return store.instance(processId, correlationKey).map(ProcessInstance::of).map(ProcessInstance::reference);
	}

	/**
	 * Recupera una instancia que tiene que existir para continuarla, p. ej. al completar una tarea humana.
	 *
	 * @param processId proceso
	 * @param correlationKey correlación de negocio
	 * @return la referencia
	 * @throws ProcessConformanceException si no hay ninguna instancia de ese proceso con esa correlación
	 */
	@Transactional(readOnly = true)
	public ProcessInstanceRef require(String processId, CorrelationKey correlationKey) {
		return resume(processId, correlationKey).orElseThrow(() -> new ProcessConformanceException(
				"No " + processId + " instance for correlation " + correlationKey));
	}

	/**
	 * Asocia la instancia a su sujeto de negocio.
	 *
	 * @param reference instancia
	 * @param businessKey clave seudónima, p. ej. {@code person:<uuid>}
	 */
	public void setBusinessKey(ProcessInstanceRef reference, String businessKey) {
		var instance = load(reference);
		instance.setBusinessKey(businessKey);
		store.update(instance.toRecord());
	}

	/**
	 * Registra que se ha completado una actividad. Si la actividad es una tarea con bucle estándar y el token ya está en ella,
	 * registra otra iteración.
	 *
	 * @param reference instancia
	 * @param activityId actividad completada
	 * @param outcome resultado
	 * @return la iteración registrada ({@code loopCounter}): 1 al llegar a la actividad, 2 en la segunda vuelta…
	 * @throws ProcessConformanceException si el nodo no es una actividad, no hay flujo hasta él o se supera el
	 *         {@code loopMaximum} de su bucle
	 */
	public int completeActivity(ProcessInstanceRef reference, String activityId, FlowNodeOutcome outcome) {
		var instance = load(reference);
		switch (instance.checkAdvance(definitionOf(instance), activityId)) {
			case Activity activity -> {
				activity.publishedMessage().ifPresent(message -> {
					throw new ProcessConformanceException(activityId + " sends " + message
							+ ": complete it with completeActivity(instance, sendTaskId, message)");
				});
				instance.enter(activity);
				save(instance, activity, outcome);
				return instance.loopCounter();
			}
			case StartEvent start -> throw notA("an activity", start);
			case EndEvent end -> throw notA("an activity", end);
			case ExclusiveGateway gateway -> throw notA("an activity", gateway);
		}
	}

	/**
	 * Completa una tarea de envío y registra el mensaje que envía; quien llama lo publica después (p. ej. con
	 * {@code ApplicationEventPublisher}) en la misma transacción, y la instancia sigue su flujo. Registra
	 * {@link FlowNodeOutcome#MESSAGE_PUBLISHED MESSAGE_PUBLISHED} con la versión y el identificador del envío.
	 *
	 * @param reference instancia
	 * @param sendTaskId tarea de envío
	 * @param sent mensaje enviado, con la cabecera de {@link #messageHeader(ProcessInstanceRef)}
	 * @return la iteración registrada ({@code loopCounter}): 1 al llegar a la tarea, 2 en la segunda vuelta…
	 * @throws ProcessConformanceException si el nodo no es una tarea de envío, no hay flujo hasta él, no envía ese mensaje
	 *         y versión, la cabecera no es de esta instancia o se supera el {@code loopMaximum} de su bucle
	 */
	public int completeActivity(ProcessInstanceRef reference, String sendTaskId, ProcessMessage sent) {
		var instance = load(reference);
		switch (instance.checkAdvance(definitionOf(instance), sendTaskId)) {
			case Activity activity -> {
				var declared = activity.publishedMessage().orElseThrow(() -> new ProcessConformanceException(sendTaskId
						+ " sends no message: complete it with completeActivity(instance, activityId, outcome)"));
				var version = messageVersionOf(sent);
				if (!version.equals(declared)) {
					throw new ProcessConformanceException(sendTaskId + " sends " + declared + ", not " + version);
				}
				var header = sent.header();
				if (!header.sourceInstanceId().equals(instance.id()) || !header.correlationKey().equals(instance.correlationKey())) {
					throw new ProcessConformanceException("The header of " + version + " belongs to another instance: use"
							+ " messageHeader(instance)");
				}
				instance.enter(activity);
				save(instance, activity, FlowNodeOutcome.of(FlowNodeOutcome.MESSAGE_PUBLISHED, version + " "
						+ header.messageId()));
				log.info("Process instance {} of {} sent {} {} at {}", instance.id(), instance.processId(), version,
						header.messageId(), sendTaskId);
				return instance.loopCounter();
			}
			case StartEvent start -> throw notA("a send task", start);
			case EndEvent end -> throw notA("a send task", end);
			case ExclusiveGateway gateway -> throw notA("a send task", gateway);
		}
	}

	/**
	 * Registra el flujo de salida que selecciona un gateway exclusivo; después solo puede seguir ese flujo.
	 *
	 * @param reference instancia
	 * @param gatewayId gateway
	 * @param result constante devuelta por el método del gateway
	 * @param <E> tipo del {@code enum} del gateway
	 * @return la misma constante, para que el método del gateway pueda terminar con
	 *         {@code return journal.selectSequenceFlow(…)}
	 * @throws ProcessConformanceException si el nodo no es un gateway, no hay flujo hasta él o no declara esa rama
	 */
	public <E extends Enum<E>> E selectSequenceFlow(ProcessInstanceRef reference, String gatewayId, E result) {
		var instance = load(reference);
		var definition = definitionOf(instance);
		switch (instance.checkAdvance(definition, gatewayId)) {
			case ExclusiveGateway gateway -> {
				var selected = definition.outgoing(gatewayId).stream()
						.filter(SequenceFlow.Conditional.class::isInstance)
						.map(SequenceFlow.Conditional.class::cast)
						.filter(flow -> flow.when().equals(result.name()))
						.findFirst()
						.orElseThrow(() -> new ProcessConformanceException(gatewayId + " declares no outgoing sequence flow for "
								+ result.name()));
				instance.select(gateway, selected);
				save(instance, gateway, FlowNodeOutcome.of(FlowNodeOutcome.SEQUENCE_FLOW_SELECTED, result.name() + " → "
						+ selected.targetRef()));
			}
			case StartEvent start -> throw notA("an exclusive gateway", start);
			case EndEvent end -> throw notA("an exclusive gateway", end);
			case Activity activity -> throw notA("an exclusive gateway", activity);
		}
		return result;
	}

	/**
	 * Lleva la instancia a un evento de fin, que es su resultado de negocio, y la completa.
	 *
	 * @param reference instancia
	 * @param endEventId evento de fin
	 * @param outcome resultado
	 * @throws ProcessConformanceException si el nodo no es un evento de fin o no hay flujo hasta él
	 */
	public void reachEndEvent(ProcessInstanceRef reference, String endEventId, FlowNodeOutcome outcome) {
		var instance = load(reference);
		switch (instance.checkAdvance(definitionOf(instance), endEventId)) {
			case EndEvent end -> {
				end.messageDefinition().ifPresent(message -> {
					throw new ProcessConformanceException(endEventId + " publishes " + message.messageRef()
							+ ": reach it with reachEndEvent(instance, endEventId, message)");
				});
				end.error().ifPresent(error -> {
					throw new ProcessConformanceException(endEventId + " ends with the error " + error.errorRef()
							+ ": reach it with reachEndEvent(instance, endEventId, error)");
				});
				instance.complete(end, clock.instant());
				save(instance, end, outcome);
				log.info("Process instance {} of {} completed at {}", instance.id(), instance.processId(), endEventId);
			}
			case StartEvent start -> throw notA("an end event", start);
			case Activity activity -> throw notA("an end event", activity);
			case ExclusiveGateway gateway -> throw notA("an end event", gateway);
		}
	}

	/**
	 * Lleva la instancia a un evento de fin de mensaje y registra el mensaje que publica; quien llama lo publica después
	 * (p. ej. con {@code ApplicationEventPublisher}) en la misma transacción.
	 *
	 * @param reference instancia
	 * @param endEventId evento de fin
	 * @param published mensaje publicado, con la cabecera de {@link #messageHeader(ProcessInstanceRef)}
	 * @throws ProcessConformanceException si el nodo no es un evento de fin, no hay flujo hasta él, no publica ese mensaje y
	 *         versión o la cabecera no es de esta instancia
	 */
	public void reachEndEvent(ProcessInstanceRef reference, String endEventId, ProcessMessage published) {
		var instance = load(reference);
		switch (instance.checkAdvance(definitionOf(instance), endEventId)) {
			case EndEvent end -> {
				var declared = end.messageDefinition().orElseThrow(() -> new ProcessConformanceException(endEventId
						+ " publishes no message"));
				var version = messageVersionOf(published);
				if (!version.messageId().equals(declared.messageRef()) || version.version() != declared.publishedVersion()) {
					throw new ProcessConformanceException(endEventId + " publishes " + declared.messageRef() + "@"
							+ declared.publishedVersion() + ", not " + version);
				}
				var header = published.header();
				if (!header.sourceInstanceId().equals(instance.id()) || !header.correlationKey().equals(instance.correlationKey())) {
					throw new ProcessConformanceException("The header of " + version + " belongs to another instance: use"
							+ " messageHeader(instance)");
				}
				instance.complete(end, clock.instant());
				instance.published(version, header.messageId());
				save(instance, end, FlowNodeOutcome.of(FlowNodeOutcome.MESSAGE_PUBLISHED, version.toString()));
				log.info("Process instance {} of {} completed at {} publishing {} {}", instance.id(), instance.processId(),
						endEventId, version, header.messageId());
			}
			case StartEvent start -> throw notA("an end event", start);
			case Activity activity -> throw notA("an end event", activity);
			case ExclusiveGateway gateway -> throw notA("an end event", gateway);
		}
	}

	/**
	 * Lleva la instancia a un evento de fin de error y la completa con ese error como resultado de negocio. Registra
	 * {@link FlowNodeOutcome#ERROR_THROWN ERROR_THROWN} con el código del error.
	 * <p>
	 * El error es un desenlace del proceso, no un fallo técnico: si quien llama vuelve a lanzar la excepción dentro de la
	 * misma transacción, la evidencia se deshace con ella.
	 *
	 * @param reference instancia
	 * @param endEventId evento de fin de error
	 * @param error excepción del error declarado (o de una subclase suya)
	 * @throws ProcessConformanceException si el nodo no es un evento de fin, no hay flujo hasta él, no termina con un
	 *         error o la excepción no es la de su error
	 */
	public void reachEndEvent(ProcessInstanceRef reference, String endEventId, Throwable error) {
		var instance = load(reference);
		switch (instance.checkAdvance(definitionOf(instance), endEventId)) {
			case EndEvent end -> {
				var declared = end.error().orElseThrow(() -> new ProcessConformanceException(endEventId
						+ " does not end with an error"));
				var thrown = registry.definitions().errorOf(error.getClass())
						.orElseThrow(() -> new ProcessConformanceException(typeName(error.getClass())
								+ " is not a @ProcessError"));
				if (!thrown.id().equals(declared.errorRef())) {
					throw new ProcessConformanceException(endEventId + " ends with the error " + declared.errorRef()
							+ ", not " + thrown.id());
				}
				instance.complete(end, clock.instant());
				save(instance, end, FlowNodeOutcome.of(FlowNodeOutcome.ERROR_THROWN, thrown.errorCode()));
				log.info("Process instance {} of {} completed at {} with the error {} ({})", instance.id(),
						instance.processId(), endEventId, thrown.id(), thrown.errorCode());
			}
			case StartEvent start -> throw notA("an end event", start);
			case Activity activity -> throw notA("an end event", activity);
			case ExclusiveGateway gateway -> throw notA("an end event", gateway);
		}
	}

	private MessageVersionRef messageVersionOf(ProcessMessage message) {
		return registry.definitions().messageVersionOf(message.getClass()).orElseThrow(() -> new ProcessConformanceException(
				typeName(message.getClass()) + " is not a @MessageVersion of any declared message"));
	}

	/** Guarda el nuevo estado de una instancia ya existente y la evidencia del nodo al que ha llegado. */
	private void save(ProcessInstance instance, FlowNode node, FlowNodeOutcome outcome) {
		store.update(instance.toRecord());
		append(instance, node, outcome);
	}

	private void append(ProcessInstance instance, FlowNode node, FlowNodeOutcome outcome) {
		var sequence = store.flowNodeInstanceCount(instance.id()) + 1;
		store.append(new FlowNodeInstanceRecord(UUID.randomUUID(), instance.id(), sequence, instance.processId(),
				instance.processVersion(), instance.correlationKey(), instance.businessKey(), node.id(), node.elementType(),
				instance.loopCounter(), clock.instant(), outcome));
	}

	private ProcessInstance load(ProcessInstanceRef reference) {
		return store.instance(reference.instanceId()).map(ProcessInstance::of)
				.orElseThrow(() -> new ProcessConformanceException("Unknown process instance " + reference.instanceId()));
	}

	private ProcessDefinition definitionOf(ProcessInstance instance) {
		return registry.processDefinition(instance.ref());
	}

	/** Nombre de un tipo tal como lo escribe el código ({@code Outer.Inner}), o el binario si no tiene nombre canónico. */
	private static String typeName(Class<?> type) {
		var canonical = type.getCanonicalName();
		return canonical != null ? canonical : type.getName();
	}

	private static ProcessConformanceException notA(String expected, FlowNode node) {
		return new ProcessConformanceException(node.id() + " is not " + expected + " but a " + node.elementType().localName());
	}
}
