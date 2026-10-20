package io.github.eldiegos.bpmncode.model.reader;

import static io.github.eldiegos.bpmncode.model.ViolationKind.INVALID_MESSAGE_VERSION;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MESSAGE_CONTRACT_CHANGED;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MESSAGE_CONTRACT_NOT_LOCKED;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MESSAGE_CONTRACT_REMOVED;
import static io.github.eldiegos.bpmncode.model.ViolationKind.MESSAGE_VERSION_NOT_ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import fixture.process.VersionedMessages;

import io.github.eldiegos.bpmncode.model.Definitions;
import io.github.eldiegos.bpmncode.model.FlowNode;
import io.github.eldiegos.bpmncode.model.MessageContractLock;
import io.github.eldiegos.bpmncode.model.MessageFlow;
import io.github.eldiegos.bpmncode.model.MessageVersionDefinition;
import io.github.eldiegos.bpmncode.model.MessageVersionRef;
import io.github.eldiegos.bpmncode.model.ModelViolation;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessModelException;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Contratos de mensaje: versiones selladas, esquemas con huella y fichero de bloqueo.
 */
class MessageContractTests {

	private static final List<Class<?>> VALID = List.of(VersionedMessages.PaymentReceived.class,
			VersionedMessages.PaymentReceivedV1.class, VersionedMessages.PaymentReceivedV2.class,
			VersionedMessages.BillingV1.class, VersionedMessages.BillingV2.class, VersionedMessages.Fulfilment.class);

	@Test
	void aSealedFamilyDeclaresOneVersionPerRecord() {
		var message = read(VALID).message("FX-EV-PAID").orElseThrow();

		assertThat(message.itemDefinition().structureRef()).isEqualTo(VersionedMessages.PaymentReceived.class.getCanonicalName());
		assertThat(message.versions()).extracting(MessageVersionDefinition::version).containsExactly(1, 2);
		assertThat(message.version(2).orElseThrow().itemDefinition().id()).isEqualTo("ItemDefinition_FX-EV-PAID_v2");
		assertThat(message.version(2).orElseThrow().itemDefinition().structureRef())
				.isEqualTo(VersionedMessages.PaymentReceivedV2.class.getCanonicalName());
	}

	@Test
	void eachPublisherSendsAConcreteVersionAndTheFamilyAcceptsAll() {
		var definitions = read(VALID);

		var start = (FlowNode.StartEvent) definitions.process("FX-FULFILMENT").orElseThrow().startEvent();
		assertThat(start.messageDefinition().orElseThrow().versions()).containsExactly(1, 2);
		assertThat(definitions.messageFlows()).extracting(MessageFlow::sourceProcess, MessageFlow::version).containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(ProcessDefinitionRef.of("FX-BILLING", 1), 1),
				org.assertj.core.groups.Tuple.tuple(ProcessDefinitionRef.of("FX-BILLING", 2), 2));
		assertThat(definitions.messageVersionOf(VersionedMessages.PaymentReceivedV2.class))
				.contains(new MessageVersionRef("FX-EV-PAID", 2));
		assertThat(definitions.messageVersionOf(VersionedMessages.PaymentReceived.class)).isEmpty();
	}

	@Test
	void theSchemaDescribesThePayloadWithoutTheHeader() {
		var v2 = read(VALID).message("FX-EV-PAID").orElseThrow().version(2).orElseThrow().schema();

		assertThat(v2.json())
				.contains("\"$id\": \"urn:pac:message:FX-EV-PAID:2\"")
				.contains("\"enum\": [\"EUR\", \"USD\"]")
				.contains("\"required\": [\"orderId\", \"amount\", \"lines\"]")
				.contains("\"additionalProperties\": false")
				.doesNotContain("header");
		assertThat(v2.fingerprint()).matches("sha256:[0-9a-f]{64}");
		assertThat(read(VALID).message("FX-EV-PAID").orElseThrow().version(2).orElseThrow().schema()).isEqualTo(v2);
	}

	@Test
	void aConsumerMustAcceptEveryPublishedVersion() {
		var classes = new java.util.ArrayList<>(VALID);
		classes.add(VersionedMessages.StrictFulfilment.class);

		var violations = violations(classes, Optional.empty());

		assertThat(violations).filteredOn(violation -> violation.kind() == MESSAGE_VERSION_NOT_ACCEPTED).singleElement()
				.satisfies(violation -> assertThat(violation.message()).contains("FX-BILLING v2").contains("version 2"));
	}

	@Test
	void anEndEventPublishesAConcreteVersion() {
		var classes = new java.util.ArrayList<>(VALID);
		classes.add(VersionedMessages.VaguePublisher.class);

		assertThat(kinds(classes, Optional.empty())).contains(INVALID_MESSAGE_VERSION);
	}

	@Test
	void aSendTaskSendsAConcreteVersion() {
		var classes = new java.util.ArrayList<>(VALID);
		classes.add(VersionedMessages.VagueSender.class);

		assertThat(violations(classes, Optional.empty())).filteredOn(violation -> violation.kind() == INVALID_MESSAGE_VERSION)
				.singleElement().satisfies(violation -> assertThat(violation.message())
						.contains("a send task sends one concrete version"));
	}

	@Test
	void reportsBadlyDeclaredVersions() {
		var violations = violations(List.of(VersionedMessages.WithoutHeader.class, VersionedMessages.WithLegacyDate.class,
				VersionedMessages.Twice.class, VersionedMessages.TwiceA.class, VersionedMessages.TwiceB.class,
				VersionedMessages.Orphan.class), Optional.empty());

		assertThat(violations).extracting(ModelViolation::kind).containsOnly(INVALID_MESSAGE_VERSION);
		assertThat(violations).extracting(ModelViolation::message).anySatisfy(text -> assertThat(text).contains("ProcessMessage"))
				.anySatisfy(text -> assertThat(text).contains("java.util.Date"))
				.anySatisfy(text -> assertThat(text).contains("version 1 of FX-EV-TWICE is already declared"))
				.anySatisfy(text -> assertThat(text).contains("@MessageVersion is only allowed"));
	}

	@Test
	void aMatchingLockAcceptsTheModel() {
		var lock = lockOf(read(VALID));

		assertThat(AnnotationProcessModelReader.read(VALID, UnaryOperator.identity(), Optional.of(lock))
				.message("FX-EV-PAID")).isPresent();
	}

	@Test
	void anUnlockedVersionTellsTheLineToAdd() {
		var definitions = read(VALID);
		var v2 = definitions.message("FX-EV-PAID").orElseThrow().version(2).orElseThrow();
		var lock = new MessageContractLock(Map.of("FX-EV-PAID@1",
				definitions.message("FX-EV-PAID").orElseThrow().version(1).orElseThrow().schema().fingerprint()));

		assertThat(violations(VALID, Optional.of(lock))).singleElement().satisfies(violation -> {
			assertThat(violation.kind()).isEqualTo(MESSAGE_CONTRACT_NOT_LOCKED);
			assertThat(violation.message()).contains("FX-EV-PAID@2 = " + v2.schema().fingerprint());
		});
	}

	@Test
	void aChangedOrRemovedVersionIsRejected() {
		var fingerprints = new java.util.HashMap<>(lockOf(read(VALID)).fingerprints());
		fingerprints.put("FX-EV-PAID@1", "sha256:" + "0".repeat(64));
		fingerprints.put("FX-EV-PAID@3", "sha256:" + "1".repeat(64));

		assertThat(kinds(VALID, Optional.of(new MessageContractLock(fingerprints))))
				.containsExactlyInAnyOrder(MESSAGE_CONTRACT_CHANGED, MESSAGE_CONTRACT_REMOVED);
	}

	@Test
	void theLockFileIsParsedAndMalformedLinesAreRejected() {
		var lock = MessageContractLock.parse("""
				# Contratos congelados
				FX-EV-PAID@1 = sha256:%s

				FX-EV-PAID@2=sha256:%s
				""".formatted("a".repeat(64), "b".repeat(64)), "test.lock");

		assertThat(lock.fingerprints()).containsOnlyKeys("FX-EV-PAID@1", "FX-EV-PAID@2");
		assertThatThrownBy(() -> MessageContractLock.parse("FX-EV-PAID = 1", "bad.lock"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad.lock:1");
		assertThatThrownBy(() -> lock.merge(new MessageContractLock(Map.of("FX-EV-PAID@1", "sha256:" + "c".repeat(64)))))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static Definitions read(List<Class<?>> classes) {
		return AnnotationProcessModelReader.read(classes);
	}

	private static MessageContractLock lockOf(Definitions definitions) {
		var fingerprints = new java.util.HashMap<String, String>();
		definitions.messages().forEach(message -> message.versions().forEach(version -> fingerprints
				.put(version.lockKey(message.id()), version.schema().fingerprint())));
		return new MessageContractLock(fingerprints);
	}

	private static List<ModelViolation> violations(List<Class<?>> classes, Optional<MessageContractLock> lock) {
		return catchThrowableOfType(ProcessModelException.class,
				() -> AnnotationProcessModelReader.read(classes, UnaryOperator.identity(), lock)).violations();
	}

	private static List<ViolationKind> kinds(List<Class<?>> classes, Optional<MessageContractLock> lock) {
		return violations(classes, lock).stream().map(ModelViolation::kind).toList();
	}
}
