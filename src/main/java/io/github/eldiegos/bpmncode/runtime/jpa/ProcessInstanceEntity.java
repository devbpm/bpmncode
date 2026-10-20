package io.github.eldiegos.bpmncode.runtime.jpa;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Fila de {@code pac_process_instance} para el almacén JPA.
 * <p>
 * Responsabilidad: mapear con JPA la misma tabla que usa el almacén JDBC, para que una aplicación pueda pasar de uno a
 * otro sin migrar datos.
 * <p>
 * Invariantes: solo la usa {@link JpaProcessInstanceStore}; los enumerados se guardan como texto y los mensajes como
 * columnas sueltas, igual que en el script {@code schema.sql}; {@code version} es el bloqueo optimista de JPA.
 *
 * @see JpaProcessInstanceStore
 */
@Entity
@Table(name = "pac_process_instance", uniqueConstraints = @UniqueConstraint(name = "uk_pac_process_instance_correlation",
		columnNames = { "process_id", "correlation_key" }), indexes = @Index(name = "ix_pac_process_instance_process_version",
				columnList = "process_id, process_version"))
class ProcessInstanceEntity {

	@Id
	UUID id;

	@Column(name = "process_id", nullable = false, length = 80)
	String processId;

	@Column(name = "process_version", nullable = false)
	int processVersion;

	@Column(name = "correlation_key", nullable = false)
	UUID correlationKey;

	@Column(name = "business_key", length = 80)
	String businessKey;

	@Column(name = "state", nullable = false, length = 20)
	String state;

	@Column(name = "current_flow_node_id", nullable = false, length = 80)
	String currentFlowNodeId;

	@Column(name = "loop_counter", nullable = false)
	int loopCounter;

	@Column(name = "selected_sequence_flow_id", length = 160)
	String selectedSequenceFlowId;

	@Column(name = "triggering_message_id")
	UUID triggeringMessageId;

	@Column(name = "triggering_message", length = 120)
	String triggeringMessage;

	@Column(name = "triggering_source", length = 120)
	String triggeringSource;

	@Column(name = "triggering_source_instance_id")
	UUID triggeringSourceInstanceId;

	@Column(name = "triggering_occurred_at")
	Instant triggeringOccurredAt;

	@Column(name = "published_message_id")
	UUID publishedMessageId;

	@Column(name = "published_message", length = 120)
	String publishedMessage;

	@Column(name = "started_at", nullable = false)
	Instant startedAt;

	@Column(name = "ended_at")
	Instant endedAt;

	@Version
	@Column(name = "version", nullable = false)
	long version;

	protected ProcessInstanceEntity() {
	}
}
