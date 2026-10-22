package io.github.eldiegos.bpmncode.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import fixture.process.ValidProcesses;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.runtime.ProcessStoreProperties.UndeployedVersions;

/**
 * El journal ante situaciones que la aplicación no controla del todo: versiones de proceso retiradas con instancias
 * guardadas y dos pasos concurrentes de la misma instancia sin una transacción que los serialice.
 */
class ProcessJournalRobustnessTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);
	private static final ProcessDefinitionRef ORDER_V1 = ProcessDefinitionRef.of("FX-ORDER", 1);

	private static ProcessDefinitionRegistry registry(Class<?> order) {
		return new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(List.of(order,
				ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class)));
	}

	@Test
	void anInstanceOfARetiredVersionStopsWithAConformanceErrorButCanStillBeListed() {
		var store = new InMemoryProcessInstanceStore();
		var before = new ProcessJournal(registry(ValidProcesses.OrderV1.class), store, CLOCK);
		var active = before.startProcessInstance(ORDER_V1, CorrelationKey.newKey(), FlowNodeOutcome.of("START"));
		var completed = before.startProcessInstance(ORDER_V1, CorrelationKey.newKey(), FlowNodeOutcome.of("START"));
		before.completeActivity(completed, "FX-CHECK", FlowNodeOutcome.of("CHECKED"));
		before.selectSequenceFlow(completed, "FX-DECIDE", ValidProcesses.Approval.DECLINED);
		before.reachEndEvent(completed, "FX-DECLINED", FlowNodeOutcome.of("DECLINED"));

		var afterDeployment = registry(ValidProcesses.OrderV2.class);
		var journal = new ProcessJournal(afterDeployment, store, CLOCK);
		var history = new ProcessInstanceHistory(afterDeployment, store);

		assertThatThrownBy(() -> journal.completeActivity(active, "FX-CHECK", FlowNodeOutcome.of("CHECKED")))
				.isInstanceOf(ProcessConformanceException.class)
				.hasMessageContaining("runs FX-ORDER v1, which is no longer deployed");
		assertThat(history.instance(active.instanceId())).hasValueSatisfying(summary -> assertThat(summary)
				.isInstanceOfSatisfying(ProcessInstanceSummary.Active.class,
						instance -> assertThat(instance.nextFlowNodeIds()).isEmpty()));
		assertThat(history.instance(completed.instanceId())).containsInstanceOf(ProcessInstanceSummary.Completed.class);
		assertThat(history.isWaitingAt(active.instanceId(), "FX-CHECK")).isFalse();
	}

	@Test
	void theStartUpCheckWarnsByDefaultAndFailsOnlyIfTheApplicationAsks() {
		var store = new InMemoryProcessInstanceStore();
		new ProcessJournal(registry(ValidProcesses.OrderV1.class), store, CLOCK)
				.startProcessInstance(ORDER_V1, CorrelationKey.newKey(), FlowNodeOutcome.of("START"));
		var afterDeployment = registry(ValidProcesses.OrderV2.class);

		new UndeployedVersionsCheck(afterDeployment, store, UndeployedVersions.WARN).afterSingletonsInstantiated();
		new UndeployedVersionsCheck(afterDeployment, store, UndeployedVersions.IGNORE).afterSingletonsInstantiated();
		assertThatThrownBy(() -> new UndeployedVersionsCheck(afterDeployment, store, UndeployedVersions.FAIL)
				.afterSingletonsInstantiated()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no longer deployed: [FX-ORDER v1]");
		new UndeployedVersionsCheck(registry(ValidProcesses.OrderV1.class), store, UndeployedVersions.FAIL)
				.afterSingletonsInstantiated();
	}

	@Test
	void twoConcurrentStepsCannotShareAPositionOfTheHistory() throws Exception {
		var paused = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var delegate = new InMemoryProcessInstanceStore();
		var store = new PausingStore(delegate, paused, resume);
		var journal = new ProcessJournal(registry(ValidProcesses.OrderV1.class), store, CLOCK);
		var instance = journal.startProcessInstance(ORDER_V1, CorrelationKey.newKey(), FlowNodeOutcome.of("START"));

		// El primer paso calcula su posición y se detiene; el segundo se registra entero mientras tanto.
		var failure = new AtomicReference<Throwable>();
		store.pauseNextCount = true;
		var first = new Thread(() -> {
			try {
				journal.completeActivity(instance, "FX-CHECK", FlowNodeOutcome.of("CHECKED"));
			}
			catch (RuntimeException e) {
				failure.set(e);
			}
		});
		first.start();
		assertThat(paused.await(5, TimeUnit.SECONDS)).isTrue();
		journal.selectSequenceFlow(instance, "FX-DECIDE", ValidProcesses.Approval.ACCEPTED);
		resume.countDown();
		first.join(5000);

		assertThat(failure.get()).isInstanceOf(ProcessInstanceConflictException.class)
				.hasMessageContaining("is already recorded");
		assertThat(delegate.flowNodeInstances(instance.instanceId())).extracting(FlowNodeInstanceRecord::sequence)
				.doesNotHaveDuplicates();
	}

	/** Almacén que detiene el primer cálculo de la posición del historial tras leerla, para forzar el entrelazado. */
	static final class PausingStore implements ProcessInstanceStore {

		private final InMemoryProcessInstanceStore delegate;
		private final CountDownLatch paused;
		private final CountDownLatch resume;
		volatile boolean pauseNextCount;

		PausingStore(InMemoryProcessInstanceStore delegate, CountDownLatch paused, CountDownLatch resume) {
			this.delegate = delegate;
			this.paused = paused;
			this.resume = resume;
		}

		@Override
		public int flowNodeInstanceCount(UUID processInstanceId) {
			var count = delegate.flowNodeInstanceCount(processInstanceId);
			if (pauseNextCount) {
				pauseNextCount = false;
				paused.countDown();
				try {
					resume.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return count;
		}

		@Override
		public void insert(ProcessInstanceRecord instance) {
			delegate.insert(instance);
		}

		@Override
		public void update(ProcessInstanceRecord instance) {
			delegate.update(instance);
		}

		@Override
		public Optional<ProcessInstanceRecord> instance(UUID instanceId) {
			return delegate.instance(instanceId);
		}

		@Override
		public Optional<ProcessInstanceRecord> instance(String processId, CorrelationKey correlationKey) {
			return delegate.instance(processId, correlationKey);
		}

		@Override
		public List<ProcessInstanceRecord> instances() {
			return delegate.instances();
		}

		@Override
		public List<ProcessInstanceRecord> instances(CorrelationKey correlationKey) {
			return delegate.instances(correlationKey);
		}

		@Override
		public void append(FlowNodeInstanceRecord flowNodeInstance) {
			delegate.append(flowNodeInstance);
		}

		@Override
		public List<FlowNodeInstanceRecord> flowNodeInstances(UUID processInstanceId) {
			return delegate.flowNodeInstances(processInstanceId);
		}
	}
}
