package io.github.eldiegos.bpmncode.model.reader;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import io.github.eldiegos.bpmncode.annotation.BusinessRule;
import io.github.eldiegos.bpmncode.annotation.BusinessRuleTask;
import io.github.eldiegos.bpmncode.annotation.EndEvent;
import io.github.eldiegos.bpmncode.annotation.ErrorEndEvent;
import io.github.eldiegos.bpmncode.annotation.ExclusiveGateway;
import io.github.eldiegos.bpmncode.annotation.Message;
import io.github.eldiegos.bpmncode.annotation.MessageEndEvent;
import io.github.eldiegos.bpmncode.annotation.MessageStartEvent;
import io.github.eldiegos.bpmncode.annotation.MessageVersion;
import io.github.eldiegos.bpmncode.annotation.Process;
import io.github.eldiegos.bpmncode.annotation.ProcessError;
import io.github.eldiegos.bpmncode.annotation.ProcessPart;
import io.github.eldiegos.bpmncode.annotation.ProcessResource;
import io.github.eldiegos.bpmncode.annotation.SendTask;
import io.github.eldiegos.bpmncode.annotation.ServiceTask;
import io.github.eldiegos.bpmncode.annotation.StartEvent;
import io.github.eldiegos.bpmncode.annotation.TimerStartEvent;
import io.github.eldiegos.bpmncode.annotation.UserTask;

/**
 * Conocimiento de qué anotaciones forman el metamodelo de procesos.
 * <p>
 * Responsabilidad: decidir si un tipo o un método lleva metadatos de proceso y listar los métodos de un tipo en un orden
 * canónico.
 * <p>
 * Invariantes: el orden de los métodos no depende de su posición en el fichero fuente.
 *
 * @see AnnotationProcessModelReader
 */
final class ProcessMetadata {

	private static final List<Class<? extends Annotation>> TYPE_MARKERS = List.of(Process.class, ProcessPart.class,
			BusinessRule.class, Message.class, MessageVersion.class, ProcessError.class, ProcessResource.class);

	private static final List<Class<? extends Annotation>> FLOW_NODE_MARKERS = List.of(StartEvent.class,
			MessageStartEvent.class, TimerStartEvent.class,
			ServiceTask.class, UserTask.class, BusinessRuleTask.class, SendTask.class, ExclusiveGateway.class,
			EndEvent.class, MessageEndEvent.class, ErrorEndEvent.class);

	private static final List<Class<? extends Annotation>> METHOD_MARKERS = Stream.concat(FLOW_NODE_MARKERS.stream(),
			Stream.of(BusinessRule.class)).toList();

	private ProcessMetadata() {
	}

	/**
	 * Indica si un tipo o alguno de sus métodos lleva anotaciones del metamodelo.
	 *
	 * @param type tipo inspeccionado
	 * @return {@code true} si lleva metadatos de proceso
	 */
	static boolean isPresentOn(Class<?> type) {
		return TYPE_MARKERS.stream().anyMatch(type::isAnnotationPresent)
				|| Arrays.stream(type.getDeclaredMethods())
						.anyMatch(method -> METHOD_MARKERS.stream().anyMatch(method::isAnnotationPresent));
	}

	/**
	 * Devuelve las anotaciones de nodo de flujo de un método.
	 *
	 * @param method método inspeccionado
	 * @return las anotaciones de nodo presentes; más de una es una referencia ambigua
	 */
	static List<Annotation> flowNodeAnnotationsOf(Method method) {
		return FLOW_NODE_MARKERS.stream().<Annotation> map(method::getAnnotation).filter(Objects::nonNull).toList();
	}

	/**
	 * Devuelve los métodos propios de un tipo en orden canónico.
	 *
	 * @param type tipo inspeccionado
	 * @return los métodos no sintéticos, ordenados por su firma genérica
	 */
	static List<Method> methodsOf(Class<?> type) {
		return Stream.of(type.getDeclaredMethods())
				.filter(method -> !method.isSynthetic() && !method.isBridge())
				.sorted(Comparator.comparing(Method::toGenericString))
				.toList();
	}
}
