package io.github.eldiegos.bpmncode.model.reader;

import static io.github.eldiegos.bpmncode.model.ViolationKind.AMBIGUOUS_REFERENCE;
import static io.github.eldiegos.bpmncode.model.ViolationKind.GATEWAY_OUTGOING_MISMATCH;
import static io.github.eldiegos.bpmncode.model.ViolationKind.DUPLICATE_ID;
import static io.github.eldiegos.bpmncode.model.ViolationKind.INCOMPATIBLE_METADATA;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MISSING_END_EVENT;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MISSING_PROCESS_ANNOTATION;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MISSING_START_EVENT;
import static io.github.eldiegos.bpmncode.model.ViolationKind.NO_PATH_TO_END_EVENT;
import static io.github.eldiegos.bpmncode.model.ViolationKind.UNIDENTIFIED_ELEMENT;
import static io.github.eldiegos.bpmncode.model.ViolationKind.UNKNOWN_MESSAGE_REF;
import static io.github.eldiegos.bpmncode.model.ViolationKind.UNKNOWN_PROCESS_REF;
import static io.github.eldiegos.bpmncode.model.ViolationKind.UNKNOWN_BUSINESS_RULE_REF;
import static io.github.eldiegos.bpmncode.model.ViolationKind.UNKNOWN_TARGET_REF;
import static io.github.eldiegos.bpmncode.model.ViolationKind.UNREACHABLE_FLOW_NODE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import fixture.process.BrokenProcesses;
import fixture.process.ValidProcesses;

import io.github.eldiegos.bpmncode.model.BpmnElementType;
import io.github.eldiegos.bpmncode.model.BusinessRuleDefinition;
import io.github.eldiegos.bpmncode.model.BusinessRuleRef;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ModelViolation;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessModelException;
import io.github.eldiegos.bpmncode.model.ResourceDefinition;
import io.github.eldiegos.bpmncode.model.ResourceRole;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import io.github.eldiegos.bpmncode.model.StandardLoopCharacteristics;
import io.github.eldiegos.bpmncode.model.TimerEventDefinition;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Descubrimiento y validación del metamodelo de anotaciones sobre procesos de prueba.
 */
class AnnotationProcessModelReaderTests {

	@Test
	void readsTheDeclaredGraphOfAValidProcess() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class, ValidProcesses.CreditRule.class,
				ValidProcesses.OrderAccepted.class, ValidProcesses.Shipping.class, ValidProcesses.ShippingStarter.class));

		var order = definitions.process("FX-ORDER").orElseThrow();
		assertThat(order.version()).isEqualTo(1);
		assertThat(order.flowNodes()).extracting(FlowNode::id)
				.containsExactly("FX-START", "FX-CHECK", "FX-DECIDE", "FX-CREATE", "FX-DECLINED", "FX-DONE");
		assertThat(order.flowNode("FX-CHECK").orElseThrow().elementType()).isEqualTo(BpmnElementType.BUSINESS_RULE_TASK);
		assertThat(order.flowNode("FX-CHECK").orElseThrow().businessRuleRefs()).containsExactly(BusinessRuleRef.of("FX-R-001", 1));
		assertThat(order.flowNode("FX-CHECK").orElseThrow().implementation().signature()).isEqualTo("check()");
		assertThat(order.flowNode("FX-START").orElseThrow()).isInstanceOf(FlowNode.StartEvent.class);
		assertThat(order.flowNode("FX-DONE").orElseThrow()).isInstanceOfSatisfying(FlowNode.EndEvent.class,
				end -> assertThat(end.messageRef()).contains("FX-EV-ORDERED"));
		assertThat(order.outgoing("FX-DECIDE")).allSatisfy(flow -> assertThat(flow).isInstanceOf(SequenceFlow.Conditional.class))
				.extracting(flow -> ((SequenceFlow.Conditional) flow).when(), SequenceFlow::targetRef)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("ACCEPTED", "FX-CREATE"),
						org.assertj.core.groups.Tuple.tuple("DECLINED", "FX-DECLINED"));
		assertThat(order.outgoing("FX-START")).singleElement().isInstanceOf(SequenceFlow.Unconditional.class);
		assertThat(definitions.businessRule(BusinessRuleRef.of("FX-R-001", 1)).orElseThrow().implementation().toString())
				.endsWith("ValidProcesses$CreditRule#withinLimit(int)");

		var shipping = definitions.process("FX-SHIPPING").orElseThrow();
		assertThat(shipping.startEvent().id()).isEqualTo("FX-SHIP-START");
		assertThat(shipping.implementedBy()).hasSize(2);
		assertThat(definitions.messageFlows()).singleElement().satisfies(flow -> {
			assertThat(flow.messageRef()).isEqualTo("FX-EV-ORDERED");
			assertThat(flow.sourceRef()).isEqualTo("FX-DONE");
			assertThat(flow.targetRef()).isEqualTo("FX-SHIP-START");
		});
	}

	@Test
	void aSendTaskSendsItsMessageToTheProcessItStarts() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.InvoiceRequested.class,
				ValidProcesses.Subscription.class, ValidProcesses.Invoicing.class));

		var subscription = definitions.process("FX-SUBSCRIPTION").orElseThrow();
		assertThat(subscription.flowNode("FX-REQUEST-INVOICE").orElseThrow()).isInstanceOfSatisfying(FlowNode.SendTask.class,
				task -> {
					assertThat(task.elementType()).isEqualTo(BpmnElementType.SEND_TASK);
					assertThat(task.message()).isEqualTo(new MessageVersionRef("FX-EV-INVOICE-REQUESTED", 1));
					assertThat(task.publishedMessage()).contains(task.message());
				});
		assertThat(subscription.outgoing("FX-REQUEST-INVOICE")).singleElement().extracting(SequenceFlow::targetRef)
				.isEqualTo("FX-ACTIVATE");
		assertThat(definitions.messageFlows()).singleElement().satisfies(flow -> {
			assertThat(flow.id()).isEqualTo("MessageFlow_FX-REQUEST-INVOICE_FX-INV-START");
			assertThat(flow.sourceProcess()).isEqualTo(ProcessDefinitionRef.of("FX-SUBSCRIPTION", 1));
			assertThat(flow.sourceRef()).isEqualTo("FX-REQUEST-INVOICE");
			assertThat(flow.targetRef()).isEqualTo("FX-INV-START");
			assertThat(flow.version()).isEqualTo(1);
		});
	}

	@Test
	void anErrorEndEventEndsWithTheErrorOfItsException() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.CreditRejected.class,
				ValidProcesses.Loan.class));

		var loan = definitions.process("FX-LOAN").orElseThrow();
		assertThat(loan.flowNode("FX-LOAN-REJECTED").orElseThrow()).isInstanceOfSatisfying(FlowNode.EndEvent.class, end -> {
			assertThat(end.error()).hasValueSatisfying(error -> assertThat(error.errorRef()).isEqualTo("FX-ERR-CREDIT"));
			assertThat(end.messageDefinition()).isEmpty();
			assertThat(end.publishedMessage()).isEmpty();
		});
		assertThat(loan.flowNode("FX-LOAN-GRANTED").orElseThrow()).isInstanceOfSatisfying(FlowNode.EndEvent.class,
				end -> assertThat(end.eventDefinition()).isEmpty());
		assertThat(definitions.error("FX-ERR-CREDIT")).hasValueSatisfying(error -> {
			assertThat(error.errorCode()).isEqualTo("CREDIT_REJECTED");
			assertThat(error.itemDefinition().structureRef()).endsWith("ValidProcesses.CreditRejected");
		});
		assertThat(definitions.errorOf(ValidProcesses.CreditRejectedForAge.class)).map(error -> error.id())
				.contains("FX-ERR-CREDIT");
		assertThat(definitions.errorOf(IllegalStateException.class)).isEmpty();
	}

	@Test
	void anErrorIsAnAnnotatedExceptionReferencedByItsType() {
		var violations = readBroken(List.of(BrokenProcesses.NotAnException.class, BrokenProcesses.UndeclaredError.class))
				.violations();

		assertThat(violations).extracting(ModelViolation::kind).contains(ViolationKind.INVALID_ERROR,
				ViolationKind.UNKNOWN_ERROR_REF);
		assertThat(violations).extracting(ModelViolation::message)
				.anySatisfy(text -> assertThat(text).contains("@ProcessError goes on an exception"))
				.anySatisfy(text -> assertThat(text).contains("errorRef java.lang.IllegalStateException is not an exception"));
	}

	@Test
	void aUserTaskDeclaresWhoCanOrMustPerformIt() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.Reviewer.class,
				ValidProcesses.Review.class));

		var review = (FlowNode.UserTask) definitions.process("FX-REVIEW").orElseThrow().flowNode("FX-REV-REVIEW")
				.orElseThrow();
		assertThat(review.potentialOwners()).containsExactly(new ResourceRole.Reference("FX-RES-REVIEWER"),
				new ResourceRole.Expression("team lead of the author"));
		assertThat(review.humanPerformers()).containsExactly(
				new ResourceRole.Expression("the reviewer who claimed the task"));
		assertThat(definitions.resource("FX-RES-REVIEWER")).hasValueSatisfying(resource -> {
			assertThat(resource.definedBy()).endsWith("ValidProcesses.Reviewer");
			assertThat(resource.parameters())
					.extracting(ResourceDefinition.Parameter::name, parameter -> parameter.type().structureRef(),
							ResourceDefinition.Parameter::required)
					.containsExactly(org.assertj.core.groups.Tuple.tuple("department", "java.lang.String", true),
							org.assertj.core.groups.Tuple.tuple("language", "java.lang.String", false));
		});
	}

	@Test
	void aResourceRoleSaysWhoExactlyOnceAndReferencesADeclaredResource() {
		var violations = readBroken(List.of(BrokenProcesses.BrokenRoles.class)).violations();

		assertThat(violations).extracting(ModelViolation::kind).contains(INCOMPATIBLE_METADATA,
				ViolationKind.UNKNOWN_RESOURCE_REF);
		assertThat(violations).extracting(ModelViolation::message)
				.filteredOn(text -> text.contains("a potential owner declares exactly one of resourceRef or expression"))
				.hasSize(2);
		assertThat(violations).extracting(ModelViolation::message)
				.anySatisfy(text -> assertThat(text).contains("resourceRef java.lang.Integer is not a type annotated with"
						+ " @ProcessResource"));
	}

	@Test
	void physicalOrderOfMethodsDoesNotChangeTheProcess() {
		var original = AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class, ValidProcesses.CreditRule.class,
				ValidProcesses.OrderAccepted.class)).process("FX-ORDER").orElseThrow();
		var reordered = AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1Reordered.class,
				ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class)).process("FX-ORDER").orElseThrow();

		assertThat(reordered.flowNodes()).extracting(FlowNode::id, FlowNode::elementType, FlowNode::name, FlowNode::businessRuleRefs)
				.containsExactlyElementsOf(original.flowNodes().stream()
						.map(node -> org.assertj.core.groups.Tuple.tuple(node.id(), node.elementType(), node.name(), node.businessRuleRefs()))
						.toList());
		assertThat(reordered.sequenceFlows()).isEqualTo(original.sequenceFlows());
	}

	@Test
	void reportsDuplicateIds() {
		assertViolations(List.of(BrokenProcesses.DuplicateIds.class), DUPLICATE_ID);
	}

	@Test
	void reportsProcessesWithoutStartOrEnd() {
		assertViolations(List.of(BrokenProcesses.MissingStart.class), MISSING_START_EVENT);
		assertViolations(List.of(BrokenProcesses.MissingEnd.class), MISSING_END_EVENT);
	}

	@Test
	void reportsSequenceFlowsToMissingFlowNodes() {
		assertViolations(List.of(BrokenProcesses.UnknownTarget.class), UNKNOWN_TARGET_REF);
	}

	@Test
	void reportsSequenceFlowsIntoAnotherProcess() {
		var exception = readBroken(List.of(BrokenProcesses.CrossProcessSequenceFlow.class, ValidProcesses.OrderV1.class,
				ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class));
		assertThat(exception.violations()).anySatisfy(violation -> {
			assertThat(violation.kind()).isEqualTo(UNKNOWN_TARGET_REF);
			assertThat(violation.message()).contains("FX-ORDER", "only communicate through messages");
		});
	}

	@Test
	void reportsUnidentifiedDecisionsAndRules() {
		var exception = readBroken(List.of(BrokenProcesses.Unidentified.class));
		assertThat(exception.violations()).filteredOn(violation -> violation.kind() == UNIDENTIFIED_ELEMENT).hasSize(2);
	}

	@Test
	void reportsAmbiguousReferences() {
		var exception = readBroken(List.of(BrokenProcesses.AmbiguousFirst.class, BrokenProcesses.AmbiguousSecond.class));
		assertThat(exception.violations()).filteredOn(violation -> violation.kind() == AMBIGUOUS_REFERENCE)
				.extracting(ModelViolation::message)
				.anyMatch(message -> message.contains("is already defined"))
				.anyMatch(message -> message.contains("several flow nodes"));
	}

	@Test
	void reportsActivitiesWithoutTheRequiredProcessAnnotation() {
		assertViolations(List.of(BrokenProcesses.OrphanActivity.class), MISSING_PROCESS_ANNOTATION);
		assertViolations(List.of(BrokenProcesses.ComponentOfUnknownProcess.class), UNKNOWN_PROCESS_REF);
	}

	@Test
	void versionsOfARuleCoexistAndEachStepChoosesOne() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.Limits.class, ValidProcesses.VersionedLimitRule.class));
		var process = definitions.process("FX-LIMITS").orElseThrow();

		assertThat(definitions.businessRuleVersions("FX-R-002")).extracting(BusinessRuleDefinition::ref)
				.containsExactly(BusinessRuleRef.of("FX-R-002", 1), BusinessRuleRef.of("FX-R-002", 2));
		assertThat(process.flowNode("LIM-OLD").orElseThrow().businessRuleRefs()).containsExactly(BusinessRuleRef.of("FX-R-002", 1));
		assertThat(process.flowNode("LIM-NEW").orElseThrow().businessRuleRefs()).containsExactly(BusinessRuleRef.of("FX-R-002", 2));
		assertThat(definitions.businessRule(BusinessRuleRef.of("FX-R-002", 2)).orElseThrow().implementation().toString())
				.endsWith("VersionedLimitRule#withinStricterLimit(int)");
		assertThat(BusinessRuleRef.of("FX-R-002", 2)).hasToString("FX-R-002 v2").extracting(BusinessRuleRef::reference).isEqualTo("FX-R-002@2");
	}

	@Test
	void reportsAmbiguousUnknownAndDuplicateRuleVersions() {
		var exception = readBroken(List.of(BrokenProcesses.RuleVersionMistakes.class, BrokenProcesses.TwoVersionRule.class));
		assertThat(exception.violations()).extracting(ModelViolation::kind).contains(AMBIGUOUS_REFERENCE, UNKNOWN_BUSINESS_RULE_REF, DUPLICATE_ID);
		assertThat(exception.violations()).extracting(ModelViolation::message)
				.anyMatch(message -> message.contains("without a version") && message.contains("BRK-R-VER@<version>"))
				.anyMatch(message -> message.contains("version 9 of rule BRK-R-VER"))
				.anyMatch(message -> message.contains("rule BRK-R-VER v2 is already declared"));
	}

	@Test
	void reportsMetadataIncompatibleWithTheElementType() {
		var exception = readBroken(List.of(BrokenProcesses.IncompatibleMetadata.class));
		assertThat(exception.violations()).extracting(ModelViolation::kind)
				.contains(INCOMPATIBLE_METADATA, UNKNOWN_BUSINESS_RULE_REF, UNKNOWN_MESSAGE_REF);
		assertThat(exception.violations()).extracting(ModelViolation::message)
				.anyMatch(message -> message.contains("a business rule task must reference at least one business rule"))
				.anyMatch(message -> message.contains("only allowed on the outgoing sequence flows of an exclusive gateway"))
				.anyMatch(message -> message.contains("Spring event listener"));
	}

	@Test
	void gatewayOutgoingFlowsMustMatchTheEnumReturnedByTheCode() {
		var exception = readBroken(List.of(BrokenProcesses.DecisionMismatch.class));
		assertThat(exception.violations()).filteredOn(violation -> violation.kind() == GATEWAY_OUTGOING_MISMATCH)
				.extracting(ModelViolation::message)
				.anyMatch(message -> message.contains("'MAYBE' is not a constant"))
				.anyMatch(message -> message.contains("without an outgoing sequence flow: [NO]"))
				.anyMatch(message -> message.contains("it returns boolean"));
	}

	@Test
	void reportsUnreachableNodes() {
		assertViolations(List.of(BrokenProcesses.Unreachable.class), UNREACHABLE_FLOW_NODE);
	}

	@Test
	void aCycleThroughAGatewayAndAStandardLoopAreReadWithASingleToken() {
		var process = AnnotationProcessModelReader.read(List.of(ValidProcesses.Documents.class)).process("FX-DOCS")
				.orElseThrow();

		assertThat(process.sequenceFlow("DOC-COMPLETE", "DOC-UPLOAD")).isPresent();
		assertThat(process.flowNode("DOC-UPLOAD").orElseThrow()).isInstanceOfSatisfying(FlowNode.UserTask.class,
				task -> assertThat(task.loopCharacteristics())
						.contains(new StandardLoopCharacteristics("A document is not legible", 3)));
	}

	@Test
	void aTimerStartEventTakesItsScheduleFromSpringScheduledWithPlaceholdersResolved() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.NightlyCleanup.class,
				ValidProcesses.Polling.class), value -> value.replace("${fixture.nightly.cron:0 0 2 * * *}", "0 30 1 * * *"));

		var nightly = definitions.process("FX-NIGHTLY").orElseThrow().startEvent();
		assertThat(nightly.timer()).contains(TimerEventDefinition.cron("0 30 1 * * *", Optional.of("Europe/Madrid")));
		assertThat(nightly.messageRef()).isEmpty();
		var polling = definitions.process("FX-POLLING").orElseThrow().startEvent();
		assertThat(polling.timer()).contains(TimerEventDefinition.periodic(TimerEventDefinition.Schedule.FIXED_RATE,
				Duration.ofMinutes(5), Optional.of(Duration.ofSeconds(30))));
		assertThat(polling.timer().orElseThrow().timeCycle()).isEqualTo("R/PT5M");
	}

	@Test
	void aTimerStartEventNeedsAnActiveScheduled() {
		var exception = readBroken(List.of(BrokenProcesses.TimerWithoutSchedule.class, BrokenProcesses.DisabledTimer.class));
		assertThat(exception.violations()).extracting(ModelViolation::message)
				.anyMatch(message -> message.equals("a timer start event requires exactly one @Scheduled on the same method,"
						+ " found 0"))
				.anyMatch(message -> message.equals("the @Scheduled of a timer start event is disabled (cron = \"-\")"));
	}

	@Test
	void aCycleWithoutExitIsRejected() {
		var exception = readBroken(List.of(BrokenProcesses.CycleWithoutExit.class));
		assertThat(exception.violations()).filteredOn(violation -> violation.kind() == NO_PATH_TO_END_EVENT)
				.extracting(ModelViolation::message)
				.containsExactlyInAnyOrder("no end event can be reached from flow node NX-PING: a cycle without exit",
						"no end event can be reached from flow node NX-PONG: a cycle without exit");
	}

	@Test
	void reportsStandardLoopsWithoutConditionWithANegativeMaximumOrDeclaredTwice() {
		var exception = readBroken(List.of(BrokenProcesses.BrokenLoops.class));
		assertThat(exception.violations()).extracting(ModelViolation::message)
				.anyMatch(message -> message.equals("the standard loop does not declare its loopCondition"))
				.anyMatch(message -> message.equals("loopMaximum must be zero (unlimited) or positive, not -1"))
				.anyMatch(message -> message.equals("a task declares at most one standard loop"));
	}

	@Test
	void diagnosticNamesTheProblemAndItsLocation() {
		var exception = readBroken(List.of(BrokenProcesses.UnknownTarget.class));
		assertThat(exception.getMessage())
				.contains("Invalid process model")
				.contains("[UNKNOWN_TARGET_REF]")
				.contains("BrokenProcesses$UnknownTarget#start()")
				.contains("UT-MISSING");
	}

	private static void assertViolations(List<Class<?>> classes, ViolationKind expected) {
		assertThat(readBroken(classes).violations()).extracting(ModelViolation::kind).contains(expected);
	}

	private static ProcessModelException readBroken(List<Class<?>> classes) {
		var thrown = org.assertj.core.api.Assertions.catchThrowable(() -> AnnotationProcessModelReader.read(classes));
		assertThat(thrown).isInstanceOf(ProcessModelException.class);
		return (ProcessModelException) thrown;
	}

	@Test
	void invalidModelProducesNoLandscape() {
		assertThatThrownBy(() -> AnnotationProcessModelReader.read(List.of(BrokenProcesses.MissingEnd.class)))
				.isInstanceOf(ProcessModelException.class);
	}
}
