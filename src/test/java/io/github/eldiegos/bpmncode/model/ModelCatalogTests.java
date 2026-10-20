package io.github.eldiegos.bpmncode.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import fixture.process.ValidProcesses;
import fixture.process.VersionedMessages;

import io.github.eldiegos.bpmncode.i18n.MessageSourceLabelResolver;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Catálogos sobre el modelo: dónde se usa cada regla, contratos de mensaje y textos desde un {@code MessageSource}.
 */
class ModelCatalogTests {

	private static final Definitions MESSAGES = AnnotationProcessModelReader.read(List.of(
			VersionedMessages.PaymentReceived.class, VersionedMessages.PaymentReceivedV1.class,
			VersionedMessages.PaymentReceivedV2.class, VersionedMessages.BillingV1.class, VersionedMessages.BillingV2.class,
			VersionedMessages.Fulfilment.class));

	@Test
	void eachVersionOfARuleKnowsTheNodesThatApplyIt() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.VersionedLimitRule.class,
				ValidProcesses.Limits.class));

		assertThat(definitions.usagesOf(BusinessRuleRef.of("FX-R-002", 2)))
				.extracting(usage -> usage.process(), usage -> usage.flowNode().id())
				.containsExactly(tuple(ProcessDefinitionRef.of("FX-LIMITS", 1), "LIM-NEW"));
		assertThat(definitions.usagesOf(BusinessRuleRef.of("FX-R-404", 1))).isEmpty();
		assertThat(BusinessRuleRef.join(List.of(BusinessRuleRef.of("A", 1), BusinessRuleRef.of("B", 2),
				BusinessRuleRef.of("A", 1)))).isEqualTo("A@1, B@2");
	}

	@Test
	void theMessageCatalogListsVersionsFieldsPublishersAndConsumers() {
		var v1 = MESSAGES.message("FX-EV-PAID").orElseThrow().version(1).orElseThrow();
		var lock = new MessageContractLock(Map.of("FX-EV-PAID@1", v1.schema().fingerprint()));

		var contract = new MessageContractCatalog(MESSAGES, Optional.of(lock)).contract("FX-EV-PAID").orElseThrow();

		assertThat(contract.versions()).extracting(version -> version.ref().version(), MessageContractCatalog.Version::lockStatus)
				.containsExactly(tuple(1, MessageContractCatalog.LockStatus.FROZEN),
						tuple(2, MessageContractCatalog.LockStatus.NOT_FROZEN));
		assertThat(contract.versions().get(1).fields())
				.extracting(MessageContractCatalog.Field::name, MessageContractCatalog.Field::type,
						MessageContractCatalog.Field::optional, MessageContractCatalog.Field::header)
				.containsExactly(tuple("header", "MessageHeader", false, true), tuple("orderId", "String", false, false),
						tuple("amount", "Amount", false, false), tuple("reference", "String", true, false),
						tuple("lines", "List", false, false));
		assertThat(contract.versions().get(1).publishers()).extracting(MessageContractCatalog.Publisher::process)
				.containsExactly(ProcessDefinitionRef.of("FX-BILLING", 2));
		assertThat(contract.versions().get(1).lockLine()).startsWith("FX-EV-PAID@2 = sha256:");
		assertThat(contract.consumers()).singleElement().satisfies(consumer -> {
			assertThat(consumer.process()).isEqualTo(ProcessDefinitionRef.of("FX-FULFILMENT", 1));
			assertThat(consumer.acceptedVersions()).containsExactly(1, 2);
		});
		assertThat(new MessageContractCatalog(MESSAGES, Optional.empty()).contracts().getFirst().versions())
				.allSatisfy(version -> assertThat(version.lockStatus()).isEqualTo(MessageContractCatalog.LockStatus.NO_LOCK_FILE));
	}

	@Test
	void aSendTaskIsAPublisherOfItsMessageVersion() {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.InvoiceRequested.class,
				ValidProcesses.Subscription.class, ValidProcesses.Invoicing.class));

		var contract = new MessageContractCatalog(definitions, Optional.empty()).contract("FX-EV-INVOICE-REQUESTED")
				.orElseThrow();

		assertThat(contract.versions().getFirst().publishers())
				.extracting(MessageContractCatalog.Publisher::process, publisher -> publisher.flowNode().id())
				.containsExactly(tuple(ProcessDefinitionRef.of("FX-SUBSCRIPTION", 1), "FX-REQUEST-INVOICE"));
		assertThat(contract.consumers()).extracting(MessageContractCatalog.Consumer::process)
				.containsExactly(ProcessDefinitionRef.of("FX-INVOICING", 1));
	}

	@Test
	void labelsComeFromAMessageSourceWithTheModelTextAsFallback() {
		var messages = new StaticMessageSource();
		messages.addMessage("pac.message.FX-EV-PAID.name", Locale.forLanguageTag("es"), "Pago recibido");
		var message = MESSAGES.message("FX-EV-PAID").orElseThrow();

		assertThat(new MessageSourceLabelResolver(messages, Locale.forLanguageTag("es")).messageName(message))
				.isEqualTo("Pago recibido");
		assertThat(new MessageSourceLabelResolver(messages, Locale.ENGLISH).messageName(message)).isEqualTo("Payment received");
		assertThat(new MessageSourceLabelResolver(messages, Locale.ENGLISH).resolve("missing", "Order {0}", 7))
				.isEqualTo("Order 7");
	}
}
