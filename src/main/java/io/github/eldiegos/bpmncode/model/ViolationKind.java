package io.github.eldiegos.bpmncode.model;

import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;

/**
 * Clase de problema del modelo de procesos, en el vocabulario de BPMN.
 * <p>
 * Responsabilidad: permitir que las pruebas y las herramientas reconozcan el problema sin leer el mensaje.
 * <p>
 * Invariantes: cada constante corresponde a una comprobación del lector.
 *
 * @see ModelViolation
 * @see AnnotationProcessModelReader
 */
public enum ViolationKind {

	/** Un identificador BPMN se declara dos veces. */
	DUPLICATE_ID,

	/** Un identificador no es un NCName XML. */
	INVALID_ID,

	/** Falta el identificador o el nombre de un elemento. */
	UNIDENTIFIED_ELEMENT,

	/** Una declaración admite varias interpretaciones (p. ej. dos anotaciones de nodo en un método). */
	AMBIGUOUS_REFERENCE,

	/** Hay metadatos de proceso fuera de una clase {@code @Process} o {@code @ProcessPart}. */
	MISSING_PROCESS_ANNOTATION,

	/** Un {@code processRef} no corresponde a ningún proceso o versión. */
	UNKNOWN_PROCESS_REF,

	/** Un {@code targetRef} no corresponde a ningún nodo del proceso. */
	UNKNOWN_TARGET_REF,

	/** Un {@code businessRuleRef} no corresponde a ninguna regla o versión. */
	UNKNOWN_BUSINESS_RULE_REF,

	/** Un {@code messageRef} no corresponde a ningún mensaje, o nadie publica el mensaje. */
	UNKNOWN_MESSAGE_REF,

	/** Un {@code errorRef} no corresponde a ningún error declarado con {@code @ProcessError}. */
	UNKNOWN_ERROR_REF,

	/** Un {@code resourceRef} no corresponde a ningún recurso declarado con {@code @ProcessResource}. */
	UNKNOWN_RESOURCE_REF,

	/** Un error está mal declarado: {@code @ProcessError} en una clase que no es una excepción o sin código. */
	INVALID_ERROR,

	/** El proceso no declara su evento de inicio. */
	MISSING_START_EVENT,

	/** El proceso no declara ningún evento de fin. */
	MISSING_END_EVENT,

	/** Un nodo no es alcanzable desde el evento de inicio. */
	UNREACHABLE_FLOW_NODE,

	/** Desde un nodo no se puede llegar a ningún evento de fin: un ciclo sin salida. */
	NO_PATH_TO_END_EVENT,

	/** Los metadatos de un elemento son incompatibles entre sí o con su tipo de elemento. */
	INCOMPATIBLE_METADATA,

	/** Los flujos de salida de un gateway no coinciden con las constantes del enum que devuelve su método. */
	GATEWAY_OUTGOING_MISMATCH,

	/** Las versiones de un mensaje están mal declaradas (no son records, falta la versión, se repite…). */
	INVALID_MESSAGE_VERSION,

	/** Un proceso publica una versión de un mensaje que algún proceso que lo consume no acepta. */
	MESSAGE_VERSION_NOT_ACCEPTED,

	/** El esquema de una versión de mensaje no coincide con la huella congelada en {@code pac/message-contracts.lock}. */
	MESSAGE_CONTRACT_CHANGED,

	/** Una versión de mensaje no está congelada en {@code pac/message-contracts.lock}. */
	MESSAGE_CONTRACT_NOT_LOCKED,

	/** {@code pac/message-contracts.lock} congela una versión de mensaje que ya no existe. */
	MESSAGE_CONTRACT_REMOVED
}
