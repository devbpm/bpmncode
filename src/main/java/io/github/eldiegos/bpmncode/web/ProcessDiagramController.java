package io.github.eldiegos.bpmncode.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;

/**
 * API de solo lectura del visor: diagramas, su BPMN, las fichas de sus elementos, el camino de una instancia, el mapa
 * de calor de una versión y los textos del visor.
 * <p>
 * Responsabilidad: publicar por HTTP lo que el visor necesita, en {@code <base-path>/api}.
 * <p>
 * Invariantes: solo lee; los textos van en el idioma de {@code ?lang=} o de la petición, indicado en
 * {@code Content-Language}; un diagrama o una instancia desconocidos son 404.
 *
 * @see ProcessWebAutoConfiguration
 */
@RestController
@RequestMapping("${pac.process.web.base-path:" + ProcessWebProperties.DEFAULT_BASE_PATH + "}/api")
class ProcessDiagramController {

	private final BpmnDiagramService diagrams;
	private final DiagramViews views;
	private final WebTexts texts;

	ProcessDiagramController(BpmnDiagramService diagrams, DiagramViews views, WebTexts texts) {
		this.diagrams = diagrams;
		this.views = views;
		this.texts = texts;
	}

	@GetMapping("/views")
	List<DiagramOption> views(@RequestParam(required = false) String lang, Locale locale) {
		return views.options(WebLocale.of(lang, locale));
	}

	@GetMapping("/views/{viewId}")
	ResponseEntity<String> diagram(@PathVariable String viewId, @RequestParam(required = false) String lang, Locale locale) {
		var language = WebLocale.of(lang, locale);
		return diagrams.diagram(viewId)
				.map(view -> ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
						.header(HttpHeaders.CONTENT_DISPOSITION,
								ContentDisposition.inline().filename(viewId + ".bpmn").build().toString())
						.header(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag())
						.body(views.xml(view, language)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/views/{viewId}/elements")
	ResponseEntity<List<DiagramElementInfo>> elements(@PathVariable String viewId, @RequestParam(required = false) String lang,
			Locale locale) {
		var language = WebLocale.of(lang, locale);
		return diagrams.diagram(viewId)
				.map(view -> ResponseEntity.ok().header(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag())
						.body(views.elements(view, language)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/views/{viewId}/heatmap")
	ResponseEntity<HeatmapView> heatmap(@PathVariable String viewId, @RequestParam(required = false) String lang,
			Locale locale) {
		var language = WebLocale.of(lang, locale);
		return diagrams.diagram(viewId).flatMap(view -> views.heatmap(view, language))
				.map(heatmap -> ResponseEntity.ok().header(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag()).body(heatmap))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/instances/{instanceId}/path")
	ResponseEntity<InstancePathView> path(@PathVariable UUID instanceId, @RequestParam(required = false) String lang,
			Locale locale) {
		var language = WebLocale.of(lang, locale);
		return views.path(instanceId, language)
				.map(path -> ResponseEntity.ok().header(HttpHeaders.CONTENT_LANGUAGE, language.toLanguageTag()).body(path))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@GetMapping("/i18n/viewer")
	Map<String, String> viewerTexts(@RequestParam(required = false) String lang, Locale locale) {
		return texts.viewerTexts(WebLocale.of(lang, locale));
	}
}
