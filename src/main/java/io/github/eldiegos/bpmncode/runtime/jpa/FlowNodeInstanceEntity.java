package io.github.eldiegos.bpmncode.runtime.jpa;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Fila de {@code pac_flow_node_instance} para el almacén JPA.
 * <p>
 * Responsabilidad: mapear con JPA la misma tabla de evidencia que usa el almacén JDBC.
 * <p>
 * Invariantes: solo la usa {@link JpaProcessInstanceStore}; solo se inserta, nunca se modifica; el tipo de elemento y
 * el resultado se guardan como texto, igual que en el script {@code schema.sql}.
 *
 * @see JpaProcessInstanceStore
 */
@Entity
@Table(name = "pac_flow_node_instance", indexes = {
		@Index(name = "ix_pac_flow_node_instance_process_instance", columnList = "process_instance_id"),
		@Index(name = "ix_pac_flow_node_instance_process_version", columnList = "process_id, process_version") })
class FlowNodeInstanceEntity {

	@Id
	UUID id;

	@Column(name = "process_instance_id", nullable = false)
	UUID processInstanceId;

	@Column(name = "sequence", nullable = false)
	int sequence;

	@Column(name = "process_id", nullable = false, length = 80)
	String processId;

	@Column(name = "process_version", nullable = false)
	int processVersion;

	@Column(name = "correlation_key", nullable = false)
	UUID correlationKey;

	@Column(name = "business_key", length = 80)
	String businessKey;

	@Column(name = "flow_node_id", nullable = false, length = 80)
	String flowNodeId;

	@Column(name = "element_type", nullable = false, length = 30)
	String elementType;

	@Column(name = "loop_counter", nullable = false)
	int loopCounter;

	@Column(name = "occurred_at", nullable = false)
	Instant occurredAt;

	@Column(name = "outcome", nullable = false, length = 500)
	String outcome;

	protected FlowNodeInstanceEntity() {
	}
}
