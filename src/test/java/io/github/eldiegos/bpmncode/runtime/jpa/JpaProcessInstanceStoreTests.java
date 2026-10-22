package io.github.eldiegos.bpmncode.runtime.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.DuplicateProcessInstanceException;
import io.github.eldiegos.bpmncode.runtime.FlowNodeInstanceRecord;
import io.github.eldiegos.bpmncode.runtime.FlowNodeOutcome;
import io.github.eldiegos.bpmncode.runtime.HistoricFlowNodeInstance;
import io.github.eldiegos.bpmncode.runtime.InMemoryProcessInstanceStore;
import io.github.eldiegos.bpmncode.runtime.JdbcProcessInstanceStore;
import io.github.eldiegos.bpmncode.runtime.ProcessConformanceException;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceConflictException;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceStore;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceSummary;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceStoreContract;
import io.github.eldiegos.bpmncode.runtime.ProcessRuntimeAutoConfiguration;

/**
 * En una aplicación con JPA, la librería guarda las instancias en la unidad de persistencia de la aplicación, con el
 * mismo comportamiento que los demás almacenes.
 */
class JpaProcessInstanceStoreTests {

	private static final ProcessDefinitionRef ORDER = ProcessDefinitionRef.of("ST-ORDER", 1);
	private static final FlowNodeOutcome DONE = FlowNodeOutcome.of("DONE");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
					TransactionAutoConfiguration.class, ProcessRuntimeAutoConfiguration.class))
			.withPropertyValues("spring.datasource.generate-unique-name=true")
			.withBean(ProcessDefinitionRegistry.class, () -> new ProcessDefinitionRegistry(
					AnnotationProcessModelReader.read(List.of(ProcessInstanceStoreContract.Order.class))));

	@Test
	void anApplicationWithJpaStoresInstancesWithJpa() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ProcessInstanceStore.class)).isInstanceOf(JpaProcessInstanceStore.class);
		});
	}

	@Test
	void runsAnInstanceToTheEndWithJpa() {
		runner.run(context -> {
			var journal = context.getBean(ProcessJournal.class);
			var history = context.getBean(ProcessInstanceHistory.class);
			var correlation = CorrelationKey.newKey();

			var instance = journal.startProcessInstance(ORDER, correlation, DONE);
			journal.setBusinessKey(instance, "order:7");
			journal.selectSequenceFlow(instance, "ST-DECIDE", ProcessInstanceStoreContract.Order.Decision.ACCEPT);
			journal.completeActivity(instance, "ST-WORK", DONE);
			journal.reachEndEvent(instance, "ST-DONE", DONE);

			var summary = history.instance(instance.instanceId()).orElseThrow();
			assertThat(summary).isInstanceOf(ProcessInstanceSummary.Completed.class);
			assertThat(summary.businessKey()).contains("order:7");
			assertThat(history.flowNodeInstances(instance.instanceId())).extracting(HistoricFlowNodeInstance::flowNodeId)
					.containsExactly("ST-START", "ST-DECIDE", "ST-WORK", "ST-DONE");
		});
	}

	@Test
	void appliesTheSameRulesAsTheOtherStores() {
		runner.run(context -> {
			var journal = context.getBean(ProcessJournal.class);
			var store = context.getBean(ProcessInstanceStore.class);
			var correlation = CorrelationKey.newKey();
			var instance = journal.startProcessInstance(ORDER, correlation, DONE);

			assertThatThrownBy(() -> journal.startProcessInstance(ORDER, correlation, DONE))
					.isInstanceOf(DuplicateProcessInstanceException.class);
			assertThatThrownBy(() -> journal.reachEndEvent(instance, "ST-DONE", DONE))
					.isInstanceOf(ProcessConformanceException.class);

			var stale = store.instance(instance.instanceId()).orElseThrow();
			journal.setBusinessKey(instance, "order:8");
			assertThatThrownBy(() -> store.update(stale)).isInstanceOf(ProcessInstanceConflictException.class);
			assertThat(store.instance(instance.instanceId()).orElseThrow().businessKey()).contains("order:8");
		});
	}

	@Test
	void recordsEachPositionOfTheHistoryOnceAndListsTheActiveVersionsWithJpa() {
		runner.run(context -> {
			var journal = context.getBean(ProcessJournal.class);
			var store = context.getBean(ProcessInstanceStore.class);
			var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
			var instance = journal.startProcessInstance(ORDER, CorrelationKey.newKey(), DONE);
			var first = store.flowNodeInstances(instance.instanceId()).getFirst();
			var repeated = new FlowNodeInstanceRecord(UUID.randomUUID(), first.processInstanceId(), first.sequence(),
					first.processId(), first.processVersion(), first.correlationKey(), first.businessKey(), "ST-DECIDE",
					first.elementType(), first.loopCounter(), first.occurredAt(), DONE);

			assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.append(repeated)))
					.isInstanceOf(ProcessInstanceConflictException.class).hasMessageContaining("already recorded");
			assertThat(store.flowNodeInstances(instance.instanceId())).hasSize(1);
			assertThat(store.activeVersions()).containsExactly(ORDER);
		});
	}

	@Test
	void theApplicationCanChooseJdbcOrMemoryEvenWithJpa() {
		runner.withPropertyValues("pac.process.store.type=jdbc")
				.run(context -> assertThat(context.getBean(ProcessInstanceStore.class))
						.isInstanceOf(JdbcProcessInstanceStore.class));
		runner.withPropertyValues("pac.process.store.type=memory")
				.run(context -> assertThat(context.getBean(ProcessInstanceStore.class))
						.isInstanceOf(InMemoryProcessInstanceStore.class));
	}
}
