/**
 * Anotaciones que convierten el código Java en la única fuente de verdad de un proceso BPMN 2.0.
 * <p>
 * Cada anotación se llama como el elemento BPMN que genera y sus atributos, como los atributos BPMN correspondientes
 * ({@code id}, {@code name}, {@code documentation}, {@code outgoing}, {@code targetRef}, {@code messageRef},
 * {@code conditionExpression}):
 * <ul>
 * <li>{@link Process} declara un {@code bpmn:process} (y su versión) en una clase; {@link ProcessPart} permite repartir
 * sus nodos entre varias clases.</li>
 * <li>Cada nodo del proceso es un método anotado con exactamente una de {@link StartEvent}, {@link ServiceTask},
 * {@link UserTask}, {@link BusinessRuleTask}, {@link SendTask}, {@link ExclusiveGateway}, {@link EndEvent},
 * {@link MessageEndEvent} o {@link ErrorEndEvent}.</li>
 * <li>Los flujos de secuencia se declaran en {@code outgoing} con {@link SequenceFlow}; ni el orden físico de los
 * métodos ni sus nombres tienen significado de proceso.</li>
 * <li>{@link Message} declara un {@code bpmn:message} en el tipo de evento Java; {@link MessageStartEvent},
 * {@link MessageEndEvent} y {@link SendTask} lo referencian con {@code messageRef}.</li>
 * <li>{@link ProcessError} declara un {@code bpmn:error} en una excepción Java; {@link ErrorEndEvent} lo referencia con
 * {@code errorRef}.</li>
 * <li>{@link ProcessResource} declara un {@code bpmn:resource} (un rol de negocio) en un tipo Java; los
 * {@link PotentialOwner} y {@link HumanPerformer} de un {@link UserTask} lo referencian con {@code resourceRef} o dicen
 * quién con una {@code expression}. Asignar la tarea a una persona es cosa de la aplicación.</li>
 * <li>{@link BusinessRule} identifica el código de una regla de negocio que referencian las tareas y los gateways.</li>
 * </ul>
 * Lo que BPMN no define (la versión del proceso y de la regla, la constante {@code when} que liga una rama al código y
 * la clase o método que implementa cada elemento) se exporta como extensión {@code pac:} del BPMN generado.
 * <p>
 * Los nombres declarados son los nombres canónicos (en inglés). La interfaz los traduce mediante claves de mensaje
 * derivadas de los identificadores (véase {@link io.github.eldiegos.bpmncode.i18n.LabelResolver}).
 */
@NamedInterface("annotation")
package io.github.eldiegos.bpmncode.annotation;

import org.springframework.modulith.NamedInterface;
