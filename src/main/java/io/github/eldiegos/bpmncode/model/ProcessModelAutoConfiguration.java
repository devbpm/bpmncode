package io.github.eldiegos.bpmncode.model;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;

import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.trace.TraceabilityReport;

/**
 * Autoconfiguración de Spring Boot del modelo de procesos.
 * <p>
 * Responsabilidad: que cualquier aplicación que añada {@code bpmncode} obtenga el registro de definiciones
 * leído de sus paquetes y el informe de trazabilidad, declarados como {@code @Bean} explícitos (sin escaneo de
 * componentes).
 * <p>
 * Invariantes: el registro lee los procesos de los paquetes de autoconfiguración de la aplicación; cada bean solo se
 * define si la aplicación no aporta el suyo.
 *
 * @see ProcessDefinitionRegistry
 */
@AutoConfiguration
public class ProcessModelAutoConfiguration {

	/**
	 * Registro de definiciones leído de los paquetes de la aplicación.
	 *
	 * @param beanFactory fábrica de beans, de la que se obtienen los paquetes de autoconfiguración
	 * @param environment configuración, para resolver los {@code ${…}} de los {@code @Scheduled}
	 * @param resourceLoader cargador de recursos, para leer {@code pac/message-contracts.lock}
	 * @return el registro
	 * @throws ProcessModelException si el modelo anotado no es válido
	 */
	@Bean
	@ConditionalOnMissingBean
	ProcessDefinitionRegistry processDefinitionRegistry(BeanFactory beanFactory, Environment environment,
			ResourceLoader resourceLoader) {
		var packages = AutoConfigurationPackages.get(beanFactory);
		return new ProcessDefinitionRegistry(AnnotationProcessModelReader.scan(environment::resolvePlaceholders,
				MessageContractLock.fromClasspath(resourceLoader), packages.toArray(String[]::new)));
	}

	/**
	 * Catálogo de los contratos de mensaje, con el fichero de bloqueo del classpath.
	 *
	 * @param registry registro de definiciones
	 * @param resourceLoader cargador de recursos, para leer {@code pac/message-contracts.lock}
	 * @return el catálogo
	 */
	@Bean
	@ConditionalOnMissingBean
	MessageContractCatalog messageContractCatalog(ProcessDefinitionRegistry registry, ResourceLoader resourceLoader) {
		return new MessageContractCatalog(registry.definitions(), MessageContractLock.fromClasspath(resourceLoader));
	}

	/**
	 * Informe de trazabilidad del modelo.
	 *
	 * @param registry registro de definiciones
	 * @return el informe
	 */
	@Bean
	@ConditionalOnMissingBean
	TraceabilityReport traceabilityReport(ProcessDefinitionRegistry registry) {
		return new TraceabilityReport(registry);
	}
}
