package io.github.eldiegos.bpmncode.web;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.TreeMap;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

import io.github.eldiegos.bpmncode.i18n.LabelResolver;

/**
 * Textos del módulo web en un idioma.
 * <p>
 * Responsabilidad: resolver los textos del visor, de las fichas y del modelo buscando primero en los mensajes de la
 * aplicación (que así puede sustituir cualquiera) y después en los de la librería (español e inglés).
 * <p>
 * Invariantes: una clave sin traducción en ninguno de los dos devuelve el texto por defecto; nunca se modifica el
 * {@link MessageSource} de la aplicación.
 *
 * @see ProcessWebAutoConfiguration
 */
class WebTexts {

	static final String WEB_BUNDLE = "io/github/eldiegos/bpmncode/web/messages";
	static final String MODEL_BUNDLE = "io/github/eldiegos/bpmncode/i18n/messages";
	private static final String VIEWER_PREFIX = "pac.web.viewer.";

	private final Optional<MessageSource> application;
	private final ResourceBundleMessageSource library = new ResourceBundleMessageSource();

	WebTexts(Optional<MessageSource> application) {
		this.application = application;
		library.setBasenames(WEB_BUNDLE, MODEL_BUNDLE);
		library.setDefaultEncoding(StandardCharsets.UTF_8.name());
		library.setFallbackToSystemLocale(false);
	}

	/**
	 * Resuelve un texto.
	 *
	 * @param locale idioma
	 * @param key clave
	 * @param fallback texto si no hay traducción
	 * @param arguments argumentos del patrón
	 * @return el texto
	 */
	String text(Locale locale, String key, String fallback, Object... arguments) {
		var own = application.map(messages -> messages.getMessage(key, arguments, null, locale));
		if (own.isPresent()) {
			return own.get();
		}
		var text = library.getMessage(key, arguments, null, locale);
		return text != null ? text : LabelResolver.CANONICAL.resolve(key, fallback, arguments);
	}

	/**
	 * Traductor del modelo en un idioma, con la misma prioridad (aplicación y después librería).
	 *
	 * @param locale idioma
	 * @return el traductor
	 */
	LabelResolver labels(Locale locale) {
		return (key, fallback, arguments) -> text(locale, key, fallback, arguments);
	}

	/**
	 * Textos del visor, sin el prefijo {@value #VIEWER_PREFIX} y con los patrones sin formatear (el visor sustituye los
	 * {@code {n}}).
	 *
	 * @param locale idioma
	 * @return clave corta y texto
	 */
	Map<String, String> viewerTexts(Locale locale) {
		var keys = ResourceBundle.getBundle(WEB_BUNDLE, Locale.ROOT, getClass().getClassLoader()).keySet();
		var texts = new TreeMap<String, String>();
		keys.stream().filter(key -> key.startsWith(VIEWER_PREFIX)).forEach(key -> {
			var raw = application.map(messages -> messages.getMessage(key, null, null, locale))
					.orElseGet(() -> library.getMessage(key, null, key, locale));
			texts.put(key.substring(VIEWER_PREFIX.length()), raw);
		});
		return texts;
	}
}
