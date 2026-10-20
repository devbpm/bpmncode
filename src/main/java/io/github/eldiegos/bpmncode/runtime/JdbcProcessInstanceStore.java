package io.github.eldiegos.bpmncode.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import io.github.eldiegos.bpmncode.model.BpmnElementType;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;

/**
 * Almacén de instancias en una base de datos relacional, con {@link JdbcClient} de Spring.
 * <p>
 * Responsabilidad: guardar instancias y evidencia en las tablas {@code pac_process_instance} y
 * {@code pac_flow_node_instance} (script en {@value #SCHEMA}). Es el almacén por defecto cuando la aplicación tiene un
 * {@code DataSource} y {@code spring-jdbc}.
 * <p>
 * Invariantes: usa la conexión de la transacción en curso, si la hay (también la de JPA), así que la evidencia se
 * confirma o deshace con los datos de negocio; la unicidad de proceso y correlación la garantiza además la restricción
 * {@code uk_pac_process_instance_correlation}; el bloqueo optimista usa la columna {@code version}.
 *
 * @see ProcessInstanceStore
 */
public class JdbcProcessInstanceStore implements ProcessInstanceStore {

	/** Script de creación de las tablas, en el classpath; usa {@code create table if not exists}. */
	public static final String SCHEMA = "io/github/eldiegos/bpmncode/runtime/schema.sql";

	private static final String INSTANCE_COLUMNS = "id, process_id, process_version, correlation_key, business_key, state, "
			+ "current_flow_node_id, loop_counter, selected_sequence_flow_id, triggering_message_id, triggering_message, "
			+ "triggering_source, triggering_source_instance_id, triggering_occurred_at, published_message_id, "
			+ "published_message, started_at, ended_at, version";

	private static final String FLOW_NODE_COLUMNS = "id, process_instance_id, sequence, process_id, process_version, "
			+ "correlation_key, business_key, flow_node_id, element_type, loop_counter, occurred_at, outcome";

	private final JdbcClient jdbc;

	/**
	 * Crea el almacén.
	 *
	 * @param dataSource base de datos con las tablas ya creadas (véase {@link #initializeSchema(DataSource)})
	 */
	public JdbcProcessInstanceStore(DataSource dataSource) {
		this.jdbc = JdbcClient.create(dataSource);
	}

	/**
	 * Crea las tablas si no existen, con el script {@value #SCHEMA} (sintaxis de H2 y PostgreSQL).
	 *
	 * @param dataSource base de datos
	 */
	public static void initializeSchema(DataSource dataSource) {
		new ResourceDatabasePopulator(new ClassPathResource(SCHEMA)).execute(dataSource);
	}

	@Override
	public void insert(ProcessInstanceRecord instance) {
		if (instance(instance.processId(), instance.correlationKey()).isPresent()) {
			throw new DuplicateProcessInstanceException(instance.processId(), instance.correlationKey());
		}
		try {
			jdbc.sql("insert into pac_process_instance (" + INSTANCE_COLUMNS + ") values (:id, :processId, :processVersion, "
					+ ":correlationKey, :businessKey, :state, :currentFlowNodeId, :loopCounter, :selectedSequenceFlowId, "
					+ ":triggeringMessageId, :triggeringMessage, :triggeringSource, :triggeringSourceInstanceId, "
					+ ":triggeringOccurredAt, :publishedMessageId, :publishedMessage, :startedAt, :endedAt, :version)")
					.params(parameters(instance))
					.update();
		}
		catch (DuplicateKeyException e) {
			// Otra transacción la insertó entre la comprobación y el insert.
			throw new DuplicateProcessInstanceException(instance.processId(), instance.correlationKey());
		}
	}

	@Override
	public void update(ProcessInstanceRecord instance) {
		var updated = jdbc.sql("update pac_process_instance set business_key = :businessKey, state = :state, "
				+ "current_flow_node_id = :currentFlowNodeId, loop_counter = :loopCounter, "
				+ "selected_sequence_flow_id = :selectedSequenceFlowId, published_message_id = :publishedMessageId, "
				+ "published_message = :publishedMessage, ended_at = :endedAt, version = :version + 1 "
				+ "where id = :id and version = :version")
				.params(parameters(instance))
				.update();
		if (updated != 1) {
			throw new ProcessInstanceConflictException(instance.id(), instance.version());
		}
	}

	@Override
	public Optional<ProcessInstanceRecord> instance(UUID instanceId) {
		return jdbc.sql("select " + INSTANCE_COLUMNS + " from pac_process_instance where id = ?")
				.param(instanceId).query(JdbcProcessInstanceStore::instance).optional();
	}

	@Override
	public Optional<ProcessInstanceRecord> instance(String processId, CorrelationKey correlationKey) {
		return jdbc.sql("select " + INSTANCE_COLUMNS + " from pac_process_instance where process_id = ? and correlation_key = ?")
				.params(processId, correlationKey.value()).query(JdbcProcessInstanceStore::instance).optional();
	}

	@Override
	public List<ProcessInstanceRecord> instances() {
		return jdbc.sql("select " + INSTANCE_COLUMNS + " from pac_process_instance")
				.query(JdbcProcessInstanceStore::instance).list();
	}

	@Override
	public List<ProcessInstanceRecord> instances(CorrelationKey correlationKey) {
		return jdbc.sql("select " + INSTANCE_COLUMNS + " from pac_process_instance where correlation_key = ?")
				.param(correlationKey.value()).query(JdbcProcessInstanceStore::instance).list();
	}

	@Override
	public List<ProcessInstanceRecord> instances(ProcessDefinitionRef process) {
		return jdbc.sql("select " + INSTANCE_COLUMNS + " from pac_process_instance where process_id = ? and process_version = ?")
				.params(process.processId(), process.version()).query(JdbcProcessInstanceStore::instance).list();
	}

	@Override
	public void append(FlowNodeInstanceRecord record) {
		jdbc.sql("insert into pac_flow_node_instance (" + FLOW_NODE_COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
				.params(record.id(), record.processInstanceId(), record.sequence(), record.processId(),
						record.processVersion(), record.correlationKey().value(), record.businessKey().orElse(null),
						record.flowNodeId(), record.elementType().name(), record.loopCounter(), timestamp(record.occurredAt()),
						record.outcome().toString())
				.update();
	}

	@Override
	public List<FlowNodeInstanceRecord> flowNodeInstances(UUID processInstanceId) {
		return jdbc.sql("select " + FLOW_NODE_COLUMNS + " from pac_flow_node_instance where process_instance_id = ?")
				.param(processInstanceId).query(JdbcProcessInstanceStore::flowNodeInstance).list();
	}

	@Override
	public List<FlowNodeInstanceRecord> flowNodeInstances(ProcessDefinitionRef process) {
		return jdbc.sql("select " + FLOW_NODE_COLUMNS + " from pac_flow_node_instance where process_id = ? and process_version = ?")
				.params(process.processId(), process.version()).query(JdbcProcessInstanceStore::flowNodeInstance).list();
	}

	@Override
	public int flowNodeInstanceCount(UUID processInstanceId) {
		return jdbc.sql("select count(*) from pac_flow_node_instance where process_instance_id = ?")
				.param(processInstanceId).query(Integer.class).single();
	}

	private static Map<String, Object> parameters(ProcessInstanceRecord instance) {
		var values = new HashMap<String, Object>();
		values.put("id", instance.id());
		values.put("processId", instance.processId());
		values.put("processVersion", instance.processVersion());
		values.put("correlationKey", instance.correlationKey().value());
		values.put("businessKey", instance.businessKey().orElse(null));
		values.put("state", instance.state().name());
		values.put("currentFlowNodeId", instance.currentFlowNodeId());
		values.put("loopCounter", instance.loopCounter());
		values.put("selectedSequenceFlowId", instance.selectedSequenceFlowId().orElse(null));
		var trigger = instance.triggeringMessage();
		values.put("triggeringMessageId", trigger.map(message -> message.messageId().value()).orElse(null));
		values.put("triggeringMessage", trigger.map(message -> StoredReferences.format(message.message())).orElse(null));
		values.put("triggeringSource", trigger.map(message -> StoredReferences.format(message.source())).orElse(null));
		values.put("triggeringSourceInstanceId", trigger.map(ExchangedMessage::sourceInstanceId).orElse(null));
		values.put("triggeringOccurredAt", trigger.map(message -> timestamp(message.occurredAt())).orElse(null));
		values.put("publishedMessageId", instance.publishedMessage().map(message -> message.messageId().value()).orElse(null));
		values.put("publishedMessage", instance.publishedMessage().map(message -> StoredReferences.format(message.message()))
				.orElse(null));
		values.put("startedAt", timestamp(instance.startedAt()));
		values.put("endedAt", instance.endedAt().map(JdbcProcessInstanceStore::timestamp).orElse(null));
		values.put("version", instance.version());
		return values;
	}

	private static ProcessInstanceRecord instance(ResultSet row, int rowNumber) throws SQLException {
		var id = row.getObject("id", UUID.class);
		var processId = row.getString("process_id");
		var processVersion = row.getInt("process_version");
		var endedAt = Optional.ofNullable(instant(row, "ended_at"));
		Optional<ExchangedMessage> trigger = Optional.empty();
		var triggeringMessageId = row.getObject("triggering_message_id", UUID.class);
		if (triggeringMessageId != null) {
			trigger = Optional.of(new ExchangedMessage(MessageId.of(triggeringMessageId),
					StoredReferences.parseMessage(row.getString("triggering_message")),
					StoredReferences.parseProcess(row.getString("triggering_source")),
					row.getObject("triggering_source_instance_id", UUID.class), instant(row, "triggering_occurred_at")));
		}
		Optional<ExchangedMessage> published = Optional.empty();
		var publishedMessageId = row.getObject("published_message_id", UUID.class);
		if (publishedMessageId != null) {
			published = Optional.of(new ExchangedMessage(MessageId.of(publishedMessageId),
					StoredReferences.parseMessage(row.getString("published_message")),
					ProcessDefinitionRef.of(processId, processVersion), id,
					endedAt.orElseThrow()));
		}
		return new ProcessInstanceRecord(id, processId, processVersion,
				CorrelationKey.of(row.getObject("correlation_key", UUID.class)),
				Optional.ofNullable(row.getString("business_key")), ProcessInstanceState.valueOf(row.getString("state")),
				row.getString("current_flow_node_id"), row.getInt("loop_counter"),
				Optional.ofNullable(row.getString("selected_sequence_flow_id")), trigger, published,
				instant(row, "started_at"), endedAt, row.getLong("version"));
	}

	private static FlowNodeInstanceRecord flowNodeInstance(ResultSet row, int rowNumber) throws SQLException {
		return new FlowNodeInstanceRecord(row.getObject("id", UUID.class), row.getObject("process_instance_id", UUID.class),
				row.getInt("sequence"), row.getString("process_id"), row.getInt("process_version"),
				CorrelationKey.of(row.getObject("correlation_key", UUID.class)),
				Optional.ofNullable(row.getString("business_key")), row.getString("flow_node_id"),
				BpmnElementType.valueOf(row.getString("element_type")), row.getInt("loop_counter"),
				instant(row, "occurred_at"), FlowNodeOutcome.parse(row.getString("outcome")));
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet row, String column) throws SQLException {
		var value = row.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
