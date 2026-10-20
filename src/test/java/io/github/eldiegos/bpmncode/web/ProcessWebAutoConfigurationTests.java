package io.github.eldiegos.bpmncode.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import fixture.process.ValidProcesses;

import io.github.eldiegos.bpmncode.bpmn.BpmnAutoConfiguration;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.runtime.CorrelationKey;
import io.github.eldiegos.bpmncode.runtime.FlowNodeOutcome;
import io.github.eldiegos.bpmncode.runtime.ProcessJournal;
import io.github.eldiegos.bpmncode.runtime.ProcessRuntimeAutoConfiguration;
import io.github.eldiegos.bpmncode.statistics.ProcessStatisticsAutoConfiguration;
import io.github.eldiegos.bpmncode.trace.TraceabilityReport;

/**
 * El módulo web está apagado por defecto y, al activarlo, publica el visor y su API de solo lectura.
 */
class ProcessWebAutoConfigurationTests {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ProcessRuntimeAutoConfiguration.class, BpmnAutoConfiguration.class,
					ProcessStatisticsAutoConfiguration.class, ProcessWebAutoConfiguration.class))
			.withUserConfiguration(Mvc.class)
			.withBean(ProcessDefinitionRegistry.class, () -> new ProcessDefinitionRegistry(AnnotationProcessModelReader.read(
					List.of(ValidProcesses.OrderV1.class, ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class,
							ValidProcesses.Shipping.class, ValidProcesses.ShippingStarter.class, ValidProcesses.Reviewer.class,
							ValidProcesses.Review.class))))
			.withBean(TraceabilityReport.class, () -> new TraceabilityReport(new ProcessDefinitionRegistry(
					AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class, ValidProcesses.CreditRule.class,
							ValidProcesses.OrderAccepted.class)))));

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class Mvc {
	}

	@Test
	void nothingIsPublishedUnlessTheApplicationEnablesIt() {
		runner.run(context -> assertThat(context).doesNotHaveBean(ProcessDiagramController.class)
				.doesNotHaveBean(ViewerPageController.class));
	}

	@Test
	void theViewerPageKnowsItsApiItsResourcesAndBpmnJs() {
		runner.withPropertyValues("pac.process.web.enabled=true").run(context -> {
			var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
			mvc.perform(get("/bpmncode/viewer.html").param("lang", "es")).andExpect(status().isOk())
					.andExpect(content().string(Matchers.allOf(Matchers.containsString("<html lang=\"es\">"),
							Matchers.containsString("data-api=\"/bpmncode/api\""),
							Matchers.containsString("/bpmncode/static/viewer.js"),
							Matchers.containsString("/webjars/bpmn-js/18.10.1/dist/bpmn-navigated-viewer.production.min.js"))));
			mvc.perform(get("/bpmncode/static/viewer.js")).andExpect(status().isOk())
					.andExpect(content().string(Matchers.containsString("data-api")));
			mvc.perform(get("/bpmncode/api/i18n/viewer").param("lang", "es")).andExpect(status().isOk())
					.andExpect(jsonPath("$.fit").value("Ajustar a la ventana"));
			mvc.perform(get("/bpmncode/api/i18n/viewer").param("lang", "en")).andExpect(jsonPath("$.fit").value("Fit to window"));
		});
	}

	@Test
	void theApiDescribesDiagramsInstancesAndHeatmaps() {
		runner.withPropertyValues("pac.process.web.enabled=true").run(context -> {
			var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
			var journal = context.getBean(ProcessJournal.class);
			var instance = journal.startProcessInstance(ProcessDefinitionRef.of("FX-ORDER", 1), CorrelationKey.newKey(),
					FlowNodeOutcome.of("START"));
			journal.completeActivity(instance, "FX-CHECK", FlowNodeOutcome.of("CHECKED"));

			mvc.perform(get("/bpmncode/api/views").param("lang", "en")).andExpect(status().isOk())
					.andExpect(jsonPath("$[*].id", Matchers.hasItems("FX-ORDER", "FX-SHIPPING", "COLLABORATION")));
			mvc.perform(get("/bpmncode/api/views/FX-ORDER")).andExpect(status().isOk())
					.andExpect(content().string(Matchers.containsString("<bpmn:process id=\"FX-ORDER\"")));
			mvc.perform(get("/bpmncode/api/views/FX-ORDER/elements").param("lang", "es")).andExpect(status().isOk())
					.andExpect(header().string("Content-Language", "es"))
					.andExpect(jsonPath("$[?(@.id == 'FX-DECIDE')].runningHere").value(1))
					.andExpect(jsonPath("$[?(@.id == 'FX-CHECK')].kind").value("Tarea de reglas de negocio"));
			mvc.perform(get("/bpmncode/api/views/FX-ORDER/heatmap")).andExpect(status().isOk())
					.andExpect(jsonPath("$.instances").value(1))
					.andExpect(jsonPath("$.nodes[?(@.nodeId == 'FX-CHECK')].executions").value(1));
			mvc.perform(get("/bpmncode/api/views/COLLABORATION/heatmap")).andExpect(status().isNotFound());
			mvc.perform(get("/bpmncode/api/instances/" + instance.instanceId() + "/path")).andExpect(status().isOk())
					.andExpect(jsonPath("$.steps[*].nodeId", Matchers.contains("FX-START", "FX-CHECK")))
					.andExpect(jsonPath("$.waitingNodeId").value("FX-DECIDE"));
			mvc.perform(get("/bpmncode/api/processes/instances")).andExpect(status().isOk())
					.andExpect(jsonPath("$[0].processId").value("FX-ORDER"));
		});
	}

	@Test
	void theCardOfAUserTaskListsWhoDoesItApartFromItsEvents() {
		runner.withPropertyValues("pac.process.web.enabled=true").run(context -> {
			var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
			mvc.perform(get("/bpmncode/api/views/FX-REVIEW/elements").param("lang", "es")).andExpect(status().isOk())
					.andExpect(jsonPath("$[?(@.id == 'FX-REV-REVIEW')].events[*]").isEmpty())
					.andExpect(jsonPath("$[?(@.id == 'FX-REV-REVIEW')].roles[*].kind", Matchers.contains(
							"Propietario potencial", "Propietario potencial", "Persona que la realiza")))
					.andExpect(jsonPath("$[?(@.id == 'FX-REV-REVIEW')].roles[0].resourceId").value("FX-RES-REVIEWER"))
					.andExpect(jsonPath("$[?(@.id == 'FX-REV-REVIEW')].roles[1].name").value("team lead of the author"))
					.andExpect(jsonPath("$[?(@.id == 'FX-REV-START')].roles[*]").isEmpty());
			mvc.perform(get("/bpmncode/api/i18n/viewer").param("lang", "es"))
					.andExpect(jsonPath("$['details.roles']").value("Quién la hace"));
		});
	}

	@Test
	void theBasePathIsConfigurable() {
		runner.withPropertyValues("pac.process.web.enabled=true", "pac.process.web.base-path=/tools/bpmn").run(context -> {
			var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context).build();
			mvc.perform(get("/tools/bpmn/api/views")).andExpect(status().isOk());
			mvc.perform(get("/tools/bpmn/viewer.html")).andExpect(content().string(Matchers.containsString(
					"data-api=\"/tools/bpmn/api\"")));
			mvc.perform(get("/bpmncode/api/views")).andExpect(status().isNotFound());
		});
	}
}
