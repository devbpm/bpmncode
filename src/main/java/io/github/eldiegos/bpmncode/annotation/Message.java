package io.github.eldiegos.bpmncode.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara un mensaje BPMN ({@code bpmn:message}) entre procesos: el contrato con el que un proceso inicia otro.
 * <p>
 * Responsabilidad: dar identidad estable al mensaje que publica un proceso (al terminar o con una tarea de envío) y
 * consume otro al empezar. Se anota la {@code sealed interface} de la familia de versiones; cada versión es un record
 * que la implementa y lleva {@link MessageVersion}. Un mensaje de una sola versión puede anotarse directamente en su
 * record.
 * <p>
 * Invariantes:
 * <ul>
 * <li>cada identificador de mensaje aparece una sola vez y todo mensaje que inicia un proceso lo publica algún
 * proceso;</li>
 * <li>cada versión es un record que implementa {@code io.github.eldiegos.bpmncode.runtime.ProcessMessage}, es decir, lleva su
 * {@code MessageHeader} (identificador del mensaje, correlación, momento y origen);</li>
 * <li>un {@link MessageEndEvent} o una {@link SendTask} publica una versión concreta; un {@link MessageStartEvent}
 * acepta la familia (todas las versiones) o una versión, y debe aceptar toda versión que le publiquen;</li>
 * <li>si existe {@code pac/message-contracts.lock}, cada versión está congelada en él con la huella de su esquema y no
 * puede cambiar ni desaparecer.</li>
 * </ul>
 *
 * @see MessageVersion
 * @see MessageStartEvent#messageRef()
 * @see MessageEndEvent#messageRef()
 * @see SendTask#messageRef()
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Message {

	/**
	 * Identificador estable del mensaje (atributo {@code id}), p. ej. {@code EV-PERSON-REGISTERED}.
	 *
	 * @return un NCName XML único en la aplicación
	 */
	String id();

	/**
	 * Nombre canónico del mensaje (atributo {@code name}).
	 *
	 * @return el nombre en inglés
	 */
	String name();

	/**
	 * Texto de negocio del mensaje (elemento {@code bpmn:documentation}).
	 *
	 * @return la documentación, posiblemente vacía
	 */
	String documentation() default "";
}
