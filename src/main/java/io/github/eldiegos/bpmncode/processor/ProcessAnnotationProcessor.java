package io.github.eldiegos.bpmncode.processor;

import static io.github.eldiegos.bpmncode.processor.AnnotationNames.BUSINESS_RULE;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.FLOW_NODES;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.MESSAGE_VERSION;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_ERROR;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_PART;
import static io.github.eldiegos.bpmncode.processor.AnnotationNames.PROCESS_RESOURCE;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * Procesador de anotaciones de {@code bpmncode}: hace fallar la compilación con las declaraciones que el
 * framework rechazaría al arrancar y que se pueden decidir mirando solo el elemento anotado.
 * <p>
 * Responsabilidad: adelantar a {@code javac} (y al IDE) las comprobaciones locales del lector del modelo:
 * <ul>
 * <li>{@code @TimerStartEvent} lleva exactamente un {@code @Scheduled} activo con un solo disparador;</li>
 * <li>{@code @MessageStartEvent} es un listener de Spring que recibe un {@code @Message} o una de sus versiones;</li>
 * <li>{@code @MessageEndEvent} y {@code @SendTask} publican una versión concreta (un record), no la familia;</li>
 * <li>los métodos de nodo están en una clase {@code @Process} o {@code @ProcessPart} y llevan un solo nodo;</li>
 * <li>eventos de inicio y tareas tienen un único flujo de salida sin condición; los bucles, su condición;</li>
 * <li>un {@code @ExclusiveGateway} devuelve un {@code enum} cuyas constantes son exactamente sus ramas; un
 * {@code @BusinessRuleTask} referencia alguna regla;</li>
 * <li>{@code @Message} y {@code @MessageVersion} forman una familia sellada de records {@code ProcessMessage};</li>
 * <li>{@code @ProcessError} va en una excepción con {@code errorCode}, y el {@code errorRef} de un
 * {@code @ErrorEndEvent} es una de ellas;</li>
 * <li>cada {@code @PotentialOwner} y {@code @HumanPerformer} declara un {@code resourceRef} anotado con
 * {@code @ProcessResource} o una {@code expression}, nunca las dos;</li>
 * <li>identificadores NCName, nombres no vacíos y versiones desde 1.</li>
 * </ul>
 * <p>
 * Invariantes: funciona con el framework como librería aislada: reconoce las anotaciones por su nombre (no depende de
 * Spring ni del framework) y solo usa el elemento anotado y los tipos que referencia, que el compilador conoce aunque
 * vengan de otro jar. Lo que depende de toda la aplicación (destinos en otra clase, reglas, publicadores y consumidores,
 * alcanzabilidad, unicidad de identificadores, fichero de bloqueo, {@code ${…}}) lo sigue comprobando el lector del
 * modelo al arrancar.
 *
 * @see FlowNodeRules
 * @see MessageRules
 */
@SupportedAnnotationTypes("io.github.eldiegos.bpmncode.annotation.*")
public class ProcessAnnotationProcessor extends AbstractProcessor {

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
		var elements = processingEnv.getElementUtils();
		var types = processingEnv.getTypeUtils();
		var problems = new Problems(processingEnv.getMessager());
		var flowNodes = new FlowNodeRules(elements, types, problems);
		var messages = new MessageRules(elements, types, problems);
		for (var annotation : annotations) {
			var name = annotation.getQualifiedName().toString();
			for (var element : round.getElementsAnnotatedWith(annotation)) {
				var declared = DeclaredAnnotation.on(element, name, elements);
				if (declared.isEmpty()) {
					continue;
				}
				if (FLOW_NODES.contains(name) && element instanceof ExecutableElement method) {
					flowNodes.check(method, declared.get());
				}
				else if (name.equals(MESSAGE) && element instanceof TypeElement type) {
					messages.checkMessage(type, declared.get());
				}
				else if (name.equals(MESSAGE_VERSION) && element instanceof TypeElement type) {
					messages.checkVersion(type, declared.get());
				}
				else if (name.equals(PROCESS_ERROR) && element instanceof TypeElement type) {
					error(type, declared.get(), problems);
				}
				else {
					declaration(element, name, declared.get(), problems);
				}
			}
		}
		// No reclama las anotaciones: otros procesadores pueden tratarlas también.
		return false;
	}

	/** Un error es una excepción con identidad y código. */
	private void error(TypeElement type, DeclaredAnnotation declared, Problems problems) {
		Identity.check(declared, "error", type, problems);
		var throwable = processingEnv.getElementUtils().getTypeElement(Throwable.class.getCanonicalName());
		if (!processingEnv.getTypeUtils().isAssignable(type.asType(), throwable.asType())) {
			problems.error(type, declared, "@ProcessError goes on an exception (a subclass of Throwable)");
		}
		if (declared.string("errorCode").isBlank()) {
			problems.error(type, declared, "@ProcessError does not declare its errorCode");
		}
	}

	/** Procesos, partes de proceso, reglas y recursos: su identidad y, si la tienen, su versión. */
	private static void declaration(Element element, String name, DeclaredAnnotation declared, Problems problems) {
		if (name.equals(PROCESS)) {
			Identity.check(declared, "process", element, problems);
			Identity.positiveVersion(declared, "version", element, problems);
		}
		else if (name.equals(BUSINESS_RULE)) {
			Identity.check(declared, "rule", element, problems);
			Identity.positiveVersion(declared, "version", element, problems);
		}
		else if (name.equals(PROCESS_RESOURCE)) {
			Identity.check(declared, "resource", element, problems);
		}
		else if (name.equals(PROCESS_PART) && declared.string("processRef").isBlank()) {
			problems.error(element, declared, "@ProcessPart does not declare its processRef");
		}
	}
}
