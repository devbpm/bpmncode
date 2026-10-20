package io.github.eldiegos.bpmncode.runtime;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.ProcessModelAutoConfiguration;
import io.github.eldiegos.bpmncode.runtime.jpa.JpaProcessInstanceStore;

/**
 * Autoconfiguración de Spring Boot de la ejecución de procesos.
 * <p>
 * Responsabilidad: que cualquier aplicación que añada {@code bpmncode} obtenga el journal, el historial de instancias y
 * un almacén para ambos, declarados como {@code @Bean} explícitos (sin escaneo de componentes), use JPA, JDBC, otra
 * tecnología (con su propio {@link ProcessInstanceStore}) o ninguna.
 * <p>
 * Invariantes: cada bean, también el reloj y el almacén, solo se define si la aplicación no aporta el suyo; sin
 * configurar, el almacén se elige por este orden: JPA, JDBC, memoria; las clases JPA y JDBC de la librería solo se cargan
 * si la aplicación ya tiene esas tecnologías, así que la librería no exige ninguna; se aplica antes que la
 * autoconfiguración JPA de Spring Boot, para que esta vea las entidades del almacén JPA (las nombra por texto para no
 * depender de sus módulos).
 *
 * @see ProcessJournal
 * @see ProcessStoreProperties
 * @see JpaEntityPackageRegistrar
 */
@AutoConfiguration(after = ProcessModelAutoConfiguration.class,
		afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
		beforeName = { "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
			"org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration" })
@EnableConfigurationProperties(ProcessStoreProperties.class)
@Import(JpaEntityPackageRegistrar.class)
public class ProcessRuntimeAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(ProcessRuntimeAutoConfiguration.class);

	private static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";
	private static final String ENTITY_MANAGER_FACTORY = "jakarta.persistence.EntityManagerFactory";
	private static final String SHARED_ENTITY_MANAGER = "org.springframework.orm.jpa.SharedEntityManagerCreator";

	/**
	 * Reloj por defecto para las marcas de tiempo de la evidencia.
	 *
	 * @return el reloj del sistema
	 */
	@Bean
	@ConditionalOnMissingBean
	Clock processClock() {
		return Clock.systemDefaultZone();
	}

	/**
	 * Almacén de instancias: el que fije {@code pac.process.store.type} o, sin configurar, JPA si la aplicación tiene un
	 * {@code EntityManagerFactory}, JDBC si tiene un {@code DataSource} y {@code spring-jdbc}, y memoria si no tiene nada.
	 *
	 * @param properties propiedades {@code pac.process.store.*}
	 * @param dataSource base de datos de la aplicación, si la hay
	 * @param beans fábrica de beans, para buscar el {@code EntityManagerFactory} sin cargar JPA si no está
	 * @param resourceLoader cargador de la aplicación, para saber qué tecnologías tiene
	 * @return el almacén
	 * @throws IllegalStateException si se pide una tecnología que la aplicación no tiene
	 */
	@Bean
	@ConditionalOnMissingBean
	ProcessInstanceStore processInstanceStore(ProcessStoreProperties properties, ObjectProvider<DataSource> dataSource,
			ListableBeanFactory beans, ResourceLoader resourceLoader) {
		var classLoader = resourceLoader.getClassLoader();
		var database = Optional.ofNullable(dataSource.getIfUnique());
		var jdbc = database.isPresent() && JpaEntityPackageRegistrar.present(JDBC_CLIENT, classLoader);
		var store = switch (properties.type()) {
			case MEMORY -> new InMemoryProcessInstanceStore();
			case JDBC -> {
				if (!jdbc) {
					throw new IllegalStateException("pac.process.store.type=jdbc needs a DataSource and spring-jdbc");
				}
				yield JdbcStore.create(database.get());
			}
			case JPA -> (jpaAvailable(classLoader) ? JpaStore.create(beans) : Optional.<ProcessInstanceStore> empty())
					.orElseThrow(() -> new IllegalStateException(
							"pac.process.store.type=jpa needs JPA (an EntityManagerFactory and spring-orm)"));
			case AUTO -> (jpaAvailable(classLoader) ? JpaStore.createIfManaged(beans) : Optional.<ProcessInstanceStore> empty())
					.orElseGet(() -> jdbc ? JdbcStore.create(database.get()) : new InMemoryProcessInstanceStore());
		};
		if (database.isPresent() && JpaEntityPackageRegistrar.present(JDBC_CLIENT, classLoader)
				&& !(store instanceof InMemoryProcessInstanceStore)) {
			JdbcStore.initializeSchema(database.get(), properties.initializeSchema());
		}
		log.info("Process instances are stored with {}", store.getClass().getSimpleName());
		return store;
	}

	/**
	 * Journal de ejecución con conformidad.
	 *
	 * @param registry registro de definiciones
	 * @param store almacén de instancias
	 * @param clock reloj de la evidencia
	 * @return el journal
	 */
	@Bean
	@ConditionalOnMissingBean
	ProcessJournal processJournal(ProcessDefinitionRegistry registry, ProcessInstanceStore store, Clock clock) {
		return new ProcessJournal(registry, store, clock);
	}

	/**
	 * Consultas sobre las instancias y su historial.
	 *
	 * @param registry registro de definiciones
	 * @param store almacén de instancias
	 * @return el historial
	 */
	@Bean
	@ConditionalOnMissingBean
	ProcessInstanceHistory processInstanceHistory(ProcessDefinitionRegistry registry, ProcessInstanceStore store) {
		return new ProcessInstanceHistory(registry, store);
	}

	/**
	 * Indica si la aplicación tiene JPA y {@code spring-orm}.
	 *
	 * @param classLoader cargador de la aplicación
	 * @return {@code true} si puede usarse el almacén JPA
	 */
	static boolean jpaAvailable(ClassLoader classLoader) {
		return JpaEntityPackageRegistrar.present(ENTITY_MANAGER_FACTORY, classLoader)
				&& JpaEntityPackageRegistrar.present(SHARED_ENTITY_MANAGER, classLoader);
	}

	/**
	 * Creación del almacén JDBC, aislada para que sus clases solo se carguen si {@code spring-jdbc} está presente.
	 * <p>
	 * Responsabilidad: crear el almacén y, según la configuración, sus tablas.
	 * <p>
	 * Invariantes: solo se usa tras comprobar que {@code JdbcClient} está en el classpath.
	 */
	private static final class JdbcStore {

		private JdbcStore() {
		}

		static ProcessInstanceStore create(DataSource dataSource) {
			return new JdbcProcessInstanceStore(dataSource);
		}

		/** Crea las tablas (sirven al almacén JDBC y al JPA) si la configuración lo pide. */
		static void initializeSchema(DataSource dataSource, ProcessStoreProperties.SchemaInitialization mode) {
			if (mode == ProcessStoreProperties.SchemaInitialization.ALWAYS
					|| mode == ProcessStoreProperties.SchemaInitialization.EMBEDDED && isH2(dataSource)) {
				JdbcProcessInstanceStore.initializeSchema(dataSource);
			}
		}

		private static boolean isH2(DataSource dataSource) {
			try (var connection = dataSource.getConnection()) {
				return "H2".equals(connection.getMetaData().getDatabaseProductName());
			}
			catch (SQLException e) {
				throw new IllegalStateException("Cannot read the database product name", e);
			}
		}
	}

	/**
	 * Creación del almacén JPA, aislada para que sus clases solo se carguen si la aplicación tiene JPA.
	 * <p>
	 * Responsabilidad: buscar la unidad de persistencia de la aplicación y crear el almacén sobre ella.
	 * <p>
	 * Invariantes: solo se usa tras comprobar que {@code EntityManagerFactory} y {@code spring-orm} están en el classpath.
	 */
	private static final class JpaStore {

		private JpaStore() {
		}

		/** Almacén sobre la unidad de persistencia de la aplicación, o vacío si no tiene una. */
		static Optional<ProcessInstanceStore> create(ListableBeanFactory beans) {
			return entityManagerFactory(beans).map(JpaProcessInstanceStore::new);
		}

		/** Como {@link #create}, pero vacío (con un aviso) si la unidad de persistencia no gestiona las entidades. */
		static Optional<ProcessInstanceStore> createIfManaged(ListableBeanFactory beans) {
			return entityManagerFactory(beans).filter(entityManagerFactory -> {
				var managed = JpaProcessInstanceStore.manages(entityManagerFactory);
				if (!managed) {
					log.warn("The persistence unit does not manage the bpmncode entities (an @EntityScan without the package {}?):"
							+ " process instances are not stored with JPA", JpaEntityPackageRegistrar.ENTITY_PACKAGE);
				}
				return managed;
			}).map(JpaProcessInstanceStore::new);
		}

		private static Optional<EntityManagerFactory> entityManagerFactory(ListableBeanFactory beans) {
			return Optional.ofNullable(beans.getBeanProvider(EntityManagerFactory.class).getIfUnique());
		}
	}
}
