package io.github.eldiegos.bpmncode.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de configuración del almacén de instancias ({@code pac.process.store.*}).
 * <p>
 * Responsabilidad: elegir dónde se guardan las instancias y si la librería crea sus tablas. Para guardarlas en otra
 * tecnología (NoSQL, un servicio…) la aplicación declara su propio bean {@link ProcessInstanceStore}.
 * <p>
 * Invariantes: sin configurar, el almacén se elige solo ({@link Type#AUTO}) y las tablas solo se crean en H2.
 *
 * @param type almacén ({@code pac.process.store.type})
 * @param initializeSchema creación de las tablas de los almacenes JDBC y JPA ({@code pac.process.store.initialize-schema})
 * @param undeployedVersions qué hacer al arrancar si hay instancias activas de versiones que ya no están desplegadas
 *        ({@code pac.process.store.undeployed-versions})
 * @see ProcessRuntimeAutoConfiguration
 */
@ConfigurationProperties("pac.process.store")
public record ProcessStoreProperties(@DefaultValue("auto") Type type,
		@DefaultValue("embedded") SchemaInitialization initializeSchema,
		@DefaultValue("warn") UndeployedVersions undeployedVersions) {

	/**
	 * Almacén de instancias.
	 * <p>
	 * Responsabilidad: nombrar las opciones de {@code pac.process.store.type}.
	 * <p>
	 * Invariantes: {@link #AUTO} nunca falla: si no hay base de datos, usa la memoria.
	 */
	public enum Type {
		/**
		 * JPA si la aplicación tiene un {@code EntityManagerFactory}; si no, JDBC si tiene un {@code DataSource} y
		 * {@code spring-jdbc}; si no, memoria.
		 */
		AUTO,
		/** Siempre en memoria, aunque haya base de datos. */
		MEMORY,
		/** Siempre JDBC; la aplicación no arranca si no hay {@code DataSource} o falta {@code spring-jdbc}. */
		JDBC,
		/** Siempre JPA; la aplicación no arranca si no tiene JPA. */
		JPA
	}

	/**
	 * Creación de las tablas de los almacenes JDBC y JPA.
	 * <p>
	 * Responsabilidad: nombrar las opciones de {@code pac.process.store.initialize-schema}.
	 * <p>
	 * Invariantes: el script es idempotente ({@code create table if not exists}), así que ejecutarlo de más no rompe nada.
	 */
	public enum SchemaInitialization {
		/** Solo si la base de datos es H2 (desarrollo y pruebas). */
		EMBEDDED,
		/** Siempre, al arrancar. */
		ALWAYS,
		/** Nunca: las tablas las crea la aplicación (Flyway, Liquibase…). */
		NEVER
	}

	/**
	 * Qué hacer al arrancar con las instancias activas de versiones de proceso que ya no están desplegadas: no pueden
	 * avanzar hasta que vuelva a desplegarse su clase {@code @Process}.
	 * <p>
	 * Responsabilidad: nombrar las opciones de {@code pac.process.store.undeployed-versions}.
	 * <p>
	 * Invariantes: por defecto la librería solo avisa; decidir si eso impide arrancar es cosa de la aplicación.
	 */
	public enum UndeployedVersions {
		/** Registrar un aviso con las versiones afectadas y arrancar. */
		WARN,
		/** No arrancar. */
		FAIL,
		/** No comprobarlo (p. ej. si la aplicación lo vigila por su cuenta). */
		IGNORE
	}
}
