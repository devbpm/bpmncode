package io.github.eldiegos.bpmncode.runtime;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Almacén de las instancias de proceso y de su evidencia.
 * <p>
 * Responsabilidad: guardar y recuperar el estado de las instancias ({@link ProcessInstanceRecord}) y la evidencia de cada
 * nodo de flujo ({@link FlowNodeInstanceRecord}), sin saber nada de conformidad. La librería trae tres implementaciones:
 * {@link InMemoryProcessInstanceStore}, que no necesita base de datos, {@link JdbcProcessInstanceStore} y
 * {@link io.github.eldiegos.bpmncode.runtime.jpa.JpaProcessInstanceStore}; la aplicación puede aportar la suya como
 * bean. Las consultas por versión de proceso tienen una implementación por defecto que se apoya en las demás, así que un
 * almacén propio no está obligado a implementarlas; los almacenes de la librería las resuelven con una sola consulta.
 * <p>
 * Invariantes: como mucho una instancia por proceso y correlación ({@link #insert} lanza
 * {@link DuplicateProcessInstanceException}); {@link #update} solo guarda si la versión del registro es la guardada y
 * la incrementa ({@link ProcessInstanceConflictException} si no); la evidencia solo se añade. El orden de las listas
 * devueltas es libre: quien las consulta las ordena.
 *
 * @see ProcessJournal
 * @see ProcessInstanceHistory
 */
public interface ProcessInstanceStore {

	/**
	 * Guarda una instancia nueva.
	 *
	 * @param instance instancia, con versión 0
	 * @throws DuplicateProcessInstanceException si ya hay una instancia del mismo proceso con esa correlación
	 */
	void insert(ProcessInstanceRecord instance);

	/**
	 * Guarda el nuevo estado de una instancia existente.
	 *
	 * @param instance instancia, con la versión que se leyó
	 * @throws ProcessInstanceConflictException si la instancia no existe o alguien la cambió después de leerla
	 */
	void update(ProcessInstanceRecord instance);

	/**
	 * Busca una instancia.
	 *
	 * @param instanceId identificador
	 * @return la instancia, o vacío si no existe
	 */
	Optional<ProcessInstanceRecord> instance(UUID instanceId);

	/**
	 * Busca la instancia de un proceso con una correlación.
	 *
	 * @param processId proceso
	 * @param correlationKey correlación de negocio
	 * @return la instancia, o vacío si no existe
	 */
	Optional<ProcessInstanceRecord> instance(String processId, CorrelationKey correlationKey);

	/**
	 * Devuelve todas las instancias.
	 *
	 * @return las instancias, en cualquier orden
	 */
	List<ProcessInstanceRecord> instances();

	/**
	 * Devuelve las instancias de una correlación.
	 *
	 * @param correlationKey correlación de negocio
	 * @return las instancias, en cualquier orden
	 */
	List<ProcessInstanceRecord> instances(CorrelationKey correlationKey);

	/**
	 * Devuelve las instancias de una versión de un proceso.
	 *
	 * @param process proceso y versión
	 * @return sus instancias, en cualquier orden; por defecto filtra {@link #instances()}
	 */
	default List<ProcessInstanceRecord> instances(ProcessDefinitionRef process) {
		return instances().stream().filter(instance -> instance.ref().equals(process)).toList();
	}

	/**
	 * Añade la evidencia de un nodo de flujo. Cada posición ({@code sequence}) de una instancia se registra una sola vez:
	 * así, dos pasos concurrentes de la misma instancia no pueden dejar el historial con la misma posición repetida.
	 *
	 * @param flowNodeInstance evidencia
	 * @throws ProcessInstanceConflictException si la instancia ya tiene un registro en esa posición (sin guardar nada)
	 */
	void append(FlowNodeInstanceRecord flowNodeInstance);

	/**
	 * Devuelve la evidencia de una instancia.
	 *
	 * @param processInstanceId instancia
	 * @return sus registros, en cualquier orden; vacío si no existe
	 */
	List<FlowNodeInstanceRecord> flowNodeInstances(UUID processInstanceId);

	/**
	 * Devuelve la evidencia de todas las instancias de una versión de un proceso, para agregarla (mapas de calor,
	 * estadísticas) sin una consulta por instancia.
	 *
	 * @param process proceso y versión
	 * @return los registros, en cualquier orden; por defecto reúne la evidencia de cada instancia de
	 *         {@link #instances(ProcessDefinitionRef)}
	 */
	default List<FlowNodeInstanceRecord> flowNodeInstances(ProcessDefinitionRef process) {
		return instances(process).stream().flatMap(instance -> flowNodeInstances(instance.id()).stream()).toList();
	}

	/**
	 * Devuelve las versiones de proceso que tienen alguna instancia activa, para comprobar al arrancar que todas siguen
	 * desplegadas sin leer las instancias una a una.
	 *
	 * @return las versiones, ordenadas y sin repetir; por defecto se calculan con {@link #instances()}, y los almacenes
	 *         con base de datos lo resuelven con una consulta
	 */
	default List<ProcessDefinitionRef> activeVersions() {
		return instances().stream().filter(instance -> instance.state() == ProcessInstanceState.ACTIVE)
				.map(ProcessInstanceRecord::ref).distinct().sorted().toList();
	}

	/**
	 * Cuenta la evidencia de una instancia; el journal lo usa para numerar el siguiente registro.
	 *
	 * @param processInstanceId instancia
	 * @return el número de registros
	 */
	default int flowNodeInstanceCount(UUID processInstanceId) {
		return flowNodeInstances(processInstanceId).size();
	}
}
