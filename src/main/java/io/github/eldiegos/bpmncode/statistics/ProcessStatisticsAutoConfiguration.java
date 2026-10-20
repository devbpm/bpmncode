package io.github.eldiegos.bpmncode.statistics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.runtime.ProcessRuntimeAutoConfiguration;

/**
 * Autoconfiguración de Spring Boot de las estadísticas de ejecución.
 * <p>
 * Responsabilidad: registrar {@link ProcessExecutionStatistics} como {@code @Bean} explícito (sin escaneo de
 * componentes) en cualquier aplicación que añada {@code bpmncode}.
 * <p>
 * Invariantes: se aplica después de la autoconfiguración de ejecución, de la que depende; el servicio solo se define si
 * la aplicación no aporta el suyo. No activa nada más: si la aplicación no lo inyecta, no se usa.
 *
 * @see ProcessExecutionStatistics
 */
@AutoConfiguration(after = ProcessRuntimeAutoConfiguration.class)
public class ProcessStatisticsAutoConfiguration {

	/**
	 * Estadísticas de ejecución.
	 *
	 * @param registry registro de definiciones
	 * @param history historial de instancias
	 * @return el servicio
	 */
	@Bean
	@ConditionalOnMissingBean
	ProcessExecutionStatistics processExecutionStatistics(ProcessDefinitionRegistry registry, ProcessInstanceHistory history) {
		return new ProcessExecutionStatistics(registry, history);
	}
}
