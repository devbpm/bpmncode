package io.github.eldiegos.bpmncode.i18n;

import java.util.Locale;
import java.util.Objects;

import org.springframework.context.MessageSource;

/**
 * {@link LabelResolver} respaldado por un {@link MessageSource} de Spring y un idioma.
 * <p>
 * Responsabilidad: resolver los textos del modelo (procesos, nodos, flujos, reglas, mensajes, tipos de elemento) con
 * los ficheros de mensajes de la aplicación, sin que cada aplicación tenga que escribir su propio adaptador.
 * <p>
 * Invariantes: una clave sin traducción devuelve el texto por defecto ({@code fallback}), formateado como hace
 * {@link LabelResolver#CANONICAL}; nunca devuelve la clave ni lanza por una clave ausente. No es un bean: la aplicación
 * lo crea donde lo necesite, normalmente uno por idioma.
 *
 * @see LabelResolver
 */
public final class MessageSourceLabelResolver implements LabelResolver {

	private final MessageSource messages;
	private final Locale locale;
	private final String keyPrefix;

	/**
	 * Crea el resolvedor con el prefijo de claves por defecto ({@value LabelResolver#DEFAULT_KEY_PREFIX}).
	 *
	 * @param messages fuente de mensajes de la aplicación
	 * @param locale idioma de los textos
	 */
	public MessageSourceLabelResolver(MessageSource messages, Locale locale) {
		this(messages, locale, DEFAULT_KEY_PREFIX);
	}

	/**
	 * Crea el resolvedor con un prefijo de claves propio.
	 *
	 * @param messages fuente de mensajes de la aplicación
	 * @param locale idioma de los textos
	 * @param keyPrefix prefijo de las claves del modelo, p. ej. {@code "myapp."}
	 */
	public MessageSourceLabelResolver(MessageSource messages, Locale locale, String keyPrefix) {
		this.messages = Objects.requireNonNull(messages, "messages");
		this.locale = Objects.requireNonNull(locale, "locale");
		this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
	}

	@Override
	public String resolve(String key, String fallback, Object... arguments) {
		var text = messages.getMessage(key, arguments, null, locale);
		return text != null ? text : CANONICAL.resolve(key, fallback, arguments);
	}

	@Override
	public String keyPrefix() {
		return keyPrefix;
	}

	/** Idioma de los textos. */
	public Locale locale() {
		return locale;
	}
}
