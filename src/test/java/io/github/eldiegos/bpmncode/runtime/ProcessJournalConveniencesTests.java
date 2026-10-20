package io.github.eldiegos.bpmncode.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fixture.process.VersionedMessages;

import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Atajos del journal: inicio idempotente, inicio por mensaje con su evidencia, instancia obligatoria, gateway que
 * devuelve su constante y turno de una tarea.
 */
class ProcessJournalConveniencesTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);
	private static final ProcessDefinitionRef FULFILMENT = ProcessDefinitionRef.of("FX-FULFILMENT", 1);
	private static final ProcessDefinitionRef ORDER = ProcessDefinitionRef.of("ST-ORDER", 1);

	private static final ProcessDefinitionRegistry REGISTRY = new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(
			List.of(VersionedMessages.PaymentReceived.class, VersionedMessages.PaymentReceivedV1.class,
					VersionedMessages.PaymentReceivedV2.class, VersionedMessages.BillingV1.class,
					VersionedMessages.BillingV2.class, VersionedMessages.Fulfilment.class,
					ProcessInstanceStoreContract.Order.class)));

	private final InMemoryProcessInstanceStore store = new InMemoryProcessInstanceStore();
	private final ProcessJournal journal = new ProcessJournal(REGISTRY, store, CLOCK);
	private final ProcessInstanceHistory history = new ProcessInstanceHistory(REGISTRY, store);

	@Test
	void aRepeatedMessageDoesNotStartASecondInstance() {
		var payment = payment(CorrelationKey.newKey());

		var first = journal.startProcessInstanceIfAbsent(FULFILMENT, payment, FlowNodeOutcome.of("PAID"));
		var again = journal.startProcessInstanceIfAbsent(FULFILMENT, payment, FlowNodeOutcome.of("PAID"));

		assertThat(first).isInstanceOf(ProcessStart.Started.class);
		assertThat(again).isInstanceOf(ProcessStart.AlreadyStarted.class);
		assertThat(again.instance()).isEqualTo(first.instance());
		assertThat(history.flowNodeInstances(first.instance().instanceId())).hasSize(1);
	}

	@Test
	void startingWithAMessageRecordsWhichVersionWasReceived() {
		var instance = journal.startProcessInstance(FULFILMENT, payment(CorrelationKey.newKey()));

		assertThat(history.flowNodeInstances(instance.instanceId()).getFirst().outcome())
				.isEqualTo(FlowNodeOutcome.messageReceived(new MessageVersionRef("FX-EV-PAID", 1)))
				.isEqualTo(FlowNodeOutcome.of(FlowNodeOutcome.MESSAGE_RECEIVED, "FX-EV-PAID@1"));
	}

	@Test
	void requireFindsTheInstanceOrFailsWithAConformanceError() {
		var correlation = CorrelationKey.newKey();
		var started = journal.startProcessInstanceIfAbsent(ORDER, correlation, FlowNodeOutcome.of("START"));

		assertThat(journal.require("ST-ORDER", correlation)).isEqualTo(started.instance());
		assertThatThrownBy(() -> journal.require("ST-ORDER", CorrelationKey.newKey()))
				.isInstanceOf(ProcessConformanceException.class).hasMessageContaining("No ST-ORDER instance");
	}

	@Test
	void theGatewayReturnsItsConstantAndTheHistoryKnowsWhereTheInstanceWaits() {
		var instance = journal.startProcessInstance(ORDER, CorrelationKey.newKey(), FlowNodeOutcome.of("START"));
		assertThat(history.isWaitingAt(instance.instanceId(), "ST-DECIDE")).isTrue();
		assertThat(history.isWaitingAt(instance.instanceId(), "ST-WORK")).isFalse();

		var decision = journal.selectSequenceFlow(instance, "ST-DECIDE", ProcessInstanceStoreContract.Order.Decision.ACCEPT);

		assertThat(decision).isEqualTo(ProcessInstanceStoreContract.Order.Decision.ACCEPT);
		assertThat(history.isWaitingAt(instance.instanceId(), "ST-WORK")).isTrue();
		assertThat(history.isWaitingAt(UUID.randomUUID(), "ST-WORK")).isFalse();
	}

	private static VersionedMessages.PaymentReceivedV1 payment(CorrelationKey correlation) {
		var header = new MessageHeader(MessageId.newId(), correlation, CLOCK.instant(), ProcessDefinitionRef.of("FX-BILLING", 1),
				UUID.randomUUID());
		return new VersionedMessages.PaymentReceivedV1(header, "order-1", BigDecimal.TEN);
	}
}
