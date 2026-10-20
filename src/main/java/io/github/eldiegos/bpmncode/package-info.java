/**
 * Framework code-first de procesos BPMN 2.0, reutilizable por cualquier aplicación Spring Boot: el código Java anotado
 * es la única fuente de verdad de los procesos.
 * <p>
 * Paquetes (cada uno, salvo {@code model.reader} y {@code processor}, es una interfaz con nombre del módulo de Spring
 * Modulith {@code bpmncode}):
 * <ul>
 * <li>{@link io.github.eldiegos.bpmncode.annotation}: las anotaciones, con el vocabulario de BPMN ({@code @Process},
 * {@code @StartEvent}, {@code @ServiceTask}, {@code @ExclusiveGateway}, {@code @SequenceFlow}, {@code @Message}…);</li>
 * <li>{@link io.github.eldiegos.bpmncode.model}: el modelo semántico validado ({@code Definitions}, {@code ProcessDefinition},
 * {@code FlowNode}…) y su registro; su subpaquete {@code reader} lee las anotaciones y es interno;</li>
 * <li>{@link io.github.eldiegos.bpmncode.runtime}: el journal de ejecución con conformidad y el historial de instancias;</li>
 * <li>{@link io.github.eldiegos.bpmncode.trace}: la trazabilidad Proceso → Nodo de flujo → Regla → Código → Mensaje;</li>
 * <li>{@link io.github.eldiegos.bpmncode.i18n}: la traducción de los textos del modelo;</li>
 * <li>{@link io.github.eldiegos.bpmncode.bpmn}: la exportación BPMN 2.0 XML (modelo semántico y BPMN DI);</li>
 * <li>{@link io.github.eldiegos.bpmncode.statistics}: las estadísticas de ejecución (mapa de calor, camino de una
 * instancia, instancias en espera, recuento por versión).</li>
 * </ul>
 * Además, {@link io.github.eldiegos.bpmncode.processor} es el procesador de anotaciones que comprueba al compilar las reglas locales
 * a cada elemento; lo usa {@code javac}, no la aplicación. {@link io.github.eldiegos.bpmncode.web} es el módulo web
 * opcional (visor bpmn-js y API de solo lectura), apagado salvo con {@code pac.process.web.enabled=true}; no es una
 * interfaz con nombre.
 * No depende de ningún proceso concreto y se activa sola por autoconfiguración de Spring Boot.
 */
@ApplicationModule(displayName = "Process framework (BPMN 2.0)", allowedDependencies = {})
package io.github.eldiegos.bpmncode;

import org.springframework.modulith.ApplicationModule;
