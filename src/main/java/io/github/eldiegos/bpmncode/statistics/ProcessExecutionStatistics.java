package io.github.eldiegos.bpmncode.statistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import io.github.eldiegos.bpmncode.runtime.HistoricFlowNodeInstance;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceSummary;

/**
 * Estadísticas de ejecución sobre la evidencia del journal.
 * <p>
 * Responsabilidad: calcular el mapa de calor de una versión de un proceso, el camino seguido por una instancia, las
 * instancias que esperan en cada nodo y el recuento de instancias por versión, para que cualquier aplicación pueda
 * presentarlos sin recorrer la evidencia por su cuenta.
 * <p>
 * Invariantes: solo lee; una versión se calcula con dos consultas al almacén (sus instancias y toda su evidencia), nunca
 * con una por instancia; un paso entre dos nodos consecutivos de la evidencia cuenta como recorrido de un flujo solo si
 * el modelo declara ese flujo (otra vuelta de un bucle estándar no recorre ninguno); devuelve identificadores y cifras,
 * sin textos.
 *
 * @see ExecutionHeatmap
 * @see InstancePath
 */
@Transactional(readOnly = true)
public class ProcessExecutionStatistics {

	private final ProcessDefinitionRegistry registry;
	private final ProcessInstanceHistory history;

	/**
	 * Crea el servicio.
	 *
	 * @param registry registro de definiciones
	 * @param history historial de instancias
	 */
	public ProcessExecutionStatistics(ProcessDefinitionRegistry registry, ProcessInstanceHistory history) {
		this.registry = registry;
		this.history = history;
	}

	/**
	 * Calcula el mapa de calor de una versión desplegada de un proceso.
	 *
	 * @param process proceso y versión
	 * @return el mapa, o vacío si esa versión no está desplegada
	 */
	public Optional<ExecutionHeatmap> heatmap(ProcessDefinitionRef process) {
		return registry.definitions().process(process).map(this::heatmap);
	}

	private ExecutionHeatmap heatmap(ProcessDefinition process) {
		var instances = history.instances(process.ref());
		var evidence = history.flowNodeInstances(process.ref());
		Map<String, Long> executions = new HashMap<>();
		Map<String, Set<UUID>> visitors = new HashMap<>();
		Map<String, Long> traversals = new HashMap<>();
		for (var entry : evidence.entrySet()) {
			String previous = null;
			for (var step : entry.getValue()) {
				var nodeId = step.flowNodeId();
				executions.merge(nodeId, 1L, Long::sum);
				visitors.computeIfAbsent(nodeId, id -> new HashSet<>()).add(entry.getKey());
				if (previous != null) {
					process.sequenceFlow(previous, nodeId).map(SequenceFlow::id)
							.ifPresent(flowId -> traversals.merge(flowId, 1L, Long::sum));
				}
				previous = nodeId;
			}
		}
		var waiting = waiting(instances);
		var total = instances.size();
		var running = (int) instances.stream().filter(ProcessInstanceSummary.Active.class::isInstance).count();
		var nodes = process.flowNodes().stream().map(node -> {
			var visited = visitors.getOrDefault(node.id(), Set.of()).size();
			return new ExecutionHeatmap.NodeStatistics(node.id(), executions.getOrDefault(node.id(), 0L), visited,
					total == 0 ? 0 : Math.round(100f * visited / total), waiting.getOrDefault(node.id(), 0L));
		}).toList();
		var flows = process.sequenceFlows().stream()
				.map(flow -> new ExecutionHeatmap.FlowStatistics(flow.id(), traversals.getOrDefault(flow.id(), 0L))).toList();
		var max = nodes.stream().mapToLong(ExecutionHeatmap.NodeStatistics::executions).max().orElse(0);
		return new ExecutionHeatmap(process.ref(), total, running, total - running, max, nodes, flows);
	}

	/**
	 * Calcula el camino seguido por una instancia.
	 *
	 * @param instanceId instancia
	 * @return el camino, o vacío si la instancia no existe
	 */
	public Optional<InstancePath> path(UUID instanceId) {
		return history.instance(instanceId).map(instance -> {
			var ref = ProcessDefinitionRef.of(instance.processId(), instance.processVersion());
			var process = registry.definitions().process(ref);
			var steps = new ArrayList<InstancePath.PathStep>();
			var flows = new ArrayList<String>();
			String previous = null;
			for (HistoricFlowNodeInstance evidence : history.flowNodeInstances(instanceId)) {
				steps.add(new InstancePath.PathStep(steps.size() + 1, evidence.flowNodeId(), evidence.loopCounter(),
						evidence.occurredAt()));
				var from = previous;
				process.flatMap(definition -> from == null ? Optional.empty()
						: definition.sequenceFlow(from, evidence.flowNodeId())).map(SequenceFlow::id).ifPresent(flows::add);
				previous = evidence.flowNodeId();
			}
			var waiting = switch (instance) {
				case ProcessInstanceSummary.Active active -> Optional.of(active.waitingFlowNodeId());
				case ProcessInstanceSummary.Completed completed -> Optional.<String> empty();
			};
			return new InstancePath(instanceId, ref, instance instanceof ProcessInstanceSummary.Active, steps, flows, waiting);
		});
	}

	/**
	 * Cuenta las instancias activas que esperan en cada nodo de una versión.
	 *
	 * @param process proceso y versión
	 * @return instancias en espera por nodo de flujo; los nodos sin ninguna no aparecen
	 */
	public Map<String, Long> waitingByNode(ProcessDefinitionRef process) {
		return waiting(history.instances(process));
	}

	/**
	 * Cuenta las instancias de cada versión de cada proceso, con una sola consulta al almacén.
	 *
	 * @return activas y completadas por versión, ordenadas por proceso y versión; las versiones sin instancias no
	 *         aparecen (usa {@link InstanceCounts#NONE})
	 */
	public Map<ProcessDefinitionRef, InstanceCounts> countsByVersion() {
		var counts = new TreeMap<ProcessDefinitionRef, InstanceCounts>();
		for (var instance : history.instances()) {
			var ref = ProcessDefinitionRef.of(instance.processId(), instance.processVersion());
			var active = instance instanceof ProcessInstanceSummary.Active;
			counts.merge(ref, new InstanceCounts(active ? 1 : 0, active ? 0 : 1),
					(left, right) -> new InstanceCounts(left.running() + right.running(), left.completed() + right.completed()));
		}
		return counts;
	}

	private static Map<String, Long> waiting(List<ProcessInstanceSummary> instances) {
		Map<String, Long> waiting = new HashMap<>();
		instances.stream().filter(ProcessInstanceSummary.Active.class::isInstance)
				.map(ProcessInstanceSummary.Active.class::cast)
				.forEach(active -> waiting.merge(active.waitingFlowNodeId(), 1L, Long::sum));
		return waiting;
	}
}
