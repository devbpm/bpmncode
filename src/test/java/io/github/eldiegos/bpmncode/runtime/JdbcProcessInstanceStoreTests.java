package io.github.eldiegos.bpmncode.runtime;

import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/**
 * El almacén JDBC cumple el contrato de los almacenes, sobre una base de datos H2 con el script de la librería.
 */
class JdbcProcessInstanceStoreTests extends ProcessInstanceStoreContract {

	@Override
	protected ProcessInstanceStore newStore() {
		var database = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
		JdbcProcessInstanceStore.initializeSchema(database);
		return new JdbcProcessInstanceStore(database);
	}
}
