/**
 * Modelo semántico BPMN 2.0 validado que se lee de las anotaciones: {@link io.github.eldiegos.bpmncode.model.Definitions} (el
 * equivalente de {@code bpmn:definitions}), {@link io.github.eldiegos.bpmncode.model.ProcessDefinition},
 * {@link io.github.eldiegos.bpmncode.model.FlowNode} con su jerarquía {@code Event} / {@code Activity} / {@code Gateway},
 * {@link io.github.eldiegos.bpmncode.model.SequenceFlow}, {@link io.github.eldiegos.bpmncode.model.MessageFlow},
 * {@link io.github.eldiegos.bpmncode.model.Message} y las reglas de negocio, más el
 * {@link io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry} que los ofrece en ejecución.
 * <p>
 * Un modelo inválido nunca se construye: la lectura termina con una
 * {@link io.github.eldiegos.bpmncode.model.ProcessModelException} que reúne todas las
 * {@link io.github.eldiegos.bpmncode.model.ModelViolation}.
 */
@NamedInterface("model")
package io.github.eldiegos.bpmncode.model;

import org.springframework.modulith.NamedInterface;
