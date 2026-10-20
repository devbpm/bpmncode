package io.github.eldiegos.bpmncode.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fixture.process.ValidProcesses;

import io.github.eldiegos.bpmncode.bpmn.BpmnAutoConfiguration;
import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.FlowNodeOutcome;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;
import io.github.eldiegos.bpmncode.runtime.ProcessRuntimeAutoConfiguration;

/**
 * Las comprobaciones del jar de pruebas funcionan sobre los procesos de prueba de la librería.
 */
class ProcessAssertionsTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ProcessRuntimeAutoConfiguration.class, BpmnAutoConfiguration.class))
			.withBean(ProcessDefinitionRegistry.class, () -> new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(
					List.of(ValidProcesses.OrderV1.class, ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class,
							ValidProcesses.Shipping.class, ValidProcesses.ShippingStarter.class))));

	@Test
	void theDiagramsOfTheFixturesAreValidAndFullyDescribed() {
		runner.run(context -> {
			var diagrams = context.getBean(BpmnDiagramService.class);
			ProcessAssertions.assertEveryDiagramIsValidBpmn(diagrams);
			ProcessAssertions.assertEveryDrawnElementIsDescribed(diagrams);
		});
	}

	@Test
	void anInvalidDocumentFailsWithTheReason() {
		assertThatThrownBy(() -> ProcessAssertions.assertValidBpmn("<definitions/>")).isInstanceOf(AssertionError.class)
				.hasMessageContaining("not valid BPMN 2.0");
	}

	@Test
	void thePathOfAnInstanceListsItsNodesInOrder() {
		runner.run(context -> {
			var journal = context.getBean(ProcessJournal.class);
			var instance = journal.startProcessInstance(ProcessDefinitionRef.of("FX-ORDER", 1), CorrelationKey.newKey(),
					FlowNodeOutcome.of("START"));
			journal.completeActivity(instance, "FX-CHECK", FlowNodeOutcome.of("CHECKED"));

			assertThat(ProcessAssertions.pathOf(context.getBean(ProcessInstanceHistory.class), instance.instanceId()))
					.containsExactly("FX-START", "FX-CHECK");
		});
	}
}
