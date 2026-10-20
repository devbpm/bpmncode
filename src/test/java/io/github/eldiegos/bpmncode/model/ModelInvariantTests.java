package io.github.eldiegos.bpmncode.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.eldiegos.bpmncode.bpmn.BpmnDiagram;
import io.github.eldiegos.bpmncode.model.CodeReference.MethodReference;

/**
 * Los tipos del modelo no permiten construir estados ilegales.
 */
class ModelInvariantTests {

	private static final MethodReference CODE = new MethodReference("x.Y", "m()");
	private static final FlowNode START = new FlowNode.StartEvent("S", "P", "Start", "", CODE, Optional.empty());
	private static final FlowNode END = new FlowNode.EndEvent("E", "P", "End", "", CODE, Optional.empty());

	@Test
	void businessRuleTaskWithoutRulesCannotExist() {
		assertThatThrownBy(() -> new FlowNode.BusinessRuleTask("A", "P", "Rules", "", CODE, List.of(), Optional.empty()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aStandardLoopNeedsItsConditionAndANonNegativeMaximum() {
		assertThatThrownBy(() -> new StandardLoopCharacteristics(" ", 3)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StandardLoopCharacteristics("Again", -1)).isInstanceOf(IllegalArgumentException.class);
		var unlimited = new StandardLoopCharacteristics("Again", 0);
		assertThat(unlimited.maximum()).isEmpty();
		assertThat(unlimited.allowsAnotherIteration(1_000)).isTrue();
		var three = new StandardLoopCharacteristics("Again", 3);
		assertThat(three.allowsAnotherIteration(2)).isTrue();
		assertThat(three.allowsAnotherIteration(3)).isFalse();
	}

	@Test
	void nodesNeedIdentity() {
		assertThatThrownBy(() -> new FlowNode.EndEvent(" ", "P", "End", "", CODE, Optional.empty()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MethodReference("x.Y", "")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void conditionalFlowsNeedTheirConstant() {
		assertThatThrownBy(() -> new SequenceFlow.Conditional("F", "P", "G", "E", "Yes", "c", ""))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void processDefinitionsNeedExactlyOneStartAndAnEnd() {
		var flow = new SequenceFlow.Unconditional("F", "P", "S", "E");
		assertThatThrownBy(() -> new ProcessDefinition("P", "P", 1, "", "x", List.of(), List.of(END), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProcessDefinition("P", "P", 1, "", "x", List.of(), List.of(START), List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProcessDefinition("P", "P", 0, "", "x", List.of(), List.of(START, END), List.of(flow)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProcessDefinition("P", "P", 1, "", "x", List.of(), List.of(START, END),
				List.of(new SequenceFlow.Unconditional("F", "P", "S", "MISSING"))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aCollaborationNeedsAtLeastTwoProcesses() {
		assertThatThrownBy(() -> new BpmnDiagram.CollaborationDiagram(List.of("P"))).isInstanceOf(IllegalArgumentException.class);
	}
}
