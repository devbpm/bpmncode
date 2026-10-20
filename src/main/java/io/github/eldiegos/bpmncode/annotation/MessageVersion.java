package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara una versión de un {@link Message}: el record que la anotación lleva es el contrato exacto de esa versión.
 * <p>
 * Responsabilidad: numerar las versiones de un mensaje para que el publicador elija una concreta, el consumidor diga
 * cuáles acepta y cada una quede congelada en {@code pac/message-contracts.lock}.
 * <p>
 * Invariantes: solo se usa en records que implementan la {@code sealed interface} anotada con {@link Message} (o en el
 * propio record, si el mensaje tiene una única versión); cada número aparece una vez por mensaje y es mayor que cero;
 * una versión publicada no se modifica nunca: si cambia el contrato, se añade otra.
 *
 * @see Message
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MessageVersion {

	/**
	 * Número de versión del mensaje.
	 *
	 * @return la versión, mayor que cero
	 */
	int value();
}
