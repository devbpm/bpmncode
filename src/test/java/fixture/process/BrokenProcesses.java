package fixture.process;

import org.springframework.scheduling.annotation.Scheduled;

import io.github.eldiegos.bpmncode.annotation.BusinessRule;
import io.github.eldiegos.bpmncode.annotation.BusinessRuleTask;
import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ErrorEndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.HumanPerformer;
import io.github.eldiegos.bpmncode.annotation.MessageEndEvent;
import io.github.eldiegos.bpmncode.annotation.MessageStartEvent;
import io.github.eldiegos.bpmncode.annotation.PotentialOwner;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.ProcessError;
import io.github.eldiegos.bpmncode.annotation.ProcessPart;
import io.github.eldiegos.bpmncode.annotation.SequenceFlow;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StandardLoop;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.annotation.TimerStartEvent;
import io.github.eldiegos.bpmncode.annotation.UserTask;

/**
 * Definiciones de proceso incompletas o incoherentes, con un problema cada una, para probar los diagnósticos del lector.
 */
public final class BrokenProcesses {

	private BrokenProcesses() {
	}

	public enum YesNo {
		YES, NO
	}

	public record NotAMessage() {
	}

	@Process(id = "BRK-DUP", name = "Duplicate ids", version = 1)
	public static class DuplicateIds {

		@StartEvent(id = "DUP-START", name = "Start", outgoing = @SequenceFlow(targetRef = "DUP-STEP"))
		void start() {
		}

		@ServiceTask(id = "DUP-STEP", name = "Step", outgoing = @SequenceFlow(targetRef = "DUP-END"))
		void step() {
		}

		@ServiceTask(id = "DUP-STEP", name = "Another step", outgoing = @SequenceFlow(targetRef = "DUP-END"))
		void otherStep() {
		}

		@EndEvent(id = "DUP-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-NOSTART", name = "No start", version = 1)
	public static class MissingStart {

		@ServiceTask(id = "NS-STEP", name = "Step", outgoing = @SequenceFlow(targetRef = "NS-END"))
		void step() {
		}

		@EndEvent(id = "NS-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-NOEND", name = "No end", version = 1)
	public static class MissingEnd {

		@StartEvent(id = "NE-START", name = "Start", outgoing = @SequenceFlow(targetRef = "NE-STEP"))
		void start() {
		}

		@ServiceTask(id = "NE-STEP", name = "Step", outgoing = @SequenceFlow(targetRef = "NE-START"))
		void step() {
		}
	}

	@Process(id = "BRK-TARGET", name = "Broken sequence flow", version = 1)
	public static class UnknownTarget {

		@StartEvent(id = "UT-START", name = "Start", outgoing = @SequenceFlow(targetRef = "UT-MISSING"))
		void start() {
		}

		@EndEvent(id = "UT-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-UNIDENTIFIED", name = "Elements without id", version = 1)
	public static class Unidentified {

		@StartEvent(id = "UN-START", name = "Start", outgoing = @SequenceFlow(targetRef = "UN-DECIDE"))
		void start() {
		}

		@ExclusiveGateway(id = "", name = "Decision without id", outgoing = {
				@SequenceFlow(when = "YES", targetRef = "UN-END"), @SequenceFlow(when = "NO", targetRef = "UN-END") })
		YesNo decide() {
			return YesNo.YES;
		}

		@BusinessRuleTask(id = "UN-RULES", name = "Rules", businessRuleRefs = "",
				outgoing = @SequenceFlow(targetRef = "UN-END"))
		void rules() {
		}

		@EndEvent(id = "UN-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-AMBIGUOUS", name = "Ambiguous process", version = 1)
	public static class AmbiguousFirst {

		@StartEvent(id = "AM-START", name = "Start", outgoing = @SequenceFlow(targetRef = "AM-END"))
		@EndEvent(id = "AM-END", name = "End")
		void startAndEnd() {
		}
	}

	@Process(id = "BRK-AMBIGUOUS", name = "The same process again", version = 1)
	public static class AmbiguousSecond {
	}

	/** Declara una tarea pero ni @Process ni @ProcessPart. */
	public static class OrphanActivity {

		@ServiceTask(id = "OR-STEP", name = "Orphan step", outgoing = @SequenceFlow(targetRef = "OR-END"))
		void step() {
		}
	}

	@ProcessPart(processRef = "BRK-DOES-NOT-EXIST")
	public static class ComponentOfUnknownProcess {
	}

	@Process(id = "BRK-INCOMPATIBLE", name = "Incompatible metadata", version = 1)
	public static class IncompatibleMetadata {

		@MessageStartEvent(id = "IN-START", name = "Start", outgoing = @SequenceFlow(targetRef = "IN-RULES"),
				messageRef = NotAMessage.class)
		void start() {
		}

		@BusinessRuleTask(id = "IN-RULES", name = "Rules without rules", businessRuleRefs = {},
				outgoing = @SequenceFlow(targetRef = "IN-TASK"))
		void rulesWithoutRules() {
		}

		@ServiceTask(id = "IN-TASK", name = "Conditional task", businessRuleRefs = "R-UNKNOWN",
				outgoing = @SequenceFlow(targetRef = "IN-END", when = "YES"))
		void conditionalTask() {
		}

		@MessageEndEvent(id = "IN-END", name = "End", messageRef = NotAMessage.class)
		void end() {
		}
	}

	@Process(id = "BRK-DECISION", name = "Misaligned decision", version = 1)
	public static class DecisionMismatch {

		@StartEvent(id = "DM-START", name = "Start", outgoing = @SequenceFlow(targetRef = "DM-DECIDE"))
		void start() {
		}

		@ExclusiveGateway(id = "DM-DECIDE", name = "Yes?", outgoing = {
				@SequenceFlow(when = "YES", targetRef = "DM-END"), @SequenceFlow(when = "MAYBE", targetRef = "DM-END") })
		YesNo decide() {
			return YesNo.YES;
		}

		@ExclusiveGateway(id = "DM-BOOLEAN", name = "Boolean?", outgoing = {
				@SequenceFlow(when = "TRUE", targetRef = "DM-END"), @SequenceFlow(when = "FALSE", targetRef = "DM-END") })
		boolean decideWithBoolean() {
			return true;
		}

		@EndEvent(id = "DM-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-UNREACHABLE", name = "Unreachable node", version = 1)
	public static class Unreachable {

		@StartEvent(id = "UR-START", name = "Start", outgoing = @SequenceFlow(targetRef = "UR-END"))
		void start() {
		}

		@ServiceTask(id = "UR-ISLAND", name = "Island", outgoing = @SequenceFlow(targetRef = "UR-END"))
		void island() {
		}

		@EndEvent(id = "UR-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-CROSS", name = "Sequence flow between processes", version = 1)
	public static class CrossProcessSequenceFlow {

		@StartEvent(id = "CR-START", name = "Start", outgoing = @SequenceFlow(targetRef = "FX-DONE"))
		void start() {
		}

		@EndEvent(id = "CR-END", name = "End")
		void end() {
		}
	}
	/** Regla con dos versiones, referenciada sin versión y con una versión que no existe. */
	public static final class TwoVersionRule {

		@BusinessRule(id = "BRK-R-VER", name = "Rule v1")
		static boolean first() {
			return true;
		}

		@BusinessRule(id = "BRK-R-VER", version = 2, name = "Rule v2")
		static boolean second() {
			return true;
		}

		@BusinessRule(id = "BRK-R-VER", version = 2, name = "Rule v2 again")
		static boolean secondAgain() {
			return true;
		}
	}

	@Process(id = "BRK-RULE-VERSIONS", name = "Rule versions", version = 1)
	public static class RuleVersionMistakes {

		@StartEvent(id = "RV-START", name = "Start", outgoing = @SequenceFlow(targetRef = "RV-AMBIGUOUS"))
		void start() {
		}

		@BusinessRuleTask(id = "RV-AMBIGUOUS", name = "Ambiguous", businessRuleRefs = "BRK-R-VER",
				outgoing = @SequenceFlow(targetRef = "RV-MISSING"))
		void ambiguous() {
		}

		@BusinessRuleTask(id = "RV-MISSING", name = "Missing version", businessRuleRefs = "BRK-R-VER@9",
				outgoing = @SequenceFlow(targetRef = "RV-END"))
		void missing() {
		}

		@EndEvent(id = "RV-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-NO-EXIT", name = "Cycle without exit", version = 1)
	public static class CycleWithoutExit {

		@StartEvent(id = "NX-START", name = "Start", outgoing = @SequenceFlow(targetRef = "NX-CHOOSE"))
		void start() {
		}

		@ExclusiveGateway(id = "NX-CHOOSE", name = "Which way?", outgoing = {
				@SequenceFlow(when = "YES", targetRef = "NX-END"), @SequenceFlow(when = "NO", targetRef = "NX-PING") })
		YesNo choose() {
			return YesNo.YES;
		}

		@ServiceTask(id = "NX-PING", name = "Ping", outgoing = @SequenceFlow(targetRef = "NX-PONG"))
		void ping() {
		}

		@ServiceTask(id = "NX-PONG", name = "Pong", outgoing = @SequenceFlow(targetRef = "NX-PING"))
		void pong() {
		}

		@EndEvent(id = "NX-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-LOOPS", name = "Broken loops", version = 1)
	public static class BrokenLoops {

		@StartEvent(id = "BL-START", name = "Start", outgoing = @SequenceFlow(targetRef = "BL-BLANK"))
		void start() {
		}

		@ServiceTask(id = "BL-BLANK", name = "Loop without condition", outgoing = @SequenceFlow(targetRef = "BL-NEGATIVE"),
				loop = @StandardLoop(loopCondition = " "))
		void blank() {
		}

		@ServiceTask(id = "BL-NEGATIVE", name = "Negative maximum", outgoing = @SequenceFlow(targetRef = "BL-TWICE"),
				loop = @StandardLoop(loopCondition = "Again", loopMaximum = -1))
		void negative() {
		}

		@UserTask(id = "BL-TWICE", name = "Two loops", outgoing = @SequenceFlow(targetRef = "BL-END"),
				loop = { @StandardLoop(loopCondition = "Again"), @StandardLoop(loopCondition = "Once more") })
		void twice() {
		}

		@EndEvent(id = "BL-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-TIMER-NO-SCHEDULE", name = "Timer without schedule", version = 1)
	public static class TimerWithoutSchedule {

		@TimerStartEvent(id = "TNS-START", name = "Never", outgoing = @SequenceFlow(targetRef = "TNS-END"))
		void never() {
		}

		@EndEvent(id = "TNS-END", name = "End")
		void end() {
		}
	}

	@Process(id = "BRK-TIMER-DISABLED", name = "Disabled timer", version = 1)
	public static class DisabledTimer {

		@Scheduled(cron = "-")
		@TimerStartEvent(id = "TDI-START", name = "Disabled", outgoing = @SequenceFlow(targetRef = "TDI-END"))
		void disabled() {
		}

		@EndEvent(id = "TDI-END", name = "End")
		void end() {
		}
	}

	/** Roles de una tarea humana sin decir quién, diciéndolo dos veces o con un recurso no declarado. */
	@Process(id = "BRK-ROLES", name = "Broken roles", version = 1)
	public static class BrokenRoles {

		@StartEvent(id = "BR-START", name = "Start", outgoing = @SequenceFlow(targetRef = "BR-TASK"))
		void start() {
		}

		@UserTask(id = "BR-TASK", name = "Task",
				potentialOwners = { @PotentialOwner, @PotentialOwner(resourceRef = String.class, expression = "anyone") },
				humanPerformers = @HumanPerformer(resourceRef = Integer.class),
				outgoing = @SequenceFlow(targetRef = "BR-END"))
		void task() {
		}

		@EndEvent(id = "BR-END", name = "End")
		void end() {
		}
	}

	/** Un error declarado en una clase que no es una excepción. */
	@ProcessError(id = "BRK-ERR-NOT-EXCEPTION", name = "Not an exception", errorCode = "NOT_AN_EXCEPTION")
	public static final class NotAnException {
	}

	/** Un evento de fin de error cuyo {@code errorRef} no es un {@code @ProcessError}. */
	@Process(id = "BRK-ERROR", name = "Undeclared error", version = 1)
	public static class UndeclaredError {

		@StartEvent(id = "UE-START", name = "Start", outgoing = @SequenceFlow(targetRef = "UE-FAILED"))
		void start() {
		}

		@ErrorEndEvent(id = "UE-FAILED", name = "Failed", errorRef = IllegalStateException.class)
		void failed() {
		}
	}
}
