package io.github.eldiegos.bpmncode.model;

import java.util.List;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Registro de las definiciones de proceso desplegadas en la aplicación.
 * <p>
 * Responsabilidad: leer al arrancar el código anotado de los paquetes de la aplicación y ofrecer las
 * {@link Definitions} resultantes.
 * <p>
 * Invariantes: es inmutable tras el arranque; un modelo inválido aborta el arranque con una
 * {@link ProcessModelException}, de modo que la aplicación nunca se ejecuta con procesos que no sabe describir.
 *
 * @see AnnotationProcessModelReader
 * @see ProcessModelAutoConfiguration
 */
public class ProcessDefinitionRegistry {

	private static final Logger log = LoggerFactory.getLogger(ProcessDefinitionRegistry.class);

	private final Definitions definitions;

	/**
	 * Crea el registro a partir de unas definiciones ya leídas.
	 *
	 * @param definitions definiciones validadas
	 */
	public ProcessDefinitionRegistry(Definitions definitions) {
		this.definitions = definitions;
		definitions.processes().forEach(process -> log.info("Process definition {} v{} '{}': {} flow nodes, {} sequence flows",
				process.id(), process.version(), process.name(), process.flowNodes().size(), process.sequenceFlows().size()));
	}

	/**
	 * Devuelve el modelo completo.
	 *
	 * @return procesos, reglas, mensajes y flujos de mensaje
	 */
	public Definitions definitions() {
		return definitions;
	}

	/**
	 * Devuelve las definiciones de proceso.
	 *
	 * @return las definiciones, ordenadas por identificador
	 */
	public List<ProcessDefinition> processDefinitions() {
		return definitions.processes();
	}

	/**
	 * Devuelve la versión vigente (la mayor) de un proceso.
	 *
	 * @param processId identificador del proceso
	 * @return la definición
	 * @throws NoSuchElementException si el proceso no existe
	 */
	public ProcessDefinition processDefinition(String processId) {
		return definitions.process(processId)
				.orElseThrow(() -> new NoSuchElementException("Unknown process definition " + processId));
	}

	/**
	 * Devuelve una versión concreta de un proceso.
	 *
	 * @param ref identificador y versión
	 * @return la definición
	 * @throws NoSuchElementException si esa versión no está desplegada
	 */
	public ProcessDefinition processDefinition(ProcessDefinitionRef ref) {
		return definitions.process(ref)
				.orElseThrow(() -> new NoSuchElementException("Unknown process definition " + ref));
	}
}
