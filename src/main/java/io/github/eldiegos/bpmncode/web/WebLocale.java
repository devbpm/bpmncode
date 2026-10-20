package io.github.eldiegos.bpmncode.web;

import java.util.Locale;

/**
 * Idioma de una petición al módulo web.
 * <p>
 * Responsabilidad: elegir el idioma de los textos: el del parámetro {@code ?lang=} si lo hay (el visor lo reenvía en
 * todas sus llamadas) y si no el que resuelve Spring para la petición.
 * <p>
 * Invariantes: nunca devuelve nulo; un {@code lang} vacío o mal formado se ignora.
 *
 * @see ProcessDiagramController
 */
final class WebLocale {

	private WebLocale() {
	}

	static Locale of(String lang, Locale requestLocale) {
		if (lang != null && !lang.isBlank()) {
			var requested = Locale.forLanguageTag(lang.strip());
			if (!requested.getLanguage().isEmpty()) {
				return requested;
			}
		}
		return requestLocale == null ? Locale.ENGLISH : requestLocale;
	}
}
