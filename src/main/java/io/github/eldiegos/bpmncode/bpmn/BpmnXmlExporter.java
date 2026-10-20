package io.github.eldiegos.bpmncode.bpmn;

import java.util.Optional;

import io.github.eldiegos.bpmncode.i18n.LabelResolver;
import io.github.eldiegos.bpmncode.model.Definitions;

/**
 * Exportador de documentos BPMN 2.0 XML a partir del modelo de procesos.
 * <p>
 * Responsabilidad: coordinar el cálculo del alcance del diagrama y la escritura del modelo semántico y del intercambio
 * de diagramas (BPMN DI); identifica el documento con {@code exporter} y {@code exporterVersion}.
 * <p>
 * Invariantes: la salida es función pura del modelo, el diagrama, los textos y el espacio de nombres: mismo código y
 * mismo idioma, mismo XML.
 *
 * @see SemanticModelWriter
 * @see DiagramInterchangeWriter
 */
public final class BpmnXmlExporter {

	/** Espacio de nombres de destino por defecto de los documentos exportados. */
	public static final String DEFAULT_TARGET_NAMESPACE = "https://pac.dev/process";

	/** Nombre del exportador (atributo {@code exporter} de {@code bpmn:definitions}). */
	public static final String EXPORTER = "bpmncode";

	private BpmnXmlExporter() {
	}

	/**
	 * Exporta el BPMN de un diagrama con los textos canónicos de las anotaciones.
	 *
	 * @param definitions modelo completo
	 * @param diagram diagrama que se dibuja
	 * @return el documento BPMN 2.0
	 */
	public static String export(Definitions definitions, BpmnDiagram diagram) {
		return export(definitions, diagram, LabelResolver.CANONICAL);
	}

	/**
	 * Exporta el BPMN de un diagrama con los textos de un idioma y el espacio de nombres por defecto.
	 *
	 * @param definitions modelo completo
	 * @param diagram diagrama que se dibuja
	 * @param labels traductor de los textos visibles
	 * @return el documento BPMN 2.0
	 */
	public static String export(Definitions definitions, BpmnDiagram diagram, LabelResolver labels) {
		return export(definitions, diagram, labels, DEFAULT_TARGET_NAMESPACE);
	}

	/**
	 * Exporta el BPMN de un diagrama.
	 *
	 * @param definitions modelo completo
	 * @param diagram diagrama que se dibuja
	 * @param labels traductor de los textos visibles
	 * @param targetNamespace espacio de nombres de destino (atributo {@code targetNamespace}), sin barra final
	 * @return el documento BPMN 2.0
	 * @throws IllegalArgumentException si el diagrama nombra un proceso inexistente
	 */
	public static String export(Definitions definitions, BpmnDiagram diagram, LabelResolver labels,
			String targetNamespace) {
		var scope = DiagramScope.of(definitions, diagram, labels);
		var document = BpmnDocument.create(diagram.id(), targetNamespace, EXPORTER, exporterVersion());
		new SemanticModelWriter(document, scope).write();
		new DiagramInterchangeWriter(document, scope).write();
		return document.serialize();
	}

	/**
	 * Versión del exportador: la del jar del framework ({@code Implementation-Version}) o {@code development} si se
	 * ejecuta desde las clases compiladas.
	 *
	 * @return la versión
	 */
	static String exporterVersion() {
		return Optional.ofNullable(BpmnXmlExporter.class.getPackage().getImplementationVersion()).orElse("development");
	}
}
