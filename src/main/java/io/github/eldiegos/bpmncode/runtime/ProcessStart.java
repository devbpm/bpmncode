package io.github.eldiegos.bpmncode.runtime;

import java.util.Objects;

/**
 * Resultado de iniciar una instancia solo si no existe ya otra del mismo proceso con la misma correlación.
 * <p>
 * Responsabilidad: distinguir un inicio real de una entrega repetida (un mensaje recibido dos veces, un reintento),
 * para que quien inicia no tenga que comprobarlo antes por su cuenta.
 * <p>
 * Invariantes: exactamente uno de los dos casos; en los dos hay una referencia a la instancia del proceso con esa
 * correlación.
 *
 * @see ProcessJournal#startProcessInstanceIfAbsent(io.github.eldiegos.bpmncode.model.ProcessDefinitionRef, ProcessMessage, FlowNodeOutcome)
 */
public sealed interface ProcessStart permits ProcessStart.Started, ProcessStart.AlreadyStarted {

	/** Instancia nueva o existente. */
	ProcessInstanceRef instance();

	/**
	 * Se inició una instancia nueva.
	 * <p>
	 * Responsabilidad: llevar la instancia recién iniciada.
	 * <p>
	 * Invariantes: la instancia está en su evento de inicio.
	 *
	 * @param instance instancia nueva
	 * @see ProcessStart
	 */
	record Started(ProcessInstanceRef instance) implements ProcessStart {

		public Started {
			Objects.requireNonNull(instance, "instance");
		}
	}

	/**
	 * Ya había una instancia con esa correlación: no se registró nada.
	 * <p>
	 * Responsabilidad: llevar la instancia existente.
	 * <p>
	 * Invariantes: la instancia puede estar en cualquier estado, también completada.
	 *
	 * @param instance instancia existente
	 * @see ProcessStart
	 */
	record AlreadyStarted(ProcessInstanceRef instance) implements ProcessStart {

		public AlreadyStarted {
			Objects.requireNonNull(instance, "instance");
		}
	}
}
