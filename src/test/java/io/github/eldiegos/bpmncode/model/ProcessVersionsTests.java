package io.github.eldiegos.bpmncode.model;

import static io.github.eldiegos.bpmncode.model.ViolationKind.AMBIGUOUS_REFERENCE;
import static io.github.eldiegos.bpmncode.model.ViolationKind.DUPLICATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.ProcessPart;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.bpmn.BpmnDiagram;
import io.github.eldiegos.bpmncode.bpmn.BpmnXmlExporter;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import fixture.process.ValidProcesses;

/**
 * Varias versiones del mismo proceso desplegadas a la vez: cada una con su definición, sus vistas BPMN y sus
 * identificadores, sin confundirse entre sí ni con otros procesos.
 */
class ProcessVersionsTests {

	private static final Definitions DEFINITIONS = AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class,
			ValidProcesses.OrderV2.class, ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class,
			ValidProcesses.Shipping.class, ValidProcesses.ShippingStarter.class));

	@Test
	void bothVersionsAreDeployedAndTheHighestIsCurrent() {
		assertThat(DEFINITIONS.versions("FX-ORDER")).extracting(ProcessDefinition::version).containsExactly(1, 2);
		assertThat(DEFINITIONS.process("FX-ORDER")).hasValueSatisfying(current -> assertThat(current.version()).isEqualTo(2));
		assertThat(DEFINITIONS.process(ProcessDefinitionRef.of("FX-ORDER", 1))).hasValueSatisfying(
				v1 -> assertThat(v1.flowNode("FX-NOTIFY")).isEmpty());
		assertThat(DEFINITIONS.process(ProcessDefinitionRef.of("FX-ORDER", 2))).hasValueSatisfying(
				v2 -> assertThat(v2.flowNode("FX-NOTIFY")).isPresent());
		assertThat(DEFINITIONS.isCurrent(ProcessDefinitionRef.of("FX-ORDER", 1))).isFalse();
		assertThat(DEFINITIONS.isCurrent(ProcessDefinitionRef.of("FX-ORDER", 2))).isTrue();
	}

	@Test
	void eachVersionThatPublishesTheMessageIsLinkedToItsConsumer() {
		assertThat(DEFINITIONS.messageFlows()).extracting(MessageFlow::sourceProcess)
				.containsExactlyInAnyOrder(ProcessDefinitionRef.of("FX-ORDER", 1), ProcessDefinitionRef.of("FX-ORDER", 2));
		assertThat(DEFINITIONS.messageFlows()).extracting(MessageFlow::targetProcess).containsOnly(ProcessDefinitionRef.of("FX-SHIPPING", 1));
	}

	@Test
	void everyDiagramShowsOneVersionPerProcessSoIdsStayUnique() {
		var current = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessDiagram("FX-ORDER"));
		var previous = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessVersionDiagram(ProcessDefinitionRef.of("FX-ORDER", 1)));
		var consumer = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessDiagram("FX-SHIPPING"));
		var collaboration = BpmnXmlExporter.export(DEFINITIONS,
				new BpmnDiagram.CollaborationDiagram(List.of("FX-ORDER", "FX-SHIPPING")));

		assertThat(current).contains("id=\"FX-NOTIFY\"", "name=\"Order\" pac:version=\"2\"");
		assertThat(previous).doesNotContain("id=\"FX-NOTIFY\"").contains("name=\"Order\" pac:version=\"1\"");
		assertThat(consumer).contains("name=\"Order\" pac:version=\"2\"");
		assertThat(collaboration).contains("id=\"FX-NOTIFY\"");
		List.of(current, previous, consumer, collaboration).forEach(xml -> assertThat(duplicateIds(xml)).isEmpty());
	}

	@Test
	void theVersionViewIsIdentifiedByProcessAndVersion() {
		assertThat(new BpmnDiagram.ProcessVersionDiagram(ProcessDefinitionRef.of("FX-ORDER", 1)).id()).isEqualTo("FX-ORDER-v1");
	}

	@Test
	void theSameVersionCannotBeDefinedTwice() {
		assertThatThrownBy(() -> AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class,
				ValidProcesses.OrderV1Reordered.class, ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class)))
				.isInstanceOf(ProcessModelException.class)
				.satisfies(error -> assertThat(((ProcessModelException) error).violations())
						.anyMatch(violation -> violation.kind() == AMBIGUOUS_REFERENCE));
	}

	@Test
	void nodeIdsAreSharedOnlyBetweenVersionsOfTheSameProcess() {
		assertThatThrownBy(() -> AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class,
				ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class, Thief.class)))
				.isInstanceOf(ProcessModelException.class)
				.satisfies(error -> assertThat(((ProcessModelException) error).violations())
						.anyMatch(violation -> violation.kind() == DUPLICATE_ID && violation.message().contains("FX-START")));
	}

	@Test
	void aComponentMustSayWhichVersionItContributesToWhenThereAreSeveral() {
		assertThatThrownBy(() -> AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class,
				ValidProcesses.OrderV2.class, ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class,
				UnversionedComponent.class)))
				.isInstanceOf(ProcessModelException.class)
				.satisfies(error -> assertThat(((ProcessModelException) error).violations())
						.anyMatch(violation -> violation.kind() == AMBIGUOUS_REFERENCE
								&& violation.message().contains("must declare its version")));
	}

	private static List<String> duplicateIds(String xml) {
		var seen = new ArrayList<String>();
		var duplicates = new ArrayList<String>();
		var matcher = Pattern.compile(" id=\"([^\"]+)\"").matcher(xml);
		while (matcher.find()) {
			if (seen.contains(matcher.group(1))) {
				duplicates.add(matcher.group(1));
			}
			seen.add(matcher.group(1));
		}
		return duplicates;
	}

	/** Otro proceso que reutiliza un identificador de nodo de FX-ORDER. */
	@Process(id = "FX-THIEF", name = "Thief", version = 1)
	static class Thief {

		@StartEvent(id = "FX-START", name = "Stolen start", outgoing = @SequenceFlow(targetRef = "FX-THIEF-END"))
		void start() {
		}

		@EndEvent(id = "FX-THIEF-END", name = "End")
		void end() {
		}
	}

	/** Componente que no indica a qué versión de FX-ORDER contribuye. */
	@ProcessPart(processRef = "FX-ORDER")
	static class UnversionedComponent {

		@EndEvent(id = "FX-EXTRA-END", name = "Extra end")
		void end() {
		}
	}
}
