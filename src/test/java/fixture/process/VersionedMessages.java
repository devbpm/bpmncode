package fixture.process;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.context.event.EventListener;

import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.Message;
import io.github.eldiegos.bpmncode.annotation.MessageEndEvent;
import io.github.eldiegos.bpmncode.annotation.MessageStartEvent;
import io.github.eldiegos.bpmncode.annotation.MessageVersion;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.SendTask;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.runtime.MessageHeader;
import io.github.eldiegos.bpmncode.runtime.ProcessMessage;

/**
 * Mensajes con varias versiones (familia sellada) y los procesos que las publican y consumen, válidos y erróneos.
 */
public final class VersionedMessages {

	private VersionedMessages() {
	}

	public enum Currency {
		EUR, USD
	}

	public record Amount(BigDecimal value, Currency currency) {
	}

	@Message(id = "FX-EV-PAID", name = "Payment received", documentation = "An order has been paid.")
	public sealed interface PaymentReceived extends ProcessMessage permits PaymentReceivedV1, PaymentReceivedV2 {

		String orderId();
	}

	@MessageVersion(1)
	public record PaymentReceivedV1(MessageHeader header, String orderId, BigDecimal amount) implements PaymentReceived {
	}

	@MessageVersion(2)
	public record PaymentReceivedV2(MessageHeader header, String orderId, Amount amount, Optional<String> reference,
			List<String> lines) implements PaymentReceived {
	}

	/** Versión 1 del cobro: publica la versión 1 del mensaje. */
	@Process(id = "FX-BILLING", name = "Billing", version = 1)
	public static class BillingV1 {

		@StartEvent(id = "FX-BILL-START", name = "Bill", outgoing = @SequenceFlow(targetRef = "FX-PAID"))
		void start() {
		}

		@MessageEndEvent(id = "FX-PAID", name = "Paid", messageRef = PaymentReceivedV1.class)
		void paid() {
		}
	}

	/** Versión 2 del cobro: publica la versión 2 del mensaje. */
	@Process(id = "FX-BILLING", name = "Billing", version = 2)
	public static class BillingV2 {

		@StartEvent(id = "FX-BILL-START", name = "Bill", outgoing = @SequenceFlow(targetRef = "FX-PAID"))
		void start() {
		}

		@MessageEndEvent(id = "FX-PAID", name = "Paid", messageRef = PaymentReceivedV2.class)
		void paid() {
		}
	}

	/** Acepta la familia: todas las versiones. */
	@Process(id = "FX-FULFILMENT", name = "Fulfilment", version = 1)
	public static class Fulfilment {

		@EventListener
		@MessageStartEvent(id = "FX-FUL-START", name = "Payment received", messageRef = PaymentReceived.class,
				outgoing = @SequenceFlow(targetRef = "FX-FUL-SEND"))
		void on(PaymentReceived payment) {
		}

		@ServiceTask(id = "FX-FUL-SEND", name = "Send goods", outgoing = @SequenceFlow(targetRef = "FX-FUL-DONE"))
		void send() {
		}

		@EndEvent(id = "FX-FUL-DONE", name = "Sent")
		void done() {
		}
	}

	/** Solo acepta la versión 1, pero el cobro v2 publica la 2. */
	@Process(id = "FX-STRICT", name = "Strict fulfilment", version = 1)
	public static class StrictFulfilment {

		@EventListener
		@MessageStartEvent(id = "FX-STRICT-START", name = "Payment received", messageRef = PaymentReceivedV1.class,
				outgoing = @SequenceFlow(targetRef = "FX-STRICT-DONE"))
		void on(PaymentReceivedV1 payment) {
		}

		@EndEvent(id = "FX-STRICT-DONE", name = "Done")
		void done() {
		}
	}

	/** Un evento de fin que publica la familia, no una versión concreta. */
	@Process(id = "FX-VAGUE", name = "Vague publisher", version = 1)
	public static class VaguePublisher {

		@StartEvent(id = "FX-VAGUE-START", name = "Start", outgoing = @SequenceFlow(targetRef = "FX-VAGUE-END"))
		void start() {
		}

		@MessageEndEvent(id = "FX-VAGUE-END", name = "End", messageRef = PaymentReceived.class)
		void end() {
		}
	}

	/** Una tarea de envío que envía la familia, no una versión concreta. */
	@Process(id = "FX-VAGUE-SENDER", name = "Vague sender", version = 1)
	public static class VagueSender {

		@StartEvent(id = "FX-VS-START", name = "Start", outgoing = @SequenceFlow(targetRef = "FX-VS-SEND"))
		void start() {
		}

		@SendTask(id = "FX-VS-SEND", name = "Send", messageRef = PaymentReceived.class,
				outgoing = @SequenceFlow(targetRef = "FX-VS-END"))
		void send() {
		}

		@EndEvent(id = "FX-VS-END", name = "End")
		void end() {
		}
	}

	@Message(id = "FX-EV-NO-HEADER", name = "Without header")
	public record WithoutHeader(String value) {
	}

	@Message(id = "FX-EV-DATE", name = "With a legacy date")
	public record WithLegacyDate(MessageHeader header, Date when) implements ProcessMessage {
	}

	@Message(id = "FX-EV-TWICE", name = "Version declared twice")
	public sealed interface Twice extends ProcessMessage permits TwiceA, TwiceB {
	}

	@MessageVersion(1)
	public record TwiceA(MessageHeader header) implements Twice {
	}

	@MessageVersion(1)
	public record TwiceB(MessageHeader header, String extra) implements Twice {
	}

	@MessageVersion(3)
	public record Orphan(MessageHeader header) implements ProcessMessage {
	}
}
