package io.github.eldiegos.bpmncode.bpmn;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.ProcessModelAutoConfiguration;

/**
 * Autoconfiguración de Spring Boot de la exportación BPMN.
 * <p>
 * Responsabilidad: registrar {@link BpmnDiagramService} como {@code @Bean} explícito (sin escaneo de componentes) en
 * cualquier aplicación que añada {@code bpmncode}, con las {@link BpmnProperties} de la aplicación.
 * <p>
 * Invariantes: se aplica después de la autoconfiguración del modelo, del que depende; el servicio solo se define si la
 * aplicación no aporta el suyo.
 *
 * @see BpmnDiagramService
 */
@AutoConfiguration(after = ProcessModelAutoConfiguration.class)
@EnableConfigurationProperties(BpmnProperties.class)
public class BpmnAutoConfiguration {

	/**
	 * Servicio de diagramas BPMN.
	 *
	 * @param registry registro de definiciones
	 * @param properties propiedades {@code pac.process.bpmn.*}
	 * @return el servicio
	 */
	@Bean
	@ConditionalOnMissingBean
	BpmnDiagramService bpmnDiagramService(ProcessDefinitionRegistry registry, BpmnProperties properties) {
		return new BpmnDiagramService(registry, properties.targetNamespace());
	}
}
