package io.github.eldiegos.bpmncode.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import fixture.process.ValidProcesses;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Tareas de envío en el journal: se completan con el mensaje que envían, que queda registrado, y la instancia sigue su
 * flujo; el mensaje inicia el proceso que lo consume.
 */
class ProcessJournalSendTaskTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
	private static final ProcessDefinitionRef SUBSCRIPTION = ProcessDefinitionRef.of("FX-SUBSCRIPTION", 1);
	private static final ProcessDefinitionRef INVOICING = ProcessDefinitionRef.of("FX-INVOICING", 1);

	private static final ProcessDefinitionRegistry REGISTRY = new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(
			List.of(ValidProcesses.InvoiceRequested.class, ValidProcesses.Subscription.class, ValidProcesses.Invoicing.class,
					ValidProcesses.OrderAccepted.class)));

	private final InMemoryProcessInstanceStore store = new InMemoryProcessInstanceStore();
	private final ProcessJournal journal = new ProcessJournal(REGISTRY, store, CLOCK);
	private final ProcessInstanceHistory history = new ProcessInstanceHistory(REGISTRY, store);

	@Test
	void aSendTaskRecordsTheMessageItSendsAndTheInstanceContinues() {
		var correlation = CorrelationKey.newKey();
		var subscription = journal.startProcessInstance(SUBSCRIPTION, correlation, FlowNodeOutcome.of("REQUESTED"));
		var invoice = new ValidProcesses.InvoiceRequested(journal.messageHeader(subscription), "sub-1");

		var iteration = journal.completeActivity(subscription, "FX-REQUEST-INVOICE", invoice);
		journal.completeActivity(subscription, "FX-ACTIVATE", FlowNodeOutcome.of("ACTIVATED"));
		journal.reachEndEvent(subscription, "FX-ACTIVE", FlowNodeOutcome.of("ACTIVE"));

		assertThat(iteration).isEqualTo(1);
		assertThat(history.flowNodeInstances(subscription.instanceId()))
				.extracting(HistoricFlowNodeInstance::flowNodeId)
				.containsExactly("FX-SUB-START", "FX-REQUEST-INVOICE", "FX-ACTIVATE", "FX-ACTIVE");
		assertThat(history.flowNodeInstances(subscription.instanceId()).get(1).outcome()).isEqualTo(FlowNodeOutcome.of(
				FlowNodeOutcome.MESSAGE_PUBLISHED, "FX-EV-INVOICE-REQUESTED@1 " + invoice.header().messageId()));

		var invoicing = journal.startProcessInstance(INVOICING, invoice);
		assertThat(journal.messageHeader(invoicing).correlationKey()).isEqualTo(correlation);
	}

	@Test
	void aSendTaskCannotBeCompletedWithoutItsMessage() {
		var subscription = journal.startProcessInstance(SUBSCRIPTION, CorrelationKey.newKey(), FlowNodeOutcome.of("REQUESTED"));

		assertThatThrownBy(() -> journal.completeActivity(subscription, "FX-REQUEST-INVOICE", FlowNodeOutcome.of("SENT")))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessageContaining("FX-REQUEST-INVOICE sends FX-EV-INVOICE-REQUESTED@1");
		assertThat(history.flowNodeInstances(subscription.instanceId())).hasSize(1);
	}

	@Test
	void aSendTaskOnlySendsItsOwnMessageWithAHeaderOfTheSameInstance() {
		var subscription = journal.startProcessInstance(SUBSCRIPTION, CorrelationKey.newKey(), FlowNodeOutcome.of("REQUESTED"));
		var other = journal.startProcessInstance(SUBSCRIPTION, CorrelationKey.newKey(), FlowNodeOutcome.of("REQUESTED"));
		var wrongMessage = new ValidProcesses.OrderAccepted(journal.messageHeader(subscription), "order-1");
		var foreignHeader = new ValidProcesses.InvoiceRequested(journal.messageHeader(other), "sub-2");

		assertThatThrownBy(() -> journal.completeActivity(subscription, "FX-REQUEST-INVOICE", wrongMessage))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessageContaining("sends FX-EV-INVOICE-REQUESTED@1, not FX-EV-ORDERED@1");
		assertThatThrownBy(() -> journal.completeActivity(subscription, "FX-REQUEST-INVOICE", foreignHeader))
				.isInstanceOf(ProcessConformanceException.class).hasMessageContaining("belongs to another instance");
		assertThat(history.flowNodeInstances(subscription.instanceId())).hasSize(1);
	}

	@Test
	void onlyASendTaskIsCompletedWithAMessage() {
		var subscription = journal.startProcessInstance(SUBSCRIPTION, CorrelationKey.newKey(), FlowNodeOutcome.of("REQUESTED"));
		journal.completeActivity(subscription, "FX-REQUEST-INVOICE",
				new ValidProcesses.InvoiceRequested(journal.messageHeader(subscription), "sub-1"));

		assertThatThrownBy(() -> journal.completeActivity(subscription, "FX-ACTIVATE",
				new ValidProcesses.InvoiceRequested(journal.messageHeader(subscription), "sub-1")))
				.isInstanceOf(ProcessConformanceException.class).hasMessageContaining("FX-ACTIVATE sends no message");
	}
}
