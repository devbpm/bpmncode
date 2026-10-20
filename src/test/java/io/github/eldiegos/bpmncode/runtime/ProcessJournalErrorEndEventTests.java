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
 * Eventos de fin de error en el journal: la instancia termina en el evento con su error como resultado de negocio, y
 * solo con la excepción de ese error.
 */
class ProcessJournalErrorEndEventTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
	private static final ProcessDefinitionRef LOAN = ProcessDefinitionRef.of("FX-LOAN", 1);

	private static final ProcessDefinitionRegistry REGISTRY = new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(
			List.of(ValidProcesses.CreditRejected.class, ValidProcesses.Loan.class)));

	private final InMemoryProcessInstanceStore store = new InMemoryProcessInstanceStore();
	private final ProcessJournal journal = new ProcessJournal(REGISTRY, store, CLOCK);
	private final ProcessInstanceHistory history = new ProcessInstanceHistory(REGISTRY, store);

	@Test
	void anErrorEndEventCompletesTheInstanceWithTheErrorCode() {
		var loan = declinedLoan();

		journal.reachEndEvent(loan, "FX-LOAN-REJECTED", new ValidProcesses.CreditRejectedForAge());

		assertThat(history.flowNodeInstances(loan.instanceId())).last().satisfies(last -> {
			assertThat(last.flowNodeId()).isEqualTo("FX-LOAN-REJECTED");
			assertThat(last.outcome()).isEqualTo(FlowNodeOutcome.of(FlowNodeOutcome.ERROR_THROWN, "CREDIT_REJECTED"));
		});
		assertThat(store.instance(loan.instanceId()).orElseThrow().state()).isEqualTo(ProcessInstanceState.COMPLETED);
	}

	@Test
	void anErrorEndEventIsOnlyReachedWithItsError() {
		var loan = declinedLoan();

		assertThatThrownBy(() -> journal.reachEndEvent(loan, "FX-LOAN-REJECTED", FlowNodeOutcome.of("REJECTED")))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessageContaining("FX-LOAN-REJECTED ends with the error FX-ERR-CREDIT");
		assertThatThrownBy(() -> journal.reachEndEvent(loan, "FX-LOAN-REJECTED", new IllegalStateException("boom")))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessageContaining("java.lang.IllegalStateException is not a @ProcessError");
		assertThat(history.flowNodeInstances(loan.instanceId())).hasSize(2);
	}

	@Test
	void aPlainEndEventDoesNotEndWithAnError() {
		var loan = journal.startProcessInstance(LOAN, CorrelationKey.newKey(), FlowNodeOutcome.of("REQUESTED"));
		journal.selectSequenceFlow(loan, "FX-LOAN-DECIDE", ValidProcesses.Approval.ACCEPTED);

		assertThatThrownBy(() -> journal.reachEndEvent(loan, "FX-LOAN-GRANTED", new ValidProcesses.CreditRejected("no")))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessageContaining("FX-LOAN-GRANTED does not end with an error");
	}

	private ProcessInstanceRef declinedLoan() {
		var loan = journal.startProcessInstance(LOAN, CorrelationKey.newKey(), FlowNodeOutcome.of("REQUESTED"));
		journal.selectSequenceFlow(loan, "FX-LOAN-DECIDE", ValidProcesses.Approval.DECLINED);
		return loan;
	}
}
