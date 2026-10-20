package io.github.eldiegos.bpmncode.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;

/**
 * Huellas congeladas de las versiones de mensaje: el contenido de {@code pac/message-contracts.lock}.
 * <p>
 * Responsabilidad: impedir que el contrato de una versión publicada cambie o desaparezca sin que nadie lo note. El
 * fichero se revisa en el control de versiones como cualquier otro contrato: cada línea
 * {@code EV-PERSON-REGISTERED@1 = sha256:<hex>} congela el esquema de una versión.
 * <p>
 * Invariantes: cada versión aparece una vez; las líneas vacías y las que empiezan por {@code #} se ignoran; una línea
 * mal formada impide leer el fichero.
 *
 * @param fingerprints huella congelada por versión ({@code <messageId>@<version>})
 * @see MessageSchema#fingerprint()
 */
public record MessageContractLock(Map<String, String> fingerprints) {

	/** Ubicación del fichero en el classpath de la aplicación. */
	public static final String LOCATION = "pac/message-contracts.lock";

	private static final Pattern LINE = Pattern.compile("([A-Za-z_][A-Za-z0-9_.-]*@[1-9][0-9]*)\\s*=\\s*(sha256:[0-9a-f]{64})");

	public MessageContractLock {
		fingerprints = Map.copyOf(fingerprints);
	}

	/**
	 * Lee el contenido de uno o varios ficheros de bloqueo.
	 *
	 * @param content texto del fichero
	 * @param source de dónde procede, para los errores
	 * @return las huellas
	 * @throws IllegalArgumentException si una línea está mal formada o una versión se repite con otra huella
	 */
	public static MessageContractLock parse(String content, String source) {
		var fingerprints = new TreeMap<String, String>();
		var number = 0;
		for (var line : content.split("\\R")) {
			number++;
			var trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			var matcher = LINE.matcher(trimmed);
			if (!matcher.matches()) {
				throw new IllegalArgumentException(source + ":" + number + " is not '<messageId>@<version> = sha256:<hex>'");
			}
			var previous = fingerprints.put(matcher.group(1), matcher.group(2));
			if (previous != null && !previous.equals(matcher.group(2))) {
				throw new IllegalArgumentException(source + ":" + number + " locks " + matcher.group(1) + " twice");
			}
		}
		return new MessageContractLock(fingerprints);
	}

	/**
	 * Lee y une todos los {@code pac/message-contracts.lock} del classpath (la aplicación y sus jars).
	 *
	 * @param resourceLoader cargador de recursos de la aplicación
	 * @return las huellas, o vacío si no hay ningún fichero (entonces no se comprueban los contratos)
	 * @throws UncheckedIOException si un fichero no se puede leer
	 * @throws IllegalArgumentException si un fichero está mal formado o dos ficheros se contradicen
	 */
	public static Optional<MessageContractLock> fromClasspath(ResourceLoader resourceLoader) {
		try {
			var resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader)
					.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + LOCATION);
			Optional<MessageContractLock> lock = Optional.empty();
			for (var resource : resources) {
				var parsed = parse(resource.getContentAsString(StandardCharsets.UTF_8), resource.getDescription());
				lock = Optional.of(lock.map(previous -> previous.merge(parsed)).orElse(parsed));
			}
			return lock;
		}
		catch (IOException unreadable) {
			throw new UncheckedIOException("Cannot read " + LOCATION, unreadable);
		}
	}

	/**
	 * Indica si una versión está congelada con la huella de su esquema actual.
	 *
	 * @param version versión
	 * @param schema esquema actual de la versión
	 * @return {@code true} si el fichero la congela con esa huella
	 */
	public boolean freezes(MessageVersionRef version, MessageSchema schema) {
		return schema.fingerprint().equals(fingerprints.get(version.toString()));
	}

	/**
	 * Une las huellas de otro fichero (p. ej. de otro jar).
	 *
	 * @param other otras huellas
	 * @return la unión
	 * @throws IllegalArgumentException si una versión aparece en ambos con distinta huella
	 */
	public MessageContractLock merge(MessageContractLock other) {
		var merged = new TreeMap<>(fingerprints);
		other.fingerprints.forEach((key, fingerprint) -> {
			var previous = merged.put(key, fingerprint);
			if (previous != null && !previous.equals(fingerprint)) {
				throw new IllegalArgumentException(key + " is locked with two different fingerprints");
			}
		});
		return new MessageContractLock(merged);
	}

	/**
	 * Línea que congela una versión, tal como debe escribirse en el fichero.
	 *
	 * @param version versión
	 * @param schema esquema de la versión
	 * @return {@code <messageId>@<version> = sha256:<hex>}
	 */
	public static String lineOf(MessageVersionRef version, MessageSchema schema) {
		return version + " = " + schema.fingerprint();
	}
}
