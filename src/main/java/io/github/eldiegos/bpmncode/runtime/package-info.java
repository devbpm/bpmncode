/**
 * Ejecución de procesos sin motor BPMN: el {@link io.github.eldiegos.bpmncode.runtime.ProcessJournal} comprueba en cada avance que
 * el código sigue un flujo de secuencia declarado (conformidad) y guarda la evidencia de cada nodo de flujo; el
 * {@link io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory} la consulta.
 * <p>
 * Dónde se guardan las instancias lo decide un {@link io.github.eldiegos.bpmncode.runtime.ProcessInstanceStore}: en memoria
 * ({@link io.github.eldiegos.bpmncode.runtime.InMemoryProcessInstanceStore}), en una base de datos
 * ({@link io.github.eldiegos.bpmncode.runtime.JdbcProcessInstanceStore}) o donde decida la aplicación.
 */
@NamedInterface("runtime")
package io.github.eldiegos.bpmncode.runtime;

import org.springframework.modulith.NamedInterface;
