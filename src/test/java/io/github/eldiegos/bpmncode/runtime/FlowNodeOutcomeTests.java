package io.github.eldiegos.bpmncode.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Parse del resultado de actividad: la forma persistida se convierte en un valor válido o se rechaza.
 */
class FlowNodeOutcomeTests {

	@ParameterizedTest
	@ValueSource(strings = { "REQUEST_RECEIVED", "RULES_VIOLATED:R-PER-001, R-PER-002", "SEQUENCE_FLOW_SELECTED:ELIGIBLE → NEXT",
			"DECISION_RECORDED:APPROVED/operator:7" })
	void parseIsTheExactInverseOfTheStoredForm(String stored) {
		assertThat(FlowNodeOutcome.parse(stored).toString()).isEqualTo(stored);
	}

	@Test
	void parseSeparatesCodeAndDetailAtTheFirstColon() {
		var outcome = FlowNodeOutcome.parse("DECISION_RECORDED:APPROVED/operator:7");
		assertThat(outcome.code()).isEqualTo("DECISION_RECORDED");
		assertThat(outcome.detail()).contains("APPROVED/operator:7");
		assertThat(FlowNodeOutcome.parse("PERSON_CREATED").detail()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "request_received", "1CODE", "RULES VIOLATED", ":DETAIL", "CODE:", "CODE: " })
	void malformedValuesAreRejected(String stored) {
		assertThatThrownBy(() -> FlowNodeOutcome.parse(stored)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void namedConstructorsBuildTheSameValues() {
		assertThat(FlowNodeOutcome.of("PERSON_CREATED")).isEqualTo(FlowNodeOutcome.parse("PERSON_CREATED"));
		assertThat(FlowNodeOutcome.of("EVENT_RECEIVED", "EV-X")).isEqualTo(FlowNodeOutcome.parse("EVENT_RECEIVED:EV-X"));
	}
}
