package io.github.eldiegos.bpmncode.bpmn;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de configuración de la exportación BPMN ({@code pac.process.bpmn.*}).
 * <p>
 * Responsabilidad: permitir que cada aplicación use su propio espacio de nombres en los documentos BPMN exportados.
 * <p>
 * Invariantes: el espacio de nombres nunca está vacío y no termina en barra.
 *
 * @param targetNamespace espacio de nombres de destino de los documentos ({@code pac.process.bpmn.target-namespace})
 * @see BpmnXmlExporter#DEFAULT_TARGET_NAMESPACE
 */
@ConfigurationProperties("pac.process.bpmn")
public record BpmnProperties(@DefaultValue(BpmnXmlExporter.DEFAULT_TARGET_NAMESPACE) String targetNamespace) {

	public BpmnProperties {
		if (targetNamespace == null || targetNamespace.isBlank()) {
			throw new IllegalArgumentException("pac.process.bpmn.target-namespace must not be blank");
		}
		targetNamespace = targetNamespace.endsWith("/") ? targetNamespace.substring(0, targetNamespace.length() - 1)
				: targetNamespace;
	}
}
