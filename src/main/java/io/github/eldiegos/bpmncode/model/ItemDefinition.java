package io.github.eldiegos.bpmncode.model;

/**
 * Definición de la estructura de datos de un mensaje BPMN ({@code bpmn:itemDefinition}).
 * <p>
 * Responsabilidad: ligar el mensaje con el tipo Java del evento que lo transporta mediante {@code structureRef}.
 * <p>
 * Invariantes: el identificador y la referencia a la estructura nunca están vacíos; {@code structureRef} es el nombre
 * canónico del tipo Java, que es un QName XML válido.
 *
 * @param id identificador del elemento, p. ej. {@code ItemDefinition_EV-PERSON-REGISTERED}
 * @param structureRef nombre canónico del tipo Java del evento (atributo {@code structureRef})
 * @see Message#itemDefinition()
 */
public record ItemDefinition(String id, String structureRef) {

	public ItemDefinition {
		if (id == null || id.isBlank() || structureRef == null || structureRef.isBlank()) {
			throw new IllegalArgumentException("An item definition needs an id and a structureRef");
		}
	}

	/**
	 * Define la estructura de un mensaje a partir de su tipo Java.
	 *
	 * @param messageId identificador del mensaje
	 * @param type tipo Java del evento
	 * @return la definición, con id {@code ItemDefinition_<messageId>}
	 */
	public static ItemDefinition of(String messageId, Class<?> type) {
		return new ItemDefinition("ItemDefinition_" + messageId, type.getCanonicalName());
	}

	/**
	 * Define la estructura de una versión de un mensaje a partir de su record.
	 *
	 * @param messageId identificador del mensaje
	 * @param version número de versión
	 * @param type record de la versión
	 * @return la definición, con id {@code ItemDefinition_<messageId>_v<version>}
	 */
	public static ItemDefinition ofVersion(String messageId, int version, Class<?> type) {
		return new ItemDefinition("ItemDefinition_" + messageId + "_v" + version, type.getCanonicalName());
	}
}
