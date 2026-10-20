package fixture.process;

import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import io.github.eldiegos.bpmncode.annotation.BusinessRule;
import io.github.eldiegos.bpmncode.annotation.BusinessRuleTask;
import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ErrorEndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.HumanPerformer;
import io.github.eldiegos.bpmncode.annotation.Message;
import io.github.eldiegos.bpmncode.annotation.MessageEndEvent;
import io.github.eldiegos.bpmncode.annotation.MessageStartEvent;
import io.github.eldiegos.bpmncode.annotation.PotentialOwner;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.ProcessError;
import io.github.eldiegos.bpmncode.annotation.ProcessPart;
import io.github.eldiegos.bpmncode.annotation.ProcessResource;
import io.github.eldiegos.bpmncode.annotation.SendTask;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StandardLoop;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.annotation.TimerStartEvent;
import io.github.eldiegos.bpmncode.annotation.UserTask;
import io.github.eldiegos.bpmncode.runtime.MessageHeader;
import io.github.eldiegos.bpmncode.runtime.ProcessMessage;

/**
 * Procesos anotados válidos para probar el descubrimiento y la generación BPMN con independencia del código de la
 * aplicación. Están fuera de {@code dev.pac} para que la aplicación nunca los descubra.
 */
public final class ValidProcesses {

	private ValidProcesses() {
	}

	public enum Approval {
		ACCEPTED, DECLINED
	}

	public static final class CreditRule {

		@BusinessRule(id = "FX-R-001", name = "Credit limit")
		static boolean withinLimit(int amount) {
			return amount <= 1000;
		}
	}

	@Message(id = "FX-EV-ORDERED", name = "Order accepted")
	public record OrderAccepted(MessageHeader header, String orderId) implements ProcessMessage {
	}

	/** Versión 1 del proceso de pedidos. */
	@Process(id = "FX-ORDER", name = "Order", version = 1)
	public static class OrderV1 {

		@StartEvent(id = "FX-START", name = "Order received", outgoing = @SequenceFlow(targetRef = "FX-CHECK"))
		void receive() {
		}

		@BusinessRuleTask(id = "FX-CHECK", name = "Check credit", businessRuleRefs = "FX-R-001",
				outgoing = @SequenceFlow(targetRef = "FX-DECIDE"))
		void check() {
		}

		@ExclusiveGateway(id = "FX-DECIDE", name = "Accepted?", outgoing = {
				@SequenceFlow(when = "ACCEPTED", targetRef = "FX-CREATE", name = "Yes"),
				@SequenceFlow(when = "DECLINED", targetRef = "FX-DECLINED", name = "No") })
		Approval decide() {
			return Approval.ACCEPTED;
		}

		@ServiceTask(id = "FX-CREATE", name = "Create order", outgoing = @SequenceFlow(targetRef = "FX-DONE"))
		void create() {
		}

		@MessageEndEvent(id = "FX-DONE", name = "Order created", messageRef = OrderAccepted.class)
		void done() {
		}

		@EndEvent(id = "FX-DECLINED", name = "Order declined")
		void declined() {
		}
	}

	/** Las mismas anotaciones que {@link OrderV1} con los métodos declarados en otro orden físico. */
	@Process(id = "FX-ORDER", name = "Order", version = 1)
	public static class OrderV1Reordered {

		@EndEvent(id = "FX-DECLINED", name = "Order declined")
		void zDeclined() {
		}

		@ServiceTask(id = "FX-CREATE", name = "Create order", outgoing = @SequenceFlow(targetRef = "FX-DONE"))
		void aCreate() {
		}

		@MessageEndEvent(id = "FX-DONE", name = "Order created", messageRef = OrderAccepted.class)
		void mDone() {
		}

		@ExclusiveGateway(id = "FX-DECIDE", name = "Accepted?", outgoing = {
				@SequenceFlow(when = "ACCEPTED", targetRef = "FX-CREATE", name = "Yes"),
				@SequenceFlow(when = "DECLINED", targetRef = "FX-DECLINED", name = "No") })
		Approval bDecide() {
			return Approval.ACCEPTED;
		}

		@BusinessRuleTask(id = "FX-CHECK", name = "Check credit", businessRuleRefs = "FX-R-001",
				outgoing = @SequenceFlow(targetRef = "FX-DECIDE"))
		void yCheck() {
		}

		@StartEvent(id = "FX-START", name = "Order received", outgoing = @SequenceFlow(targetRef = "FX-CHECK"))
		void cReceive() {
		}
	}

	/** Versión 2: se inserta un paso de notificación entre la creación del pedido y el evento de fin. */
	@Process(id = "FX-ORDER", name = "Order", version = 2)
	public static class OrderV2 {

		@StartEvent(id = "FX-START", name = "Order received", outgoing = @SequenceFlow(targetRef = "FX-CHECK"))
		void receive() {
		}

		@BusinessRuleTask(id = "FX-CHECK", name = "Check credit", businessRuleRefs = "FX-R-001",
				outgoing = @SequenceFlow(targetRef = "FX-DECIDE"))
		void check() {
		}

		@ExclusiveGateway(id = "FX-DECIDE", name = "Accepted?", outgoing = {
				@SequenceFlow(when = "ACCEPTED", targetRef = "FX-CREATE", name = "Yes"),
				@SequenceFlow(when = "DECLINED", targetRef = "FX-DECLINED", name = "No") })
		Approval decide() {
			return Approval.ACCEPTED;
		}

		@ServiceTask(id = "FX-CREATE", name = "Create order", outgoing = @SequenceFlow(targetRef = "FX-NOTIFY"))
		void create() {
		}

		@ServiceTask(id = "FX-NOTIFY", name = "Notify customer", outgoing = @SequenceFlow(targetRef = "FX-DONE"))
		void notifyCustomer() {
		}

		@MessageEndEvent(id = "FX-DONE", name = "Order created", messageRef = OrderAccepted.class)
		void done() {
		}

		@EndEvent(id = "FX-DECLINED", name = "Order declined")
		void declined() {
		}
	}

	/** Un segundo proceso, iniciado por el evento del proceso de pedidos y repartido en dos clases. */
	@Process(id = "FX-SHIPPING", name = "Shipping", version = 1)
	public static class Shipping {

		@UserTask(id = "FX-PACK", name = "Pack order", outgoing = @SequenceFlow(targetRef = "FX-SHIP"))
		void pack() {
		}

		@ServiceTask(id = "FX-SHIP", name = "Ship", outgoing = @SequenceFlow(targetRef = "FX-SHIPPED"))
		void ship() {
		}

		@EndEvent(id = "FX-SHIPPED", name = "Shipped")
		void shipped() {
		}
	}

	@ProcessPart(processRef = "FX-SHIPPING")
	public static class ShippingStarter {

		@EventListener
		@MessageStartEvent(id = "FX-SHIP-START", name = "Accepted order received", messageRef = OrderAccepted.class,
				outgoing = @SequenceFlow(targetRef = "FX-PACK"))
		void on(OrderAccepted event) {
		}
	}
	@Message(id = "FX-EV-INVOICE-REQUESTED", name = "Invoice requested")
	public record InvoiceRequested(MessageHeader header, String subscriptionId) implements ProcessMessage {
	}

	/** Envía un mensaje a mitad del proceso con una tarea de envío y sigue sin esperar respuesta. */
	@Process(id = "FX-SUBSCRIPTION", name = "Subscription", version = 1)
	public static class Subscription {

		@StartEvent(id = "FX-SUB-START", name = "Subscription requested",
				outgoing = @SequenceFlow(targetRef = "FX-REQUEST-INVOICE"))
		void start() {
		}

		@SendTask(id = "FX-REQUEST-INVOICE", name = "Request invoice", messageRef = InvoiceRequested.class,
				outgoing = @SequenceFlow(targetRef = "FX-ACTIVATE"))
		void requestInvoice() {
		}

		@ServiceTask(id = "FX-ACTIVATE", name = "Activate subscription", outgoing = @SequenceFlow(targetRef = "FX-ACTIVE"))
		void activate() {
		}

		@EndEvent(id = "FX-ACTIVE", name = "Subscription active")
		void active() {
		}
	}

	/** Lo inicia el mensaje que envía la tarea de envío de {@link Subscription}. */
	@Process(id = "FX-INVOICING", name = "Invoicing", version = 1)
	public static class Invoicing {

		@EventListener
		@MessageStartEvent(id = "FX-INV-START", name = "Invoice requested", messageRef = InvoiceRequested.class,
				outgoing = @SequenceFlow(targetRef = "FX-INVOICED"))
		void on(InvoiceRequested event) {
		}

		@EndEvent(id = "FX-INVOICED", name = "Invoice issued")
		void invoiced() {
		}
	}

	@ProcessError(id = "FX-ERR-CREDIT", name = "Credit rejected", errorCode = "CREDIT_REJECTED")
	public static class CreditRejected extends RuntimeException {

		public CreditRejected(String message) {
			super(message);
		}
	}

	/** Una causa concreta del rechazo: cuenta como el error de su superclase. */
	public static class CreditRejectedForAge extends CreditRejected {

		public CreditRejectedForAge() {
			super("Applicant under age");
		}
	}

	/** Termina con un error de negocio si se rechaza el crédito. */
	@Process(id = "FX-LOAN", name = "Loan", version = 1)
	public static class Loan {

		@StartEvent(id = "FX-LOAN-START", name = "Loan requested", outgoing = @SequenceFlow(targetRef = "FX-LOAN-DECIDE"))
		void start() {
		}

		@ExclusiveGateway(id = "FX-LOAN-DECIDE", name = "Credit approved?", outgoing = {
				@SequenceFlow(when = "ACCEPTED", targetRef = "FX-LOAN-GRANTED", name = "Yes"),
				@SequenceFlow(when = "DECLINED", targetRef = "FX-LOAN-REJECTED", name = "No") })
		Approval decide() {
			return Approval.ACCEPTED;
		}

		@EndEvent(id = "FX-LOAN-GRANTED", name = "Loan granted")
		void granted() {
		}

		@ErrorEndEvent(id = "FX-LOAN-REJECTED", name = "Credit rejected", errorRef = CreditRejected.class)
		void rejected() {
		}
	}

	@ProcessResource(id = "FX-RES-REVIEWER", name = "Reviewer", documentation = "Senior member of the department")
	public record Reviewer(String department, Optional<String> language) {
	}

	/** Una tarea humana con sus roles: un recurso declarado y expresiones que resuelve la aplicación. */
	@Process(id = "FX-REVIEW", name = "Document review", version = 1)
	public static class Review {

		@StartEvent(id = "FX-REV-START", name = "Document submitted", outgoing = @SequenceFlow(targetRef = "FX-REV-REVIEW"))
		void start() {
		}

		@UserTask(id = "FX-REV-REVIEW", name = "Review document",
				potentialOwners = { @PotentialOwner(resourceRef = Reviewer.class),
						@PotentialOwner(expression = "team lead of the author") },
				humanPerformers = @HumanPerformer(expression = "the reviewer who claimed the task"),
				outgoing = @SequenceFlow(targetRef = "FX-REV-DONE"))
		void review() {
		}

		@EndEvent(id = "FX-REV-DONE", name = "Document reviewed")
		void done() {
		}
	}

	/** Dos versiones de la misma regla: el umbral cambia sin cambiar su significado. */
	public static final class VersionedLimitRule {

		@BusinessRule(id = "FX-R-002", name = "Order limit of 1000")
		static boolean withinLimit(int amount) {
			return amount <= 1000;
		}

		@BusinessRule(id = "FX-R-002", version = 2, name = "Order limit of 500")
		static boolean withinStricterLimit(int amount) {
			return amount <= 500;
		}
	}

	/** Proceso que aplica cada versión de la regla en un paso distinto, eligiéndola con {@code ID@versión}. */
	@Process(id = "FX-LIMITS", name = "Limits", version = 1)
	public static class Limits {

		@StartEvent(id = "LIM-START", name = "Start", outgoing = @SequenceFlow(targetRef = "LIM-OLD"))
		void start() {
		}

		@BusinessRuleTask(id = "LIM-OLD", name = "Old limit", businessRuleRefs = "FX-R-002@1", outgoing = @SequenceFlow(targetRef = "LIM-NEW"))
		void oldLimit() {
		}

		@BusinessRuleTask(id = "LIM-NEW", name = "New limit", businessRuleRefs = "FX-R-002@2", outgoing = @SequenceFlow(targetRef = "LIM-END"))
		void newLimit() {
		}

		@EndEvent(id = "LIM-END", name = "End")
		void end() {
		}
	}

	public enum Completeness {
		COMPLETE, INCOMPLETE
	}

	/**
	 * Ciclo por gateway y bucle estándar con un solo token: la persona sube documentos hasta tres veces seguidas y, si el
	 * expediente queda incompleto, se le vuelve a pedir.
	 */
	@Process(id = "FX-DOCS", name = "Documents", version = 1)
	public static class Documents {

		@StartEvent(id = "DOC-START", name = "Documents requested", outgoing = @SequenceFlow(targetRef = "DOC-UPLOAD"))
		void start() {
		}

		@UserTask(id = "DOC-UPLOAD", name = "Upload documents", outgoing = @SequenceFlow(targetRef = "DOC-COMPLETE"),
				loop = @StandardLoop(loopCondition = "A document is not legible", loopMaximum = 3))
		void upload() {
		}

		@ExclusiveGateway(id = "DOC-COMPLETE", name = "Complete?", outgoing = {
				@SequenceFlow(when = "COMPLETE", targetRef = "DOC-END"),
				@SequenceFlow(when = "INCOMPLETE", targetRef = "DOC-UPLOAD", name = "Ask again") })
		Completeness check() {
			return Completeness.COMPLETE;
		}

		@EndEvent(id = "DOC-END", name = "Documents complete")
		void end() {
		}
	}

	/** Inicio por temporizador cron, con la expresión y la zona tomadas de la configuración. */
	@Process(id = "FX-NIGHTLY", name = "Nightly cleanup", version = 1)
	public static class NightlyCleanup {

		@Scheduled(cron = "${fixture.nightly.cron:0 0 2 * * *}", zone = "Europe/Madrid")
		@TimerStartEvent(id = "NIG-START", name = "Every night", outgoing = @SequenceFlow(targetRef = "NIG-CLEAN"))
		void everyNight() {
		}

		@ServiceTask(id = "NIG-CLEAN", name = "Clean up", outgoing = @SequenceFlow(targetRef = "NIG-END"))
		void clean() {
		}

		@EndEvent(id = "NIG-END", name = "Cleaned")
		void end() {
		}
	}

	/** Inicio por temporizador periódico (fixedRate con espera inicial). */
	@Process(id = "FX-POLLING", name = "Polling", version = 1)
	public static class Polling {

		@Scheduled(fixedRateString = "PT5M", initialDelay = 30, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
		@TimerStartEvent(id = "POL-START", name = "Every five minutes", outgoing = @SequenceFlow(targetRef = "POL-END"))
		void poll() {
		}

		@EndEvent(id = "POL-END", name = "Polled")
		void end() {
		}
	}
}
