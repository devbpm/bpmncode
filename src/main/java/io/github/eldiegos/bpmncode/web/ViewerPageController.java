package io.github.eldiegos.bpmncode.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

/**
 * Página del visor bpmn-js.
 * <p>
 * Responsabilidad: servir {@code <base-path>/viewer.html} con las direcciones de su API, de sus recursos y de bpmn-js
 * resueltas para esta aplicación (ruta de contexto incluida).
 * <p>
 * Invariantes: la plantilla se lee una vez; los valores que se insertan se escapan para HTML; la página acepta los
 * parámetros del visor ({@code view}, {@code element}, {@code instance}, {@code heatmap}, {@code lang}).
 *
 * @see ProcessWebAutoConfiguration
 */
@Controller
class ViewerPageController {

	static final String TEMPLATE = "io/github/eldiegos/bpmncode/web/viewer.html";

	private final ProcessWebProperties properties;
	private final String template;

	ViewerPageController(ProcessWebProperties properties) {
		this.properties = properties;
		try {
			this.template = new ClassPathResource(TEMPLATE, getClass().getClassLoader())
					.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException("Cannot read " + TEMPLATE, unreadable);
		}
	}

	@GetMapping("${pac.process.web.base-path:" + ProcessWebProperties.DEFAULT_BASE_PATH + "}/viewer.html")
	ResponseEntity<String> viewer(HttpServletRequest request, @RequestParam(required = false) String lang, Locale locale) {
		var context = request.getContextPath();
		var base = context + properties.basePath();
		var bpmnJs = properties.bpmnJsLocation().startsWith("/") ? context + properties.bpmnJsLocation()
				: properties.bpmnJsLocation();
		var page = template
				.replace("{{lang}}", HtmlUtils.htmlEscape(WebLocale.of(lang, locale).getLanguage()))
				.replace("{{api}}", HtmlUtils.htmlEscape(base + "/api"))
				.replace("{{static}}", HtmlUtils.htmlEscape(base + "/static"))
				.replace("{{bpmnJs}}", HtmlUtils.htmlEscape(bpmnJs));
		return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(page);
	}
}
