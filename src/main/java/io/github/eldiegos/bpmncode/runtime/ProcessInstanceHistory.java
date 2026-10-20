package io.github.eldiegos.bpmncode.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;

/**
 * Consultas sobre las instancias de proceso y su historial de nodos de flujo.
 * <p>
 * Responsabilidad: ofrecer a las vistas de gobierno el estado de las instancias y la secuencia de nodos por los que
 * pasó cada una; no modifica nada.
 * <p>
 * Invariantes: las instancias se devuelven de la más reciente a la más antigua y el historial en orden de secuencia;
 * los nombres de los nodos son los de la versión que ejecuta cada instancia.
 *
 * @see ProcessJournal
 */
@Transactional(readOnly = true)
public class ProcessInstanceHistory {

	private final ProcessDefinitionRegistry registry;
	private final ProcessInstanceStore store;

	ProcessInstanceHistory(ProcessDefinitionRegistry registry, ProcessInstanceStore store) {
		this.registry = registry;
		this.store = store;
	}

	/**
	 * Devuelve todas las instancias.
	 *
	 * @return instancias activas y completadas, de la más reciente a la más antigua
	 */
	public List<ProcessInstanceSummary> instances() {
		return store.instances().stream().sorted(Comparator.comparing(ProcessInstanceRecord::startedAt).reversed())
				.map(this::summary).toList();
	}

	/**
	 * Devuelve las instancias que comparten una correlación: la que publicó un mensaje y las que inició al recibirlo.
	 *
	 * @param correlationKey correlación de negocio
	 * @return sus instancias, de la más antigua a la más reciente; vacío si no hay ninguna
	 */
	public List<ProcessInstanceSummary> instances(CorrelationKey correlationKey) {
		return store.instances(correlationKey).stream().sorted(Comparator.comparing(ProcessInstanceRecord::startedAt))
				.map(this::summary).toList();
	}

	/**
	 * Devuelve las instancias de una versión de un proceso, con una sola consulta al almacén.
	 *
	 * @param process proceso y versión
	 * @return sus instancias activas y completadas, de la más reciente a la más antigua
	 */
	public List<ProcessInstanceSummary> instances(ProcessDefinitionRef process) {
		return store.instances(process).stream().sorted(Comparator.comparing(ProcessInstanceRecord::startedAt).reversed())
				.map(this::summary).toList();
	}

	/**
	 * Busca una instancia.
	 *
	 * @param instanceId identificador de la instancia
	 * @return su resumen, con su versión, o vacío si no existe
	 */
	public Optional<ProcessInstanceSummary> instance(UUID instanceId) {
		return store.instance(instanceId).map(this::summary);
	}

	/**
	 * Devuelve el historial de nodos de flujo de una instancia.
	 *
	 * @param instanceId identificador de la instancia
	 * @return los nodos por los que pasó, en orden; vacío si la instancia no existe
	 */
	public List<HistoricFlowNodeInstance> flowNodeInstances(UUID instanceId) {
		return store.flowNodeInstances(instanceId).stream().sorted(Comparator.comparingInt(FlowNodeInstanceRecord::sequence))
				.map(record -> record.toHistoric(flowNodeName(
						ProcessDefinitionRef.of(record.processId(), record.processVersion()), record.flowNodeId())))
				.toList();
	}

	/**
	 * Devuelve el historial de todas las instancias de una versión de un proceso, con una sola consulta al almacén.
	 *
	 * @param process proceso y versión
	 * @return el historial de cada instancia, en orden de secuencia; las instancias sin evidencia no aparecen
	 */
	public Map<UUID, List<HistoricFlowNodeInstance>> flowNodeInstances(ProcessDefinitionRef process) {
		var byInstance = new LinkedHashMap<UUID, List<HistoricFlowNodeInstance>>();
		store.flowNodeInstances(process).stream()
				.sorted(Comparator.comparing(FlowNodeInstanceRecord::processInstanceId)
						.thenComparingInt(FlowNodeInstanceRecord::sequence))
				.forEach(record -> byInstance.computeIfAbsent(record.processInstanceId(), id -> new ArrayList<>())
						.add(record.toHistoric(flowNodeName(process, record.flowNodeId()))));
		return byInstance;
	}

	/**
	 * Indica si una instancia espera en un nodo de flujo: si la conformidad permitiría registrarlo ahora. Sirve para
	 * comprobar el turno de una tarea humana sin guardar un estado propio.
	 *
	 * @param instanceId instancia
	 * @param flowNodeId nodo de flujo
	 * @return {@code true} si la instancia está activa y ese nodo está entre los que admite ahora
	 */
	public boolean isWaitingAt(UUID instanceId, String flowNodeId) {
		return instance(instanceId).filter(ProcessInstanceSummary.Active.class::isInstance)
				.map(ProcessInstanceSummary.Active.class::cast)
				.map(active -> active.nextFlowNodeIds().contains(flowNodeId)).orElse(false);
	}

	private ProcessInstanceSummary summary(ProcessInstanceRecord stored) {
		var instance = ProcessInstance.of(stored);
		return instance.toSummary(registry.processDefinition(instance.ref()),
				flowNodeId -> flowNodeName(instance.ref(), flowNodeId));
	}

	private String flowNodeName(ProcessDefinitionRef ref, String flowNodeId) {
		return registry.definitions().process(ref).flatMap(process -> process.flowNode(flowNodeId)).map(FlowNode::name)
				.orElse(flowNodeId);
	}
}
