package io.github.eldiegos.bpmncode.web;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import io.github.eldiegos.bpmncode.bpmn.BpmnAutoConfiguration;
import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;
import io.github.eldiegos.bpmncode.statistics.ProcessExecutionStatistics;
import io.github.eldiegos.bpmncode.statistics.ProcessStatisticsAutoConfiguration;
import io.github.eldiegos.bpmncode.trace.TraceabilityReport;

/**
 * Autoconfiguración del módulo web opcional: API de solo lectura y visor bpmn-js.
 * <p>
 * Responsabilidad: publicar en {@code pac.process.web.base-path} (por defecto {@code /bpmncode}) el visor
 * ({@code viewer.html}), sus recursos ({@code static/**}), la API del visor ({@code api/views…},
 * {@code api/instances/{id}/path}, {@code api/i18n/viewer}) y la API técnica de gobierno ({@code api/processes…}).
 * <p>
 * Invariantes: no se activa salvo que la aplicación sea web (servlet), tenga Spring MVC y ponga
 * {@code pac.process.web.enabled=true}, porque expone el código que implementa cada paso y los datos de las instancias;
 * no añade seguridad: si la aplicación la tiene, protege estas rutas como las suyas. bpmn-js lo aporta la aplicación
 * (p. ej. el WebJar {@code org.webjars.npm:bpmn-js}) en {@code pac.process.web.bpmn-js-location}.
 *
 * @see ProcessWebProperties
 */
@AutoConfiguration(after = { BpmnAutoConfiguration.class, ProcessStatisticsAutoConfiguration.class })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnProperty(prefix = "pac.process.web", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ProcessWebProperties.class)
public class ProcessWebAutoConfiguration {

	static final String STATIC_LOCATION = "classpath:/io/github/eldiegos/bpmncode/web/static/";

	@Bean
	WebTexts bpmncodeWebTexts(ObjectProvider<MessageSource> messageSource) {
		return new WebTexts(Optional.ofNullable(messageSource.getIfAvailable()));
	}

	@Bean
	DiagramViews bpmncodeDiagramViews(BpmnDiagramService diagrams, ProcessDefinitionRegistry registry,
			ProcessExecutionStatistics statistics, WebTexts texts) {
		return new DiagramViews(diagrams, registry, statistics, texts);
	}

	@Bean
	ProcessDiagramController bpmncodeProcessDiagramController(BpmnDiagramService diagrams, DiagramViews views,
			WebTexts texts) {
		return new ProcessDiagramController(diagrams, views, texts);
	}

	@Bean
	ProcessGovernanceController bpmncodeProcessGovernanceController(ProcessDefinitionRegistry registry,
			TraceabilityReport traceability, ProcessInstanceHistory history) {
		return new ProcessGovernanceController(registry, traceability, history);
	}

	@Bean
	ViewerPageController bpmncodeViewerPageController(ProcessWebProperties properties) {
		return new ViewerPageController(properties);
	}

	/**
	 * Recursos estáticos del visor ({@code viewer.js}, {@code viewer.css}) en {@code <base-path>/static/**}.
	 *
	 * @param properties propiedades del módulo web
	 * @return la configuración de Spring MVC
	 */
	@Bean
	WebMvcConfigurer bpmncodeViewerResources(ProcessWebProperties properties) {
		return new WebMvcConfigurer() {
			@Override
			public void addResourceHandlers(ResourceHandlerRegistry registry) {
				registry.addResourceHandler(properties.basePath() + "/static/**").addResourceLocations(STATIC_LOCATION);
			}
		};
	}
}
