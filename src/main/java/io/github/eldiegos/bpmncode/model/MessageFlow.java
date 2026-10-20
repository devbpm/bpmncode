package io.github.eldiegos.bpmncode.model;

import java.util.Objects;

/**
 * Flujo de mensaje BPMN ({@code bpmn:messageFlow}) entre dos procesos.
 * <p>
 * Responsabilidad: unir el evento de fin o la tarea de envío que publica un {@link Message} con el evento de inicio que
 * lo recibe; se deriva de los {@code messageRef} de {@code @MessageEndEvent}, {@code @SendTask} y
 * {@code @MessageStartEvent}.
 * <p>
 * Invariantes: el origen es un evento de fin o una tarea de envío y el destino un evento de inicio que declaran el mismo
 * mensaje; cada extremo identifica la versión concreta del proceso, porque varias versiones pueden publicar el mismo
 * mensaje.
 *
 * @param id identificador estable del flujo de mensaje (único entre las versiones que se muestran juntas)
 * @param messageRef mensaje intercambiado (atributo {@code messageRef})
 * @param version versión del mensaje que publica el origen (y que el destino acepta)
 * @param sourceProcess versión del proceso que publica
 * @param sourceRef evento de fin o tarea de envío que publica (atributo {@code sourceRef})
 * @param targetProcess versión del proceso que recibe
 * @param targetRef evento de inicio que recibe (atributo {@code targetRef})
 * @see Definitions#messageFlows()
 */
public record MessageFlow(String id, String messageRef, int version, ProcessDefinitionRef sourceProcess, String sourceRef,
		ProcessDefinitionRef targetProcess, String targetRef) {

	public MessageFlow {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(messageRef, "messageRef");
		if (version < 1) {
			throw new IllegalArgumentException("A message flow carries a version greater than zero");
		}
		Objects.requireNonNull(sourceProcess, "sourceProcess");
		Objects.requireNonNull(sourceRef, "sourceRef");
		Objects.requireNonNull(targetProcess, "targetProcess");
		Objects.requireNonNull(targetRef, "targetRef");
	}

	/** Proceso que publica, sin versión. */
	public String sourceProcessId() {
		return sourceProcess.processId();
	}

	/** Proceso que recibe, sin versión. */
	public String targetProcessId() {
		return targetProcess.processId();
	}
}
