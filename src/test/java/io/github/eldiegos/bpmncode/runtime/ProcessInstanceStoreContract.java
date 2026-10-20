package io.github.eldiegos.bpmncode.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Contrato que debe cumplir cualquier {@link ProcessInstanceStore}: el journal se comporta igual con todos.
 * <p>
 * Responsabilidad: probar un almacén a través del journal y del historial (un proceso completo, una instancia por proceso
 * y correlación, ningún cambio si un paso no es conforme, bloqueo optimista). Para probar un almacén propio, se extiende
 * esta clase e implementa {@link #newStore()}; se publica en el jar de pruebas de la librería (clasificador
 * {@code tests}).
 * <p>
 * Invariantes: cada prueba usa un almacén vacío; el almacén se usa sin transacción, así que un almacén que necesite una
 * (como el JPA) se prueba dentro de un contexto de Spring.
 */
public abstract class ProcessInstanceStoreContract {

	private static final ProcessDefinitionRef ORDER = ProcessDefinitionRef.of("ST-ORDER", 1);
	private static final FlowNodeOutcome DONE = FlowNodeOutcome.of("DONE");
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T10:00:00Z"), ZoneOffset.UTC);

	private static final ProcessDefinitionRegistry REGISTRY = new ProcessDefinitionRegistry(
			AnnotationProcessModelReader.read(List.of(Order.class)));

	/**
	 * Crea el almacén que se prueba, vacío.
	 *
	 * @return un almacén nuevo
	 */
	protected abstract ProcessInstanceStore newStore();

	@Test
	public void runsAnInstanceToTheEndAndKeepsItsEvidence() {
		var store = newStore();
		var journal = new ProcessJournal(REGISTRY, store, CLOCK);
		var history = new ProcessInstanceHistory(REGISTRY, store);
		var correlation = CorrelationKey.newKey();

		var instance = journal.startProcessInstance(ORDER, correlation, DONE);
		journal.setBusinessKey(instance, "order:42");
		journal.selectSequenceFlow(instance, "ST-DECIDE", Order.Decision.ACCEPT);
		assertThat(journal.completeActivity(instance, "ST-WORK", FlowNodeOutcome.of("WORKED", "detail"))).isEqualTo(1);
		journal.reachEndEvent(instance, "ST-DONE", DONE);

		var summary = history.instance(instance.instanceId()).orElseThrow();
		assertThat(summary).isInstanceOf(ProcessInstanceSummary.Completed.class);
		assertThat(summary.businessKey()).contains("order:42");
		assertThat(summary.flowNodeId()).isEqualTo("ST-DONE");
		assertThat(history.instances(correlation)).hasSize(1);
		assertThat(journal.resume("ST-ORDER", correlation)).contains(instance);
		assertThat(history.flowNodeInstances(instance.instanceId()))
				.extracting(HistoricFlowNodeInstance::sequence, HistoricFlowNodeInstance::flowNodeId,
						HistoricFlowNodeInstance::outcome)
				.containsExactly(
						tuple(1, "ST-START", DONE),
						tuple(2, "ST-DECIDE", FlowNodeOutcome.of("SEQUENCE_FLOW_SELECTED", "ACCEPT → ST-WORK")),
						tuple(3, "ST-WORK", FlowNodeOutcome.of("WORKED", "detail")),
						tuple(4, "ST-DONE", DONE));
	}

	@Test
	public void allowsOneInstancePerProcessAndCorrelation() {
		var store = newStore();
		var journal = new ProcessJournal(REGISTRY, store, CLOCK);
		var correlation = CorrelationKey.newKey();
		journal.startProcessInstance(ORDER, correlation, DONE);

		assertThatThrownBy(() -> journal.startProcessInstance(ORDER, correlation, DONE))
				.isInstanceOf(DuplicateProcessInstanceException.class);
		assertThat(store.instances(correlation)).hasSize(1);
	}

	@Test
	public void aRejectedStepStoresNothing() {
		var store = newStore();
		var journal = new ProcessJournal(REGISTRY, store, CLOCK);
		var history = new ProcessInstanceHistory(REGISTRY, store);
		var instance = journal.startProcessInstance(ORDER, CorrelationKey.newKey(), DONE);

		assertThatThrownBy(() -> journal.reachEndEvent(instance, "ST-DONE", DONE))
				.isInstanceOf(ProcessConformanceException.class);
		assertThat(history.instance(instance.instanceId()).orElseThrow())
				.isInstanceOf(ProcessInstanceSummary.Active.class)
				.extracting(ProcessInstanceSummary::flowNodeId).isEqualTo("ST-START");
		assertThat(history.flowNodeInstances(instance.instanceId())).hasSize(1);
	}

	@Test
	public void rejectsAnUpdateBasedOnAStaleVersion() {
		var store = newStore();
		var journal = new ProcessJournal(REGISTRY, store, CLOCK);
		var instance = journal.startProcessInstance(ORDER, CorrelationKey.newKey(), DONE);
		var stale = store.instance(instance.instanceId()).orElseThrow();

		journal.setBusinessKey(instance, "order:1");

		assertThatThrownBy(() -> store.update(stale)).isInstanceOf(ProcessInstanceConflictException.class);
		assertThat(store.instance(instance.instanceId()).orElseThrow().businessKey()).contains("order:1");
	}

	@Test
	public void returnsTheInstancesAndEvidenceOfAProcessVersion() {
		var store = newStore();
		var journal = new ProcessJournal(REGISTRY, store, CLOCK);
		var first = journal.startProcessInstance(ORDER, CorrelationKey.newKey(), DONE);
		var second = journal.startProcessInstance(ORDER, CorrelationKey.newKey(), DONE);
		journal.selectSequenceFlow(second, "ST-DECIDE", Order.Decision.ACCEPT);

		assertThat(store.instances(ORDER)).extracting(ProcessInstanceRecord::id)
				.containsExactlyInAnyOrder(first.instanceId(), second.instanceId());
		assertThat(store.instances(ProcessDefinitionRef.of("ST-ORDER", 2))).isEmpty();
		assertThat(store.flowNodeInstances(ORDER)).extracting(FlowNodeInstanceRecord::processInstanceId)
				.containsExactlyInAnyOrder(first.instanceId(), second.instanceId(), second.instanceId());
		assertThat(store.flowNodeInstances(ProcessDefinitionRef.of("ST-OTHER", 1))).isEmpty();
	}

	@Process(id = "ST-ORDER", name = "Order", version = 1)
	public static class Order {

		public enum Decision {
			ACCEPT, REJECT
		}

		@StartEvent(id = "ST-START", name = "Order received", outgoing = @SequenceFlow(targetRef = "ST-DECIDE"))
		void start() {
		}

		@ExclusiveGateway(id = "ST-DECIDE", name = "Accepted?", outgoing = {
				@SequenceFlow(when = "ACCEPT", targetRef = "ST-WORK", name = "Yes"),
				@SequenceFlow(when = "REJECT", targetRef = "ST-REJECTED", name = "No") })
		Decision decide() {
			return Decision.ACCEPT;
		}

		@ServiceTask(id = "ST-WORK", name = "Work", outgoing = @SequenceFlow(targetRef = "ST-DONE"))
		void work() {
		}

		@EndEvent(id = "ST-DONE", name = "Done")
		void done() {
		}

		@EndEvent(id = "ST-REJECTED", name = "Rejected")
		void rejected() {
		}
	}
}
