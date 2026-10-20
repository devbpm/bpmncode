/**
 * Exportación BPMN 2.0 XML (modelo semántico e intercambio de diagramas BPMN DI) a partir del modelo de procesos. Los
 * diagramas se derivan siempre del código anotado; no hay ficheros BPMN escritos a mano. Lo que BPMN no define se
 * exporta como extensión en el espacio de nombres {@code https://pac.dev/schema/bpmn} (prefijo {@code pac:}). La
 * publicación web (API y visor bpmn.io) vive en la aplicación que lo usa.
 */
@NamedInterface("bpmn")
package io.github.eldiegos.bpmncode.bpmn;

import org.springframework.modulith.NamedInterface;
