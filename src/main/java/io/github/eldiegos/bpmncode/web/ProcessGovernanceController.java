package io.github.eldiegos.bpmncode.web;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.eldiegos.bpmncode.model.Definitions;
import io.github.eldiegos.bpmncode.model.Message;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.runtime.HistoricFlowNodeInstance;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceSummary;
import io.github.eldiegos.bpmncode.trace.TraceabilityEntry;
import io.github.eldiegos.bpmncode.trace.TraceabilityReport;

/**
 * API técnica de gobierno, de solo lectura: definiciones, contratos de mensaje, trazabilidad, instancias y evidencias.
 * <p>
 * Responsabilidad: publicar para herramientas el modelo y el histórico en {@code <base-path>/api/processes}.
 * <p>
 * Invariantes: solo lee; devuelve identificadores, nombres canónicos y códigos, sin traducir.
 *
 * @see ProcessWebAutoConfiguration
 */
@RestController
@RequestMapping("${pac.process.web.base-path:" + ProcessWebProperties.DEFAULT_BASE_PATH + "}/api/processes")
class ProcessGovernanceController {

	private final ProcessDefinitionRegistry registry;
	private final TraceabilityReport traceability;
	private final ProcessInstanceHistory history;

	ProcessGovernanceController(ProcessDefinitionRegistry registry, TraceabilityReport traceability,
			ProcessInstanceHistory history) {
		this.registry = registry;
		this.traceability = traceability;
		this.history = history;
	}

	@GetMapping
	Definitions definitions() {
		return registry.definitions();
	}

	@GetMapping("/messages")
	List<Message> messages() {
		return registry.definitions().messages();
	}

	@GetMapping("/traceability")
	List<TraceabilityEntry> traceability() {
		return traceability.entries();
	}

	@GetMapping("/instances")
	List<ProcessInstanceSummary> instances() {
		return history.instances();
	}

	@GetMapping("/instances/{instanceId}/evidence")
	List<HistoricFlowNodeInstance> evidence(@PathVariable UUID instanceId) {
		return history.flowNodeInstances(instanceId);
	}
}
