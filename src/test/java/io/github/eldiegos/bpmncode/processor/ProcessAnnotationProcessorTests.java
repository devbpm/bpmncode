package io.github.eldiegos.bpmncode.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * El procesador hace fallar {@code javac} con las declaraciones que el framework rechazaría al arrancar y que dependen
 * solo del elemento anotado, y deja compilar las válidas.
 */
class ProcessAnnotationProcessorTests {

	private static final Path FIXTURES = Path.of("src", "test", "java", "fixture", "process");

	private static final String IMPORTS = """
			package demo;

			import org.springframework.context.event.EventListener;
			import org.springframework.scheduling.annotation.Scheduled;
			import io.github.eldiegos.bpmncode.annotation.*;
			import io.github.eldiegos.bpmncode.annotation.Process;
			import io.github.eldiegos.bpmncode.runtime.MessageHeader;
			import io.github.eldiegos.bpmncode.runtime.ProcessMessage;
			""";

	@TempDir
	Path output;

	// --- los procesos de prueba del framework ------------------------------------------------------------------------

	@Test
	void theValidProcessesOfTheFrameworkCompileWithoutErrors() throws Exception {
		assertThat(errorsOf(fixture("ValidProcesses.java"))).isEmpty();
	}

	@Test
	void theLocalMistakesOfTheBrokenProcessesAreCompilationErrors() throws Exception {
		assertThat(messagesOf(fixture("BrokenProcesses.java")))
				.anySatisfy(message -> assertThat(message).contains("requires exactly one @Scheduled", "found 0"))
				.anySatisfy(message -> assertThat(message).contains("is disabled"))
				.anySatisfy(message -> assertThat(message).contains("neither a @Message nor one of its @MessageVersion"))
				.anySatisfy(message -> assertThat(message).contains("must reference at least one business rule"))
				.anySatisfy(message -> assertThat(message).contains("declares several flow nodes"))
				.anySatisfy(message -> assertThat(message).contains("declares neither @Process nor @ProcessPart"))
				.anySatisfy(message -> assertThat(message).contains("without an outgoing sequence flow"))
				.anySatisfy(message -> assertThat(message).contains("loopCondition"))
				.anySatisfy(message -> assertThat(message).contains("loopMaximum must be zero"))
				.anySatisfy(message -> assertThat(message).contains("@ProcessError goes on an exception"))
				.anySatisfy(message -> assertThat(message).contains("a potential owner declares exactly one of resourceRef"
						+ " or expression"))
				.anySatisfy(message -> assertThat(message).contains("java.lang.Integer is not a type annotated with"
						+ " @ProcessResource"))
				.anySatisfy(message -> assertThat(message).contains("java.lang.IllegalStateException is not an exception"
						+ " annotated with @ProcessError"))
				.anySatisfy(message -> assertThat(message).contains("without identifier"));
	}

	@Test
	void theLocalMistakesOfTheVersionedMessagesAreCompilationErrors() throws Exception {
		assertThat(messagesOf(fixture("VersionedMessages.java")))
				.anySatisfy(message -> assertThat(message).contains("an end event publishes one concrete version"))
				.anySatisfy(message -> assertThat(message).contains("a send task sends one concrete version"))
				.anySatisfy(message -> assertThat(message).contains("must implement ProcessMessage"))
				.anySatisfy(message -> assertThat(message).contains("version 1 of FX-EV-TWICE is declared by both"))
				.anySatisfy(message -> assertThat(message).contains("@MessageVersion is only allowed"))
				// Que un consumidor no acepte una versión publicada depende de dos procesos: se comprueba al arrancar.
				.noneSatisfy(message -> assertThat(message).contains("FX-STRICT"));
	}

	// --- @TimerStartEvent ------------------------------------------------------------------------------------------

	@Test
	void aTimerStartEventWithItsScheduledCompiles() {
		assertThat(errorsOf("""
				@Process(id = "P", name = "P", version = 1)
				class Nightly {
					@Scheduled(cron = "${nightly.cron:0 0 3 * * *}")
					@TimerStartEvent(id = "S", name = "Every night", outgoing = @SequenceFlow(targetRef = "E"))
					void tick() {
					}
				}
				""")).isEmpty();
	}

	@Test
	void aTimerStartEventWithoutScheduledDoesNotCompile() {
		assertThat(errorsOf("""
				@Process(id = "P", name = "P", version = 1)
				class Nightly {
					@TimerStartEvent(id = "S", name = "Every night", outgoing = @SequenceFlow(targetRef = "E"))
					void tick() {
					}
				}
				""")).singleElement().satisfies(error -> {
			assertThat(error.getMessage(null)).contains("requires exactly one @Scheduled", "found 0");
			// El error señala la anotación, en la línea del fichero donde está escrita.
			assertThat(error.getLineNumber()).isEqualTo(12);
		});
	}

	@Test
	void twoSchedulesADisabledOneOrTwoTriggersDoNotCompile() {
		assertThat(messagesOf("""
				@Process(id = "P", name = "P", version = 1)
				class Nightly {
					@Scheduled(cron = "0 0 3 * * *")
					@Scheduled(fixedRate = 60000)
					@TimerStartEvent(id = "S", name = "Twice", outgoing = @SequenceFlow(targetRef = "E"))
					void twice() {
					}

					@Scheduled(cron = "-")
					@TimerStartEvent(id = "S2", name = "Disabled", outgoing = @SequenceFlow(targetRef = "E"))
					void disabled() {
					}

					@Scheduled(cron = "0 0 3 * * *", fixedDelay = 1000)
					@TimerStartEvent(id = "S3", name = "Two triggers", outgoing = @SequenceFlow(targetRef = "E"))
					void both() {
					}
				}
				"""))
				.anySatisfy(message -> assertThat(message).contains("found 2"))
				.anySatisfy(message -> assertThat(message).contains("disabled"))
				.anySatisfy(message -> assertThat(message).contains("exactly one of cron, fixedRate or fixedDelay"));
	}

	@Test
	void aComposedAnnotationCarryingScheduledCounts() {
		assertThat(errorsOf("""
				@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
				@Scheduled(cron = "0 0 8 * * MON-FRI")
				@interface EveryWorkingMorning {
				}

				@Process(id = "P", name = "P", version = 1)
				class Mornings {
					@EveryWorkingMorning
					@TimerStartEvent(id = "S", name = "Every morning", outgoing = @SequenceFlow(targetRef = "E"))
					void tick() {
					}
				}
				""")).isEmpty();
	}

	// --- mensajes ----------------------------------------------------------------------------------------------------

	@Test
	void aMessageStartEventIsAListenerOfAFamilyAndAnEndEventPublishesAVersion() {
		assertThat(errorsOf(PAID + """
				@Process(id = "B", name = "Billing", version = 1)
				class Billing {
					@MessageEndEvent(id = "B-END", name = "Paid", messageRef = PaidV2.class)
					void paid() {
					}
				}

				@Process(id = "F", name = "Fulfilment", version = 1)
				class Fulfilment {
					@EventListener
					@MessageStartEvent(id = "F-START", name = "Paid", messageRef = Paid.class,
							outgoing = @SequenceFlow(targetRef = "F-END"))
					void on(Paid paid) {
					}
				}
				""")).isEmpty();
	}

	@Test
	void aMessageStartEventThatIsNotAListenerOrDoesNotReceiveTheMessageDoesNotCompile() {
		assertThat(messagesOf(PAID + """
				@Process(id = "F", name = "Fulfilment", version = 1)
				class Fulfilment {
					@MessageStartEvent(id = "F-START", name = "Paid", messageRef = Paid.class,
							outgoing = @SequenceFlow(targetRef = "F-END"))
					void notAListener(Paid paid) {
					}

					@EventListener
					@MessageStartEvent(id = "F-START2", name = "Paid", messageRef = PaidV1.class,
							outgoing = @SequenceFlow(targetRef = "F-END"))
					void receivesSomethingElse(String other) {
					}
				}
				""")).hasSize(2).allSatisfy(message -> assertThat(message).contains("requires the method to be a Spring"
						+ " event listener receiving that type"));
	}

	@Test
	void aVersionRecordWithoutTheEnvelopeDoesNotCompile() {
		assertThat(messagesOf("""
				@Message(id = "EV", name = "Event")
				record Bare(String value) {
				}
				""")).singleElement().satisfies(message -> assertThat(message).contains("must implement ProcessMessage"));
	}

	// --- gateways y flujos -------------------------------------------------------------------------------------------

	@Test
	void theBranchesOfAGatewayAreExactlyTheConstantsOfItsEnum() {
		assertThat(messagesOf("""
				enum Verdict { ACCEPTED, RETURNED, REJECTED }

				@Process(id = "P", name = "P", version = 1)
				class Review {
					@ExclusiveGateway(id = "G", name = "Accepted?", outgoing = {
							@SequenceFlow(when = "ACCEPTED", targetRef = "A"),
							@SequenceFlow(when = "ACCEPTED", targetRef = "B"),
							@SequenceFlow(when = "LOST", targetRef = "C") })
					Verdict decide() {
						return Verdict.ACCEPTED;
					}

					@ExclusiveGateway(id = "G2", name = "Boolean?", outgoing = {
							@SequenceFlow(when = "true", targetRef = "A"), @SequenceFlow(when = "false", targetRef = "B") })
					boolean notAnEnum() {
						return true;
					}
				}
				"""))
				.anySatisfy(message -> assertThat(message).contains("constant 'ACCEPTED' selects several sequence flows"))
				.anySatisfy(message -> assertThat(message).contains("'LOST' is not a constant of Verdict"))
				.anySatisfy(message -> assertThat(message).contains("without an outgoing sequence flow: [RETURNED, REJECTED]"))
				.anySatisfy(message -> assertThat(message).contains("must return an enum"));
	}

	@Test
	void tasksHaveOneUnconditionalOutgoingFlowAndAValidIdentity() {
		assertThat(messagesOf("""
				@Process(id = "P", name = "P", version = 0)
				class Tasks {
					@ServiceTask(id = "1-BAD", name = "", outgoing = {})
					void none() {
					}

					@UserTask(id = "U", name = "Two", outgoing = { @SequenceFlow(targetRef = "A"), @SequenceFlow(targetRef = "B") })
					void two() {
					}

					@ServiceTask(id = "S", name = "Conditional", outgoing = @SequenceFlow(targetRef = "A", when = "YES"))
					void conditional() {
					}
				}
				"""))
				.anySatisfy(message -> assertThat(message).contains("versions start at 1"))
				.anySatisfy(message -> assertThat(message).contains("identifier '1-BAD' is invalid"))
				.anySatisfy(message -> assertThat(message).contains("the readable name is missing"))
				.anySatisfy(message -> assertThat(message).contains("does not declare its outgoing sequence flow"))
				.anySatisfy(message -> assertThat(message).contains("use an exclusive gateway to branch"))
				.anySatisfy(message -> assertThat(message).contains("only allowed on the outgoing sequence flows"));
	}

	@Test
	void theProcessorIsRegisteredAsAServiceForJavac() throws Exception {
		var registration = Path.of("src/main/resources/META-INF/services/javax.annotation.processing.Processor");
		assertThat(Files.readString(registration).strip()).isEqualTo(ProcessAnnotationProcessor.class.getName());
	}

	// --- utilidades -------------------------------------------------------------------------------------------------

	private static final String PAID = """
			@Message(id = "EV-PAID", name = "Paid")
			sealed interface Paid extends ProcessMessage permits PaidV1, PaidV2 {
			}

			@MessageVersion(1)
			record PaidV1(MessageHeader header, String orderId) implements Paid {
			}

			@MessageVersion(2)
			record PaidV2(MessageHeader header, String orderId, long cents) implements Paid {
			}

			""";

	private static JavaFileObject fixture(String name) throws Exception {
		var content = Files.readString(FIXTURES.resolve(name));
		return source("fixture/process/" + name, content);
	}

	private List<String> messagesOf(String body) {
		return errorsOf(body).stream().map(error -> error.getMessage(null)).toList();
	}

	private List<String> messagesOf(JavaFileObject file) {
		return errorsOf(file).stream().map(error -> error.getMessage(null)).toList();
	}

	private List<Diagnostic<? extends JavaFileObject>> errorsOf(String body) {
		return errorsOf(source("demo/Sample.java", IMPORTS + "\n" + body));
	}

	private List<Diagnostic<? extends JavaFileObject>> errorsOf(JavaFileObject file) {
		var compiler = ToolProvider.getSystemJavaCompiler();
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		var options = List.of("-proc:full", "-classpath", System.getProperty("java.class.path"), "-d", output.toString());
		var task = compiler.getTask(null, null, diagnostics, options, null, List.of(file));
		task.setProcessors(List.of(new ProcessAnnotationProcessor()));
		task.call();
		return diagnostics.getDiagnostics().stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
				.toList();
	}

	private static JavaFileObject source(String path, String content) {
		return new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
			@Override
			public CharSequence getCharContent(boolean ignoreEncodingErrors) {
				return content;
			}
		};
	}
}
