package io.github.eldiegos.bpmncode.bpmn;

import java.util.Objects;

import io.github.eldiegos.bpmncode.model.ProcessDefinition;

/**
 * Elemento de un diagrama BPMN con el modelo del que procede.
 * <p>
 * Responsabilidad: enlazar cada identificador del BPMN generado con su proceso, su versión y el elemento del modelo
 * (y, a través de él, con el código Java que lo implementa), para que el visor pueda explicar cualquier elemento.
 * <p>
 * Invariantes: {@link #elementId()} es el atributo {@code id} del elemento en el BPMN de la vista; {@link #process()}
 * es la versión exacta que muestra la vista, nunca otra versión del mismo proceso.
 *
 * @see BpmnDiagramService#elements(BpmnDiagram)
 */
public sealed interface DiagramElement permits DiagramElement.Participant, DiagramElement.FlowNode, DiagramElement.SequenceFlow,
		DiagramElement.MessageFlow {

	/** Identificador del elemento en el BPMN. */
	String elementId();

	/** Versión del proceso al que pertenece; en un mensaje, la del proceso que lo publica. */
	ProcessDefinition process();

	/**
	 * Calle (pool) de un proceso.
	 * <p>
	 * Responsabilidad: dar al visor el proceso y la versión de una calle.
	 * <p>
	 * Invariantes: la versión es la que dibuja la vista.
	 *
	 * @param elementId identificador del participante
	 * @param process versión del proceso que representa
	 */
	record Participant(String elementId, ProcessDefinition process) implements DiagramElement {

		public Participant {
			Objects.requireNonNull(elementId, "elementId");
			Objects.requireNonNull(process, "process");
		}
	}

	/**
	 * Nodo de flujo (evento, actividad o gateway).
	 * <p>
	 * Responsabilidad: enlazar un nodo dibujado con su definición y su código.
	 * <p>
	 * Invariantes: el nodo pertenece a {@code process}.
	 *
	 * @param process versión del proceso que lo declara
	 * @param flowNode nodo de flujo del modelo, con su código y sus reglas
	 */
	record FlowNode(ProcessDefinition process, io.github.eldiegos.bpmncode.model.FlowNode flowNode) implements DiagramElement {

		public FlowNode {
			Objects.requireNonNull(process, "process");
			Objects.requireNonNull(flowNode, "flowNode");
		}

		@Override
		public String elementId() {
			return flowNode.id();
		}
	}

	/**
	 * Flujo de secuencia entre dos nodos del mismo proceso.
	 * <p>
	 * Responsabilidad: enlazar un flujo de secuencia dibujado con su definición.
	 * <p>
	 * Invariantes: origen y destino son nodos de {@code process}.
	 *
	 * @param process versión del proceso que la declara
	 * @param sequenceFlow flujo de secuencia del modelo
	 */
	record SequenceFlow(ProcessDefinition process, io.github.eldiegos.bpmncode.model.SequenceFlow sequenceFlow) implements DiagramElement {

		public SequenceFlow {
			Objects.requireNonNull(process, "process");
			Objects.requireNonNull(sequenceFlow, "sequenceFlow");
		}

		@Override
		public String elementId() {
			return sequenceFlow.id();
		}
	}

	/**
	 * Flujo de mensaje entre procesos: un mensaje que publica una versión de un proceso y que inicia otro.
	 * <p>
	 * Responsabilidad: enlazar un flujo de mensaje con las dos versiones que conecta.
	 * <p>
	 * Invariantes: {@code process} publica el evento y {@code target} lo consume.
	 *
	 * @param messageFlow flujo de mensaje del modelo
	 * @param process versión del proceso que publica el evento
	 * @param target versión del proceso que lo consume
	 */
	record MessageFlow(io.github.eldiegos.bpmncode.model.MessageFlow messageFlow, ProcessDefinition process, ProcessDefinition target) implements DiagramElement {

		public MessageFlow {
			Objects.requireNonNull(messageFlow, "messageFlow");
			Objects.requireNonNull(process, "process");
			Objects.requireNonNull(target, "target");
		}

		@Override
		public String elementId() {
			return messageFlow.id();
		}
	}
}
