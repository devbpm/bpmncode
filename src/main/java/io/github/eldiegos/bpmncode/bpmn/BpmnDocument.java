package io.github.eldiegos.bpmncode.bpmn;

import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Documento XML BPMN 2.0 en construcción.
 * <p>
 * Responsabilidad: crear elementos en los espacios de nombres BPMN, BPMNDI, DC, DI y de la extensión {@code pac:} y
 * serializar el documento de forma determinista e indentada.
 * <p>
 * Invariantes: el elemento raíz es {@code bpmn:definitions} y declara todos los espacios de nombres; la documentación
 * es siempre el primer hijo de su elemento y {@code bpmn:extensionElements}, el siguiente, como exige el esquema.
 *
 * @see BpmnXmlExporter
 */
final class BpmnDocument {

	static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
	static final String BPMNDI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";
	static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";
	static final String DI_NS = "http://www.omg.org/spec/DD/20100524/DI";

	/** Espacio de nombres de las extensiones del framework: lo que BPMN no define (versión, código, reglas, rama). */
	static final String PAC_NS = "https://pac.dev/schema/bpmn";

	private final Document document;
	private final Element definitions;

	private BpmnDocument(Document document, Element definitions) {
		this.document = document;
		this.definitions = definitions;
	}

	/**
	 * Crea un documento vacío con su elemento {@code definitions}.
	 *
	 * @param diagramId identificador del diagrama, que forma parte de los ids raíz
	 * @param targetNamespace espacio de nombres de destino, sin barra final
	 * @param exporter nombre del exportador
	 * @param exporterVersion versión del exportador
	 * @return el documento
	 */
	static BpmnDocument create(String diagramId, String targetNamespace, String exporter, String exporterVersion) {
		try {
			var factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(true);
			var document = factory.newDocumentBuilder().newDocument();
			document.setXmlStandalone(true);
			var definitions = document.createElementNS(BPMN_NS, "bpmn:definitions");
			document.appendChild(definitions);
			definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:bpmn", BPMN_NS);
			definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:bpmndi", BPMNDI_NS);
			definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:dc", DC_NS);
			definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:di", DI_NS);
			definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:pac", PAC_NS);
			definitions.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi",
					XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
			definitions.setAttribute("id", "Definitions_" + diagramId);
			definitions.setAttribute("targetNamespace", targetNamespace + "/" + diagramId);
			definitions.setAttribute("exporter", exporter);
			definitions.setAttribute("exporterVersion", exporterVersion);
			return new BpmnDocument(document, definitions);
		}
		catch (ParserConfigurationException e) {
			throw new IllegalStateException("Cannot create a BPMN document", e);
		}
	}

	/** Elemento raíz {@code bpmn:definitions}. */
	Element definitionsElement() {
		return definitions;
	}

	Element bpmn(String localName) {
		return document.createElementNS(BPMN_NS, "bpmn:" + localName);
	}

	Element bpmndi(String localName) {
		return document.createElementNS(BPMNDI_NS, "bpmndi:" + localName);
	}

	Element dc(String localName) {
		return document.createElementNS(DC_NS, "dc:" + localName);
	}

	Element di(String localName) {
		return document.createElementNS(DI_NS, "di:" + localName);
	}

	/** Añade a {@code parent} un hijo BPMN con texto. */
	void appendText(Element parent, String localName, String text) {
		var child = bpmn(localName);
		child.setTextContent(text);
		parent.appendChild(child);
	}

	/** Pone la documentación como primer hijo de {@code parent}; no hace nada si el texto está vacío. */
	void document(Element parent, String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		var documentation = bpmn("documentation");
		documentation.setTextContent(text);
		parent.insertBefore(documentation, parent.getFirstChild());
	}

	/** Pone un atributo de la extensión {@code pac:}, permitido en cualquier elemento BPMN. */
	void setExtensionAttribute(Element element, String localName, String value) {
		element.setAttributeNS(PAC_NS, "pac:" + localName, value);
	}

	/**
	 * Añade un elemento de la extensión {@code pac:} dentro de {@code bpmn:extensionElements}, que se crea, si no existe,
	 * justo después de la documentación.
	 *
	 * @param parent elemento BPMN
	 * @param localName nombre del elemento de extensión, p. ej. {@code implementation}
	 * @return el elemento de extensión, para ponerle atributos
	 */
	Element appendExtension(Element parent, String localName) {
		var extensions = extensionElements(parent);
		var extension = document.createElementNS(PAC_NS, "pac:" + localName);
		extensions.appendChild(extension);
		return extension;
	}

	private Element extensionElements(Element parent) {
		for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child instanceof Element element && BPMN_NS.equals(element.getNamespaceURI())
					&& "extensionElements".equals(element.getLocalName())) {
				return element;
			}
		}
		var extensions = bpmn("extensionElements");
		var first = parent.getFirstChild();
		var afterDocumentation = first instanceof Element element && "documentation".equals(element.getLocalName())
				? first.getNextSibling() : first;
		parent.insertBefore(extensions, afterDocumentation);
		return extensions;
	}

	/** Pone el atributo {@code xsi:type}. */
	void setXsiType(Element element, String type) {
		element.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", type);
	}

	/**
	 * Serializa el documento.
	 *
	 * @return el XML en UTF-8, indentado
	 */
	String serialize() {
		try {
			// Solo serializa un documento propio, pero sin acceso a DTD ni hojas de estilo externas (XXE).
			var factory = TransformerFactory.newInstance();
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
			factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
			var transformer = factory.newTransformer();
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			var writer = new StringWriter();
			transformer.transform(new DOMSource(document), new StreamResult(writer));
			return writer.toString();
		}
		catch (TransformerException e) {
			throw new IllegalStateException("Cannot serialize the BPMN document", e);
		}
	}
}
