package io.github.eldiegos.bpmncode.runtime;

/**
 * Mensaje entre procesos: toda versión de un {@code @Message} lo implementa.
 * <p>
 * Responsabilidad: garantizar que cada mensaje lleva su {@link MessageHeader}, de modo que el framework pueda
 * correlacionarlo, deduplicarlo y registrar qué mensaje inició cada instancia sin conocer su carga de negocio.
 * <p>
 * Invariantes: la cabecera nunca es nula; el record de cada versión la declara como componente {@code header}.
 *
 * @see MessageHeader
 * @see ProcessJournal#startProcessInstance(io.github.eldiegos.bpmncode.model.ProcessDefinitionRef, ProcessMessage, FlowNodeOutcome)
 */
public interface ProcessMessage {

	/**
	 * Cabecera del mensaje.
	 *
	 * @return identificador, correlación, momento y origen
	 */
	MessageHeader header();
}
