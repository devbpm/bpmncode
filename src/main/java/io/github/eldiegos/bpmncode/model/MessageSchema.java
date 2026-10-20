package io.github.eldiegos.bpmncode.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Esquema JSON (JSON Schema 2020-12) de la carga de una versión de mensaje y su huella.
 * <p>
 * Responsabilidad: fijar el contrato de datos de una versión de forma comparable: dos esquemas iguales tienen la misma
 * huella y cualquier cambio de campo, tipo u obligatoriedad la cambia.
 * <p>
 * Invariantes: el JSON se genera de forma determinista a partir de los componentes del record (en su orden de
 * declaración, sin la cabecera {@code header}, que es común a todos los mensajes); la huella es
 * {@code sha256:<hex>} del JSON en UTF-8.
 *
 * @param json esquema JSON con sangría de dos espacios
 * @param fingerprint huella {@code sha256:<hex>}
 * @see MessageVersionDefinition
 */
public record MessageSchema(String json, String fingerprint) {

	public MessageSchema {
		if (json == null || json.isBlank() || fingerprint == null || !fingerprint.startsWith("sha256:")) {
			throw new IllegalArgumentException("A message schema needs its JSON and a sha256 fingerprint");
		}
	}

	/**
	 * Calcula la huella de un esquema.
	 *
	 * @param json esquema JSON
	 * @return el esquema con su huella
	 */
	public static MessageSchema of(String json) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
			return new MessageSchema(json, "sha256:" + HexFormat.of().formatHex(digest));
		}
		catch (NoSuchAlgorithmException unavailable) {
			throw new IllegalStateException("SHA-256 is not available", unavailable);
		}
	}
}
