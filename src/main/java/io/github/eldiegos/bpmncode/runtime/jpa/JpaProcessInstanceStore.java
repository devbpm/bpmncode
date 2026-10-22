package io.github.eldiegos.bpmncode.runtime.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.orm.jpa.SharedEntityManagerCreator;

import io.github.eldiegos.bpmncode.model.BpmnElementType;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.DuplicateProcessInstanceException;
import io.github.eldiegos.bpmncode.runtime.ExchangedMessage;
import io.github.eldiegos.bpmncode.runtime.FlowNodeInstanceRecord;
import io.github.eldiegos.bpmncode.runtime.FlowNodeOutcome;
import io.github.eldiegos.bpmncode.runtime.MessageId;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceConflictException;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceRecord;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceState;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceStore;
import io.github.eldiegos.bpmncode.runtime.StoredReferences;

/**
 * Almacén de instancias con JPA, en la unidad de persistencia de la aplicación.
 * <p>
 * Responsabilidad: guardar instancias y evidencia como entidades JPA ({@link ProcessInstanceEntity},
 * {@link FlowNodeInstanceEntity}) en las mismas tablas que el almacén JDBC, de modo que la aplicación que usa JPA
 * tiene también el journal en su contexto de persistencia (mismo proveedor, mismo dialecto y mismo esquema generado). Es
 * el almacén por defecto cuando la aplicación tiene un {@link EntityManagerFactory}.
 * <p>
 * Invariantes: necesita una transacción activa para escribir (el journal la abre con {@code @Transactional}); el bloqueo
 * optimista lo comprueba el almacén dentro de la transacción y JPA entre transacciones, con {@code @Version}; la unicidad de
 * proceso y correlación se comprueba antes de insertar y la garantiza además la restricción
 * {@code uk_pac_process_instance_correlation}.
 *
 * @see ProcessInstanceStore
 */
public class JpaProcessInstanceStore implements ProcessInstanceStore {

	private final EntityManager entityManager;

	/**
	 * Crea el almacén.
	 *
	 * @param entityManagerFactory unidad de persistencia de la aplicación, que debe gestionar las entidades de este paquete
	 * @throws IllegalStateException si la unidad de persistencia no gestiona las entidades del almacén
	 */
	public JpaProcessInstanceStore(EntityManagerFactory entityManagerFactory) {
		if (!manages(entityManagerFactory)) {
			throw new IllegalStateException("The persistence unit does not manage the bpmncode entities: add the package "
					+ ProcessInstanceEntity.class.getPackageName() + " to @EntityScan or use pac.process.store.type=jdbc");
		}
		this.entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
	}

	/**
	 * Indica si una unidad de persistencia gestiona las entidades del almacén (no las gestiona, p. ej., si la aplicación
	 * declara {@code @EntityScan} sin este paquete).
	 *
	 * @param entityManagerFactory unidad de persistencia
	 * @return {@code true} si puede usarse con este almacén
	 */
	public static boolean manages(EntityManagerFactory entityManagerFactory) {
		return entityManagerFactory.getMetamodel().getManagedTypes().stream()
				.anyMatch(type -> type.getJavaType() == ProcessInstanceEntity.class);
	}

	@Override
	public void insert(ProcessInstanceRecord instance) {
		if (instance(instance.processId(), instance.correlationKey()).isPresent()) {
			throw new DuplicateProcessInstanceException(instance.processId(), instance.correlationKey());
		}
		var entity = new ProcessInstanceEntity();
		entity.id = instance.id();
		entity.processId = instance.processId();
		entity.processVersion = instance.processVersion();
		entity.correlationKey = instance.correlationKey().value();
		entity.startedAt = instance.startedAt();
		instance.triggeringMessage().ifPresent(trigger -> {
			entity.triggeringMessageId = trigger.messageId().value();
			entity.triggeringMessage = StoredReferences.format(trigger.message());
			entity.triggeringSource = StoredReferences.format(trigger.source());
			entity.triggeringSourceInstanceId = trigger.sourceInstanceId();
			entity.triggeringOccurredAt = trigger.occurredAt();
		});
		copyState(instance, entity);
		entityManager.persist(entity);
	}

	@Override
	public void update(ProcessInstanceRecord instance) {
		var entity = entityManager.find(ProcessInstanceEntity.class, instance.id());
		if (entity == null || entity.version != instance.version()) {
			throw new ProcessInstanceConflictException(instance.id(), instance.version());
		}
		// Sin transacción, el cambio de una entidad se perdería en silencio: así falla con TransactionRequiredException.
		entityManager.joinTransaction();
		copyState(instance, entity);
	}

	@Override
	public Optional<ProcessInstanceRecord> instance(UUID instanceId) {
		return Optional.ofNullable(entityManager.find(ProcessInstanceEntity.class, instanceId))
				.map(JpaProcessInstanceStore::instanceRecord);
	}

	@Override
	public Optional<ProcessInstanceRecord> instance(String processId, CorrelationKey correlationKey) {
		return entityManager.createQuery("select i from ProcessInstanceEntity i where i.processId = :processId"
				+ " and i.correlationKey = :correlationKey", ProcessInstanceEntity.class)
				.setParameter("processId", processId)
				.setParameter("correlationKey", correlationKey.value())
				.getResultList().stream().findFirst().map(JpaProcessInstanceStore::instanceRecord);
	}

	@Override
	public List<ProcessInstanceRecord> instances() {
		return entityManager.createQuery("select i from ProcessInstanceEntity i", ProcessInstanceEntity.class)
				.getResultList().stream().map(JpaProcessInstanceStore::instanceRecord).toList();
	}

	@Override
	public List<ProcessInstanceRecord> instances(CorrelationKey correlationKey) {
		return entityManager.createQuery("select i from ProcessInstanceEntity i where i.correlationKey = :correlationKey",
				ProcessInstanceEntity.class)
				.setParameter("correlationKey", correlationKey.value())
				.getResultList().stream().map(JpaProcessInstanceStore::instanceRecord).toList();
	}

	@Override
	public List<ProcessInstanceRecord> instances(ProcessDefinitionRef process) {
		return entityManager.createQuery("select i from ProcessInstanceEntity i where i.processId = :processId"
				+ " and i.processVersion = :version", ProcessInstanceEntity.class)
				.setParameter("processId", process.processId())
				.setParameter("version", process.version())
				.getResultList().stream().map(JpaProcessInstanceStore::instanceRecord).toList();
	}

	@Override
	public List<ProcessDefinitionRef> activeVersions() {
		return entityManager.createQuery("select distinct i.processId, i.processVersion from ProcessInstanceEntity i"
				+ " where i.state = :state order by i.processId, i.processVersion", Object[].class)
				.setParameter("state", ProcessInstanceState.ACTIVE.name())
				.getResultList().stream()
				.map(row -> ProcessDefinitionRef.of((String) row[0], (Integer) row[1])).toList();
	}

	@Override
	public void append(FlowNodeInstanceRecord record) {
		// Igual que insert: se comprueba antes y la restricción única lo garantiza si dos pasos llegan a la vez.
		var taken = entityManager.createQuery("select count(f) from FlowNodeInstanceEntity f"
				+ " where f.processInstanceId = :instance and f.sequence = :sequence", Long.class)
				.setParameter("instance", record.processInstanceId()).setParameter("sequence", record.sequence())
				.getSingleResult();
		if (taken > 0) {
			throw ProcessInstanceConflictException.sequenceTaken(record.processInstanceId(), record.sequence());
		}
		var entity = new FlowNodeInstanceEntity();
		entity.id = record.id();
		entity.processInstanceId = record.processInstanceId();
		entity.sequence = record.sequence();
		entity.processId = record.processId();
		entity.processVersion = record.processVersion();
		entity.correlationKey = record.correlationKey().value();
		entity.businessKey = record.businessKey().orElse(null);
		entity.flowNodeId = record.flowNodeId();
		entity.elementType = record.elementType().name();
		entity.loopCounter = record.loopCounter();
		entity.occurredAt = record.occurredAt();
		entity.outcome = record.outcome().toString();
		entityManager.persist(entity);
	}

	@Override
	public List<FlowNodeInstanceRecord> flowNodeInstances(UUID processInstanceId) {
		return entityManager.createQuery("select f from FlowNodeInstanceEntity f where f.processInstanceId = :instance",
				FlowNodeInstanceEntity.class)
				.setParameter("instance", processInstanceId)
				.getResultList().stream().map(JpaProcessInstanceStore::flowNodeRecord).toList();
	}

	@Override
	public List<FlowNodeInstanceRecord> flowNodeInstances(ProcessDefinitionRef process) {
		return entityManager.createQuery("select f from FlowNodeInstanceEntity f where f.processId = :processId"
				+ " and f.processVersion = :version", FlowNodeInstanceEntity.class)
				.setParameter("processId", process.processId())
				.setParameter("version", process.version())
				.getResultList().stream().map(JpaProcessInstanceStore::flowNodeRecord).toList();
	}

	@Override
	public int flowNodeInstanceCount(UUID processInstanceId) {
		return entityManager.createQuery("select count(f) from FlowNodeInstanceEntity f where f.processInstanceId = :instance",
				Long.class)
				.setParameter("instance", processInstanceId)
				.getSingleResult().intValue();
	}

	/** Copia a la entidad lo que puede cambiar durante la vida de la instancia. */
	private static void copyState(ProcessInstanceRecord instance, ProcessInstanceEntity entity) {
		entity.businessKey = instance.businessKey().orElse(null);
		entity.state = instance.state().name();
		entity.currentFlowNodeId = instance.currentFlowNodeId();
		entity.loopCounter = instance.loopCounter();
		entity.selectedSequenceFlowId = instance.selectedSequenceFlowId().orElse(null);
		entity.publishedMessageId = instance.publishedMessage().map(message -> message.messageId().value()).orElse(null);
		entity.publishedMessage = instance.publishedMessage().map(message -> StoredReferences.format(message.message()))
				.orElse(null);
		entity.endedAt = instance.endedAt().orElse(null);
	}

	private static ProcessInstanceRecord instanceRecord(ProcessInstanceEntity entity) {
		var trigger = Optional.ofNullable(entity.triggeringMessageId).map(messageId -> new ExchangedMessage(
				MessageId.of(messageId), StoredReferences.parseMessage(entity.triggeringMessage),
				StoredReferences.parseProcess(entity.triggeringSource), entity.triggeringSourceInstanceId,
				entity.triggeringOccurredAt));
		var published = Optional.ofNullable(entity.publishedMessageId).map(messageId -> new ExchangedMessage(
				MessageId.of(messageId), StoredReferences.parseMessage(entity.publishedMessage),
				ProcessDefinitionRef.of(entity.processId, entity.processVersion), entity.id, entity.endedAt));
		return new ProcessInstanceRecord(entity.id, entity.processId, entity.processVersion,
				CorrelationKey.of(entity.correlationKey), Optional.ofNullable(entity.businessKey),
				ProcessInstanceState.valueOf(entity.state), entity.currentFlowNodeId, entity.loopCounter,
				Optional.ofNullable(entity.selectedSequenceFlowId), trigger, published, entity.startedAt,
				Optional.ofNullable(entity.endedAt), entity.version);
	}

	private static FlowNodeInstanceRecord flowNodeRecord(FlowNodeInstanceEntity entity) {
		return new FlowNodeInstanceRecord(entity.id, entity.processInstanceId, entity.sequence, entity.processId,
				entity.processVersion, CorrelationKey.of(entity.correlationKey), Optional.ofNullable(entity.businessKey),
				entity.flowNodeId, BpmnElementType.valueOf(entity.elementType), entity.loopCounter, entity.occurredAt,
				FlowNodeOutcome.parse(entity.outcome));
	}
}
