package io.github.eldiegos.bpmncode.model;

import io.github.eldiegos.bpmncode.i18n.LabelResolver;

/**
 * Tipo de elemento BPMN 2.0 de un nodo de proceso.
 * <p>
 * Responsabilidad: dar a cada variante de {@link FlowNode} su elemento BPMN ({@code bpmn:serviceTask}…) y una clave
 * estable para traducir su nombre ({@code pac.bpmn.<localName>}).
 * <p>
 * Invariantes: cada constante corresponde a exactamente un elemento BPMN y a una variante de {@link FlowNode}.
 *
 * @see FlowNode#elementType()
 * @see LabelResolver#elementType(BpmnElementType)
 */
public enum BpmnElementType {

	START_EVENT("startEvent"),
	END_EVENT("endEvent"),
	SERVICE_TASK("serviceTask"),
	BUSINESS_RULE_TASK("businessRuleTask"),
	USER_TASK("userTask"),
	SEND_TASK("sendTask"),
	EXCLUSIVE_GATEWAY("exclusiveGateway");

	private final String localName;

	BpmnElementType(String localName) {
		this.localName = localName;
	}

	/**
	 * Devuelve el nombre local del elemento BPMN 2.0.
	 *
	 * @return p. ej. {@code serviceTask}
	 */
	public String localName() {
		return localName;
	}
}
