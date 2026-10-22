package io.github.eldiegos.bpmncode.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Almacén de instancias en memoria: no necesita base de datos.
 * <p>
 * Responsabilidad: guardar instancias y evidencia en estructuras del proceso Java, para aplicaciones sin base de datos,
 * pruebas o demostraciones. Es el almacén por defecto cuando la aplicación no tiene un {@code DataSource}.
 * <p>
 * Invariantes: seguro entre hilos (cada operación es atómica); aplica las mismas reglas que cualquier almacén (una
 * instancia por proceso y correlación, bloqueo optimista, evidencia solo añadida). No es transaccional: lo guardado no
 * se deshace si falla la transacción de quien llama, y todo se pierde al parar la aplicación.
 *
 * @see ProcessInstanceStore
 */
public final class InMemoryProcessInstanceStore implements ProcessInstanceStore {

	private final Map<UUID, ProcessInstanceRecord> instances = new HashMap<>();
	private final Map<UUID, List<FlowNodeInstanceRecord>> flowNodeInstances = new HashMap<>();

	@Override
	public synchronized void insert(ProcessInstanceRecord instance) {
		if (instance(instance.processId(), instance.correlationKey()).isPresent()) {
			throw new DuplicateProcessInstanceException(instance.processId(), instance.correlationKey());
		}
		instances.put(instance.id(), instance);
	}

	@Override
	public synchronized void update(ProcessInstanceRecord instance) {
		var stored = instances.get(instance.id());
		if (stored == null || stored.version() != instance.version()) {
			throw new ProcessInstanceConflictException(instance.id(), instance.version());
		}
		instances.put(instance.id(), instance.withVersion(instance.version() + 1));
	}

	@Override
	public synchronized Optional<ProcessInstanceRecord> instance(UUID instanceId) {
		return Optional.ofNullable(instances.get(instanceId));
	}

	@Override
	public synchronized Optional<ProcessInstanceRecord> instance(String processId, CorrelationKey correlationKey) {
		return instances.values().stream()
				.filter(instance -> instance.processId().equals(processId) && instance.correlationKey().equals(correlationKey))
				.findFirst();
	}

	@Override
	public synchronized List<ProcessInstanceRecord> instances() {
		return List.copyOf(instances.values());
	}

	@Override
	public synchronized List<ProcessInstanceRecord> instances(CorrelationKey correlationKey) {
		return instances.values().stream().filter(instance -> instance.correlationKey().equals(correlationKey)).toList();
	}

	@Override
	public synchronized List<ProcessInstanceRecord> instances(ProcessDefinitionRef process) {
		return instances.values().stream().filter(instance -> instance.ref().equals(process)).toList();
	}

	@Override
	public synchronized void append(FlowNodeInstanceRecord flowNodeInstance) {
		var recorded = flowNodeInstances.computeIfAbsent(flowNodeInstance.processInstanceId(), id -> new ArrayList<>());
		if (recorded.stream().anyMatch(record -> record.sequence() == flowNodeInstance.sequence())) {
			throw ProcessInstanceConflictException.sequenceTaken(flowNodeInstance.processInstanceId(),
					flowNodeInstance.sequence());
		}
		recorded.add(flowNodeInstance);
	}

	@Override
	public synchronized List<FlowNodeInstanceRecord> flowNodeInstances(UUID processInstanceId) {
		return List.copyOf(flowNodeInstances.getOrDefault(processInstanceId, List.of()));
	}

	@Override
	public synchronized List<FlowNodeInstanceRecord> flowNodeInstances(ProcessDefinitionRef process) {
		return flowNodeInstances.values().stream().flatMap(List::stream)
				.filter(record -> record.processId().equals(process.processId()) && record.processVersion() == process.version())
				.toList();
	}

	@Override
	public synchronized int flowNodeInstanceCount(UUID processInstanceId) {
		return flowNodeInstances.getOrDefault(processInstanceId, List.of()).size();
	}
}
