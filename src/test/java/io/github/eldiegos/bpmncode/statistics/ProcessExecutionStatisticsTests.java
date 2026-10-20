package io.github.eldiegos.bpmncode.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StandardLoop;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.FlowNodeOutcome;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;
import io.github.eldiegos.bpmncode.runtime.ProcessRuntimeAutoConfiguration;

/**
 * Estadísticas de ejecución sobre la evidencia: mapa de calor, camino, espera y recuento por versión.
 */
class ProcessExecutionStatisticsTests {

	private static final ProcessDefinitionRef REVIEW = ProcessDefinitionRef.of("SX-REVIEW", 1);
	private static final FlowNodeOutcome OK = FlowNodeOutcome.of("OK");

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ProcessRuntimeAutoConfiguration.class,
					ProcessStatisticsAutoConfiguration.class))
			.withBean(ProcessDefinitionRegistry.class,
					() -> new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(List.of(Review.class))));

	@Test
	void theHeatmapCountsExecutionsVisitorsTraversalsAndWaitingInstances() {
		runner.run(context -> {
			var journal = context.getBean(ProcessJournal.class);
			var statistics = context.getBean(ProcessExecutionStatistics.class);
			// Una instancia aceptada al segundo intento y otra que espera en el primer intento.
			var accepted = journal.startProcessInstance(REVIEW, CorrelationKey.newKey(), OK);
			journal.completeActivity(accepted, "SX-CHECK", OK);
			journal.completeActivity(accepted, "SX-CHECK", OK);
			journal.selectSequenceFlow(accepted, "SX-DECIDE", Review.Decision.ACCEPT);
			journal.reachEndEvent(accepted, "SX-DONE", OK);
			journal.startProcessInstance(REVIEW, CorrelationKey.newKey(), OK);

			var heatmap = statistics.heatmap(REVIEW).orElseThrow();

			assertThat(heatmap.instances()).isEqualTo(2);
			assertThat(heatmap.running()).isEqualTo(1);
			assertThat(heatmap.maxExecutions()).isEqualTo(2);
			assertThat(heatmap.nodes()).extracting(ExecutionHeatmap.NodeStatistics::flowNodeId,
					ExecutionHeatmap.NodeStatistics::executions, ExecutionHeatmap.NodeStatistics::instances,
					ExecutionHeatmap.NodeStatistics::share, ExecutionHeatmap.NodeStatistics::waiting)
					.containsExactly(
							tuple("SX-START", 2L, 2, 100, 0L),
							tuple("SX-CHECK", 2L, 1, 50, 1L),
							tuple("SX-DECIDE", 1L, 1, 50, 0L),
							tuple("SX-DONE", 1L, 1, 50, 0L),
							tuple("SX-REJECTED", 0L, 0, 0, 0L));
			// La segunda vuelta del bucle no recorre ningún flujo; la rama de rechazo no se recorrió nunca.
			assertThat(heatmap.flows()).extracting(ExecutionHeatmap.FlowStatistics::sequenceFlowId,
					ExecutionHeatmap.FlowStatistics::traversals)
					.contains(tuple("Flow_SX-START_SX-CHECK", 1L), tuple("Flow_SX-CHECK_SX-DECIDE", 1L),
							tuple("Flow_SX-DECIDE_SX-REJECTED", 0L));
			assertThat(statistics.waitingByNode(REVIEW)).containsExactly(java.util.Map.entry("SX-CHECK", 1L));
			assertThat(statistics.countsByVersion()).containsEntry(REVIEW, new InstanceCounts(1, 1));
			assertThat(statistics.heatmap(ProcessDefinitionRef.of("SX-REVIEW", 9))).isEmpty();
		});
	}

	@Test
	void thePathListsEveryStepInOrderAndTheWaitingNode() {
		runner.run(context -> {
			var journal = context.getBean(ProcessJournal.class);
			var statistics = context.getBean(ProcessExecutionStatistics.class);
			var instance = journal.startProcessInstance(REVIEW, CorrelationKey.newKey(), OK);
			journal.completeActivity(instance, "SX-CHECK", OK);
			journal.completeActivity(instance, "SX-CHECK", OK);

			var path = statistics.path(instance.instanceId()).orElseThrow();

			assertThat(path.running()).isTrue();
			assertThat(path.process()).isEqualTo(REVIEW);
			assertThat(path.steps()).extracting(InstancePath.PathStep::order, InstancePath.PathStep::flowNodeId,
					InstancePath.PathStep::loopCounter)
					.containsExactly(tuple(1, "SX-START", 1), tuple(2, "SX-CHECK", 1), tuple(3, "SX-CHECK", 2));
			assertThat(path.sequenceFlowIds()).containsExactly("Flow_SX-START_SX-CHECK");
			assertThat(path.waitingFlowNodeId()).contains("SX-CHECK");
			assertThat(statistics.path(java.util.UUID.randomUUID())).isEmpty();
		});
	}

	@Process(id = "SX-REVIEW", name = "Review", version = 1)
	static class Review {

		enum Decision {
			ACCEPT, REJECT
		}

		@StartEvent(id = "SX-START", name = "Start", outgoing = @SequenceFlow(targetRef = "SX-CHECK"))
		void start() {
		}

		@ServiceTask(id = "SX-CHECK", name = "Check", outgoing = @SequenceFlow(targetRef = "SX-DECIDE"),
				loop = @StandardLoop(loopCondition = "Not yet readable", loopMaximum = 3))
		void check() {
		}

		@ExclusiveGateway(id = "SX-DECIDE", name = "Accepted?", outgoing = {
				@SequenceFlow(when = "ACCEPT", targetRef = "SX-DONE"),
				@SequenceFlow(when = "REJECT", targetRef = "SX-REJECTED") })
		Decision decide() {
			return Decision.ACCEPT;
		}

		@EndEvent(id = "SX-DONE", name = "Done")
		void done() {
		}

		@EndEvent(id = "SX-REJECTED", name = "Rejected")
		void rejected() {
		}
	}
}
