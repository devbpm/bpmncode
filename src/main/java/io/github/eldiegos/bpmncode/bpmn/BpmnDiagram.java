package io.github.eldiegos.bpmncode.bpmn;

import java.util.List;
import java.util.Objects;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Diagrama BPMN ({@code bpmndi:BPMNDiagram}) que se puede exportar.
 * <p>
 * Responsabilidad: describir qué procesos entran en un diagrama: uno solo (en su versión vigente o en una versión
 * anterior concreta), o todos los que intercambian mensajes (en su versión vigente).
 * <p>
 * Invariantes: un diagrama de proceso tiene exactamente un proceso; una de colaboración tiene al menos dos y su
 * identificador es siempre {@value CollaborationDiagram#ID}.
 *
 * @see BpmnDiagramService#diagrams()
 */
public sealed interface BpmnDiagram permits BpmnDiagram.ProcessDiagram, BpmnDiagram.ProcessVersionDiagram,
		BpmnDiagram.CollaborationDiagram {

	/** Identificador del diagrama, estable para la API. */
	String id();

	/** Procesos que se dibujan, en orden de arriba abajo. */
	List<String> processIds();

	/**
	 * Diagrama de la versión vigente de un proceso.
	 * <p>
	 * Responsabilidad: dibujar un proceso y, como piscinas colapsadas, los procesos con los que se comunica.
	 * <p>
	 * Invariantes: su identificador es el del proceso.
	 *
	 * @param processId proceso dibujado
	 * @see BpmnDiagram
	 */
	record ProcessDiagram(String processId) implements BpmnDiagram {

		public ProcessDiagram {
			Objects.requireNonNull(processId, "processId");
		}

		@Override
		public String id() {
			return processId;
		}

		@Override
		public List<String> processIds() {
			return List.of(processId);
		}
	}


	/**
	 * Diagrama de una versión anterior de un proceso que sigue desplegada.
	 * <p>
	 * Responsabilidad: dibujar exactamente esa versión (por ejemplo, la v1 que aún atienden clientes antiguos) y, como
	 * piscinas colapsadas, la versión vigente de los procesos con los que se comunica.
	 * <p>
	 * Invariantes: su identificador es el del proceso seguido de {@code -v} y el número de versión, p. ej.
	 * {@code PER-REGISTRATION-v1}; el diagrama de la versión vigente es {@link ProcessDiagram}.
	 *
	 * @param ref proceso y versión dibujados
	 * @see BpmnDiagram
	 */
	record ProcessVersionDiagram(ProcessDefinitionRef ref) implements BpmnDiagram {

		public ProcessVersionDiagram {
			Objects.requireNonNull(ref, "ref");
		}

		@Override
		public String id() {
			return ref.processId() + "-v" + ref.version();
		}

		@Override
		public List<String> processIds() {
			return List.of(ref.processId());
		}
	}
	/**
	 * Diagrama de colaboración entre los procesos que intercambian mensajes.
	 * <p>
	 * Responsabilidad: dibujar juntos los procesos comunicados y sus flujos de mensaje.
	 * <p>
	 * Invariantes: contiene al menos dos procesos.
	 *
	 * @param processIds procesos dibujados, ordenados
	 * @see BpmnDiagram
	 */
	record CollaborationDiagram(List<String> processIds) implements BpmnDiagram {

		/** Identificador del diagrama de colaboración. */
		public static final String ID = "COLLABORATION";

		public CollaborationDiagram {
			processIds = List.copyOf(processIds);
			if (processIds.size() < 2) {
				throw new IllegalArgumentException("A collaboration needs at least two processes");
			}
		}

		@Override
		public String id() {
			return ID;
		}
	}
}
