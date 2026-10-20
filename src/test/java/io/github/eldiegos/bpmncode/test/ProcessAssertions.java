package io.github.eldiegos.bpmncode.test;

import java.io.StringReader;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import io.github.eldiegos.bpmncode.bpmn.BpmnDiagramService;
import io.github.eldiegos.bpmncode.bpmn.DiagramElement;
import io.github.eldiegos.bpmncode.i18n.LabelResolver;
import io.github.eldiegos.bpmncode.runtime.HistoricFlowNodeInstance;
import io.github.eldiegos.bpmncode.runtime.ProcessInstanceHistory;

/**
 * Comprobaciones listas para las pruebas de una aplicación que usa la librería.
 * <p>
 * Responsabilidad: que cada aplicación pueda probar en una línea que sus diagramas son BPMN 2.0 válido según los XSD
 * oficiales de OMG (incluidos en el jar de pruebas, en {@code /bpmn/xsd}), que cada elemento dibujado tiene su elemento
 * del modelo y qué camino siguió una instancia.
 * <p>
 * Invariantes: no depende de ninguna librería de aserciones: un fallo es un {@link AssertionError} con el detalle; se
 * publica en el jar de pruebas de la librería (clasificador {@code tests}), que la aplicación añade con
 * {@code <scope>test</scope>}.
 */
public final class ProcessAssertions {

	private static final Pattern DRAWN = Pattern.compile("bpmnElement=\"([^\"]+)\"");

	private ProcessAssertions() {
	}

	/**
	 * Comprueba que un documento es BPMN 2.0 válido.
	 *
	 * @param xml documento BPMN
	 * @throws AssertionError si no valida contra los XSD oficiales
	 */
	public static void assertValidBpmn(String xml) {
		try {
			schema().newValidator().validate(new StreamSource(new StringReader(xml)));
		}
		catch (Exception invalid) {
			throw new AssertionError("The BPMN document is not valid BPMN 2.0: " + invalid.getMessage(), invalid);
		}
	}

	/**
	 * Comprueba que todos los diagramas de la aplicación son BPMN 2.0 válido.
	 *
	 * @param diagrams servicio de diagramas
	 * @throws AssertionError con el primer diagrama que no valida
	 */
	public static void assertEveryDiagramIsValidBpmn(BpmnDiagramService diagrams) {
		for (var diagram : diagrams.diagrams()) {
			try {
				assertValidBpmn(diagrams.xml(diagram, "canonical", LabelResolver.CANONICAL));
			}
			catch (AssertionError invalid) {
				throw new AssertionError("Diagram " + diagram.id() + ": " + invalid.getMessage(), invalid);
			}
		}
	}

	/**
	 * Comprueba que cada elemento dibujado en cada diagrama tiene su elemento del modelo ({@link DiagramElement}), es
	 * decir, que una herramienta puede explicar todo lo que se ve.
	 *
	 * @param diagrams servicio de diagramas
	 * @throws AssertionError con los elementos sin explicar de un diagrama
	 */
	public static void assertEveryDrawnElementIsDescribed(BpmnDiagramService diagrams) {
		for (var diagram : diagrams.diagrams()) {
			var xml = diagrams.xml(diagram, "canonical", LabelResolver.CANONICAL);
			// El plano del diagrama apunta a la colaboración: es el propio lienzo, no un elemento.
			var drawn = DRAWN.matcher(xml).results().map(match -> match.group(1))
					.filter(id -> !id.startsWith("Collaboration_")).collect(Collectors.toSet());
			var described = diagrams.elements(diagram).stream().map(DiagramElement::elementId).collect(Collectors.toSet());
			drawn.removeAll(described);
			if (!drawn.isEmpty()) {
				throw new AssertionError("Diagram " + diagram.id() + " draws elements without a model element: " + drawn);
			}
		}
	}

	/**
	 * Camino seguido por una instancia: sus nodos de flujo en orden, para compararlo en una prueba.
	 *
	 * @param history historial de instancias
	 * @param instanceId instancia
	 * @return los identificadores de los nodos registrados, en orden (repetidos si un bucle o un ciclo los repitió)
	 */
	public static List<String> pathOf(ProcessInstanceHistory history, UUID instanceId) {
		return history.flowNodeInstances(instanceId).stream().map(HistoricFlowNodeInstance::flowNodeId).toList();
	}

	private static Schema schema() throws Exception {
		var location = ProcessAssertions.class.getResource("/bpmn/xsd/BPMN20.xsd");
		if (location == null) {
			throw new IllegalStateException("The BPMN 2.0 XSD is not on the classpath (bpmncode test jar)");
		}
		return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(location);
	}
}
