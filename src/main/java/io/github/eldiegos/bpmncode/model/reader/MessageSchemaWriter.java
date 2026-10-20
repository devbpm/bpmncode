package io.github.eldiegos.bpmncode.model.reader;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.eldiegos.bpmncode.model.MessageSchema;

/**
 * Generador del esquema JSON de la carga de una versión de mensaje.
 * <p>
 * Responsabilidad: describir, con JSON Schema 2020-12 y tal como lo serializa Jackson, los componentes de un record de
 * versión, para que su huella detecte cualquier cambio del contrato.
 * <p>
 * Invariantes: la salida es determinista (componentes en orden de declaración y claves en orden fijo); el componente
 * {@code header} del record raíz se omite porque es la cabecera común a todos los mensajes; un {@code Optional} no es
 * obligatorio y todo lo demás sí; un tipo sin representación JSON estable (o un record que se contiene a sí mismo) no
 * genera esquema y se explica por qué.
 *
 * @see MessageSchema
 */
final class MessageSchemaWriter {

	private static final String HEADER = "header";

	private final Deque<Class<?>> path = new ArrayDeque<>();
	private Optional<String> problem = Optional.empty();

	private MessageSchemaWriter() {
	}

	/**
	 * Genera el esquema de una versión.
	 *
	 * @param type record de la versión
	 * @param messageId identificador del mensaje
	 * @param version número de versión
	 * @return el esquema, o el motivo por el que no puede generarse
	 */
	static Generated write(Class<?> type, String messageId, int version) {
		var writer = new MessageSchemaWriter();
		var root = new LinkedHashMap<String, Object>();
		root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
		root.put("$id", "urn:pac:message:" + messageId + ":" + version);
		root.putAll(writer.object(type));
		return writer.problem.<Generated> map(Generated.Unsupported::new)
				.orElseGet(() -> new Generated.Schema(MessageSchema.of(Json.pretty(root))));
	}

	/**
	 * Resultado de generar un esquema.
	 * <p>
	 * Responsabilidad: distinguir el esquema generado del motivo por el que no pudo generarse.
	 * <p>
	 * Invariantes: exactamente uno de los dos casos.
	 *
	 * @see MessageSchemaWriter#write(Class, String, int)
	 */
	sealed interface Generated permits Generated.Schema, Generated.Unsupported {

		/**
		 * Esquema generado.
		 * <p>
		 * Responsabilidad: llevar el esquema y su huella.
		 * <p>
		 * Invariantes: nunca es nulo.
		 *
		 * @param schema el esquema
		 * @see Generated
		 */
		record Schema(MessageSchema schema) implements Generated {
		}

		/**
		 * Tipo sin representación estable.
		 * <p>
		 * Responsabilidad: explicar qué componente impide generar el esquema.
		 * <p>
		 * Invariantes: el motivo nunca está vacío.
		 *
		 * @param reason explicación técnica
		 * @see Generated
		 */
		record Unsupported(String reason) implements Generated {
		}
	}

	private Map<String, Object> object(Class<?> type) {
		var schema = new LinkedHashMap<String, Object>();
		schema.put("type", "object");
		if (path.contains(type)) {
			fail(type.getSimpleName() + " contains itself");
			return schema;
		}
		var root = path.isEmpty();
		path.push(type);
		var properties = new LinkedHashMap<String, Object>();
		var required = new ArrayList<String>();
		for (RecordComponent component : type.getRecordComponents()) {
			if (root && component.getName().equals(HEADER)) {
				continue;
			}
			var generic = component.getGenericType();
			if (generic instanceof ParameterizedType parameterized && parameterized.getRawType() == Optional.class) {
				properties.put(component.getName(), valueOf(parameterized.getActualTypeArguments()[0], component.getName()));
			}
			else {
				properties.put(component.getName(), valueOf(generic, component.getName()));
				required.add(component.getName());
			}
		}
		path.pop();
		schema.put("properties", properties);
		schema.put("required", List.copyOf(required));
		schema.put("additionalProperties", false);
		return schema;
	}

	private Map<String, Object> valueOf(Type type, String where) {
		if (type instanceof Class<?> plain) {
			return valueOf(plain, where);
		}
		if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
			var arguments = parameterized.getActualTypeArguments();
			if (raw == List.class || raw == Set.class || raw == Collection.class) {
				var schema = new LinkedHashMap<String, Object>();
				schema.put("type", "array");
				schema.put("items", valueOf(arguments[0], where));
				if (raw == Set.class) {
					schema.put("uniqueItems", true);
				}
				return schema;
			}
			if (raw == Map.class && arguments[0] == String.class) {
				var schema = new LinkedHashMap<String, Object>();
				schema.put("type", "object");
				schema.put("additionalProperties", valueOf(arguments[1], where));
				return schema;
			}
		}
		return unsupported(type.getTypeName(), where);
	}

	private Map<String, Object> valueOf(Class<?> type, String where) {
		if (type == String.class || type == char.class || type == Character.class) {
			return Map.of("type", "string");
		}
		if (type == int.class || type == long.class || type == short.class || type == byte.class || type == Integer.class
				|| type == Long.class || type == Short.class || type == Byte.class || type == BigInteger.class) {
			return Map.of("type", "integer");
		}
		if (type == double.class || type == float.class || type == Double.class || type == Float.class
				|| type == BigDecimal.class) {
			return Map.of("type", "number");
		}
		if (type == boolean.class || type == Boolean.class) {
			return Map.of("type", "boolean");
		}
		if (type == UUID.class) {
			return formatted("uuid");
		}
		if (type == Instant.class || type == OffsetDateTime.class || type == ZonedDateTime.class) {
			return formatted("date-time");
		}
		if (type == LocalDate.class) {
			return formatted("date");
		}
		if (type == LocalTime.class) {
			return formatted("time");
		}
		if (type == Duration.class) {
			return formatted("duration");
		}
		if (type.isEnum()) {
			var schema = new LinkedHashMap<String, Object>();
			schema.put("type", "string");
			schema.put("enum", Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList());
			return schema;
		}
		if (type.isRecord()) {
			return object(type);
		}
		return unsupported(type.getName(), where);
	}

	private static Map<String, Object> formatted(String format) {
		var schema = new LinkedHashMap<String, Object>();
		schema.put("type", "string");
		schema.put("format", format);
		return schema;
	}

	private Map<String, Object> unsupported(String typeName, String where) {
		fail("component " + where + " has type " + typeName + ", which has no stable JSON representation (use"
				+ " strings, numbers, booleans, UUID, java.time types, enums, records, Optional, List, Set or Map<String, …>)");
		return Map.of();
	}

	private void fail(String reason) {
		if (problem.isEmpty()) {
			problem = Optional.of(reason);
		}
	}

	/**
	 * Escritura de JSON con sangría.
	 * <p>
	 * Responsabilidad: serializar mapas, listas, textos, números y booleanos sin depender de una librería JSON.
	 * <p>
	 * Invariantes: respeta el orden de iteración de los mapas; sangría de dos espacios; escapa los textos según RFC 8259.
	 *
	 * @see MessageSchemaWriter
	 */
	static final class Json {

		private Json() {
		}

		static String pretty(Object value) {
			var out = new StringBuilder();
			write(value, out, 0);
			return out.append('\n').toString();
		}

		private static void write(Object value, StringBuilder out, int indent) {
			switch (value) {
				case null -> out.append("null");
				case Map<?, ?> map when map.isEmpty() -> out.append("{}");
				case Map<?, ?> map -> {
					out.append("{\n");
					var first = true;
					for (var entry : map.entrySet()) {
						if (!first) {
							out.append(",\n");
						}
						first = false;
						out.append("  ".repeat(indent + 1));
						string(entry.getKey().toString(), out);
						out.append(": ");
						write(entry.getValue(), out, indent + 1);
					}
					out.append('\n').append("  ".repeat(indent)).append('}');
				}
				case List<?> list -> {
					out.append('[');
					for (var index = 0; index < list.size(); index++) {
						if (index > 0) {
							out.append(", ");
						}
						write(list.get(index), out, indent);
					}
					out.append(']');
				}
				case Boolean flag -> out.append(flag);
				case Number number -> out.append(number);
				case Object other -> string(other.toString(), out);
			}
		}

		private static void string(String text, StringBuilder out) {
			out.append('"');
			for (var character : text.toCharArray()) {
				if (character == '"' || character == '\\') {
					out.append('\\').append(character);
				}
				else if (character < 0x20) {
					out.append(String.format("\\u%04x", (int) character));
				}
				else {
					out.append(character);
				}
			}
			out.append('"');
		}
	}
}
