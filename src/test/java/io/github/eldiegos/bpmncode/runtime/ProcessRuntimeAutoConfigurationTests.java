package io.github.eldiegos.bpmncode.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * La ejecución funciona con y sin base de datos, y el almacén se elige según lo que tenga la aplicación.
 */
class ProcessRuntimeAutoConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ProcessRuntimeAutoConfiguration.class))
			.withBean(ProcessDefinitionRegistry.class, () -> new ProcessDefinitionRegistry(
					AnnotationProcessModelReader.read(List.of(ProcessInstanceStoreContract.Order.class))));

	@Test
	void withoutADatabaseInstancesLiveInMemory() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(ProcessInstanceStore.class)).isInstanceOf(InMemoryProcessInstanceStore.class);
			assertThat(context).hasSingleBean(ProcessJournal.class).hasSingleBean(ProcessInstanceHistory.class);
		});
	}

	@Test
	void withADataSourceInstancesAreStoredWithJdbcAndTheTablesAreCreatedInH2() {
		runner.withBean(DataSource.class, ProcessRuntimeAutoConfigurationTests::h2).run(context -> {
			assertThat(context.getBean(ProcessInstanceStore.class)).isInstanceOf(JdbcProcessInstanceStore.class);
			var tables = JdbcClient.create(context.getBean(DataSource.class))
					.sql("select count(*) from information_schema.tables where lower(table_name) like 'pac_%'")
					.query(Integer.class).single();
			assertThat(tables).isEqualTo(2);
		});
	}

	@Test
	void theApplicationCanChooseMemoryEvenWithADataSource() {
		runner.withBean(DataSource.class, ProcessRuntimeAutoConfigurationTests::h2)
				.withPropertyValues("pac.process.store.type=memory")
				.run(context -> assertThat(context.getBean(ProcessInstanceStore.class))
						.isInstanceOf(InMemoryProcessInstanceStore.class));
	}

	@Test
	void withoutSpringJdbcInstancesLiveInMemoryEvenWithADataSource() {
		runner.withBean(DataSource.class, ProcessRuntimeAutoConfigurationTests::h2)
				.withClassLoader(new FilteredClassLoader(JdbcClient.class))
				.run(context -> assertThat(context.getBean(ProcessInstanceStore.class))
						.isInstanceOf(InMemoryProcessInstanceStore.class));
	}

	@Test
	void askingForJdbcWithoutADataSourceFails() {
		runner.withPropertyValues("pac.process.store.type=jdbc")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void theApplicationCanProvideItsOwnStore() {
		var own = new InMemoryProcessInstanceStore();
		runner.withBean(ProcessInstanceStore.class, () -> own)
				.run(context -> assertThat(context.getBean(ProcessInstanceStore.class)).isSameAs(own));
	}

	private static DataSource h2() {
		return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
	}
}
