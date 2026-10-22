package io.github.eldiegos.bpmncode.bpmn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import io.github.eldiegos.bpmncode.model.BpmnElementType;
import io.github.eldiegos.bpmncode.i18n.LabelResolver;
import io.github.eldiegos.bpmncode.model.reader.AnnotationProcessModelReader;
import io.github.eldiegos.bpmncode.model.Definitions;
import io.github.eldiegos.bpmncode.model.SequenceFlow;
import fixture.process.ValidProcesses;
import fixture.process.VersionedMessages;

/**
 * Generación BPMN 2.0 del framework sobre procesos de prueba: válida según los XSD oficiales, estructuralmente igual a
 * las definiciones Java, determinista, traducible e independiente del orden de los métodos.
 */
class BpmnXmlExporterTests {

	static final String BPMN = BpmnDocument.BPMN_NS;
	static final String BPMNDI = BpmnDocument.BPMNDI_NS;
	static final String DI = BpmnDocument.DI_NS;

	private static final Definitions DEFINITIONS = AnnotationProcessModelReader.read(List.of(ValidProcesses.OrderV1.class,
			ValidProcesses.CreditRule.class, ValidProcesses.OrderAccepted.class, ValidProcesses.Shipping.class,
			ValidProcesses.ShippingStarter.class, ValidProcesses.InvoiceRequested.class, ValidProcesses.Subscription.class,
			ValidProcesses.Invoicing.class));

	@Test
	void singleProcessDiagramIsValidBpmnMatchingTheJavaDefinition() throws Exception {
		var xml = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessDiagram("FX-ORDER"));
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);
		var definition = DEFINITIONS.process("FX-ORDER").orElseThrow();

		var process = byId(document, "FX-ORDER");
		for (var node : definition.flowNodes()) {
			var element = byId(document, node.id());
			assertThat(element.getLocalName()).as(node.id()).isEqualTo(node.elementType().localName());
			assertThat(element.getParentNode()).isEqualTo(process);
			assertThat(element.getAttribute("name")).isEqualTo(node.name());
			assertThat(shapeFor(document, node.id())).as("BPMNShape of " + node.id()).isNotNull();
		}
		assertThat(elements(process, BPMN, "sequenceFlow")).hasSize(definition.sequenceFlows().size());
		for (var flow : definition.sequenceFlows()) {
			var element = byId(document, flow.id());
			assertThat(element.getAttribute("sourceRef")).isEqualTo(flow.sourceRef());
			assertThat(element.getAttribute("targetRef")).isEqualTo(flow.targetRef());
			assertThat(elements(element, BPMN, "conditionExpression"))
					.hasSize(flow instanceof SequenceFlow.Conditional ? 1 : 0);
			assertThat(elements(edgeFor(document, flow.id()), DI, "waypoint").size()).isGreaterThanOrEqualTo(2);
		}
		// El proceso que consume el mensaje aparece como piscina colapsada, no como un nodo ajeno.
		assertThat(elements(document.getDocumentElement(), BPMN, "process")).hasSize(1);
		assertThat(byId(document, "Participant_FX-SHIPPING").hasAttribute("processRef")).isFalse();
		assertThat(byId(document, "MessageFlow_FX-DONE_FX-SHIP-START").getAttribute("targetRef"))
				.isEqualTo("Participant_FX-SHIPPING");
	}

	@Test
	void collaborationLinksIndependentProcessesThroughTheMessage() throws Exception {
		var xml = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.CollaborationDiagram(List.of("FX-ORDER", "FX-SHIPPING",
				"FX-SUBSCRIPTION", "FX-INVOICING")));
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);
		var messageFlow = byId(document, "MessageFlow_FX-DONE_FX-SHIP-START");
		assertThat(messageFlow.getParentNode().getLocalName()).isEqualTo("collaboration");
		assertThat(messageFlow.getAttribute("sourceRef")).isEqualTo("FX-DONE");
		assertThat(messageFlow.getAttribute("targetRef")).isEqualTo("FX-SHIP-START");
		assertThat(messageFlow.getAttribute("messageRef")).isEqualTo("Message_FX-EV-ORDERED");
		assertThat(elements(byId(document, "FX-SHIP-START"), BPMN, "messageEventDefinition")).hasSize(1);
		assertThat(edgeFor(document, messageFlow.getAttribute("id"))).isNotNull();
		for (var kind : BpmnElementType.values()) {
			assertThat(document.getElementsByTagNameNS(BPMN, kind.localName()).getLength()).as(kind.name()).isPositive();
		}
	}

	@Test
	void aSendTaskReferencesItsMessageAndIsTheSourceOfTheMessageFlow() throws Exception {
		var xml = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.CollaborationDiagram(List.of("FX-SUBSCRIPTION",
				"FX-INVOICING")));
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);

		var sendTask = byId(document, "FX-REQUEST-INVOICE");
		assertThat(sendTask.getLocalName()).isEqualTo("sendTask");
		assertThat(sendTask.getAttribute("messageRef")).isEqualTo("Message_FX-EV-INVOICE-REQUESTED");
		assertThat(sendTask.getAttributeNS(BpmnDocument.PAC_NS, "version")).isEqualTo("1");
		assertThat(elements(sendTask, BPMN, "messageEventDefinition")).isEmpty();
		assertThat(byId(document, "Message_FX-EV-INVOICE-REQUESTED").getLocalName()).isEqualTo("message");
		var messageFlow = byId(document, "MessageFlow_FX-REQUEST-INVOICE_FX-INV-START");
		assertThat(messageFlow.getAttribute("sourceRef")).isEqualTo("FX-REQUEST-INVOICE");
		assertThat(messageFlow.getAttribute("targetRef")).isEqualTo("FX-INV-START");
		assertThat(edgeFor(document, messageFlow.getAttribute("id"))).isNotNull();
		assertThat(shapeFor(document, "FX-REQUEST-INVOICE")).isNotNull();
	}

	@Test
	void theRolesOfAUserTaskAreStandardResourceRoles() throws Exception {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.Reviewer.class,
				ValidProcesses.Review.class));
		var xml = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("FX-REVIEW"));
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);

		var resource = byId(document, "Resource_FX-RES-REVIEWER");
		assertThat(resource.getLocalName()).isEqualTo("resource");
		assertThat(resource.getAttribute("name")).isEqualTo("Reviewer");
		assertThat(elements(resource, BPMN, "resourceParameter"))
				.extracting(parameter -> parameter.getAttribute("name"), parameter -> parameter.getAttribute("isRequired"))
				.containsExactly(org.assertj.core.groups.Tuple.tuple("department", "true"),
						org.assertj.core.groups.Tuple.tuple("language", "false"));
		var task = byId(document, "FX-REV-REVIEW");
		var owners = elements(task, BPMN, "potentialOwner");
		assertThat(owners).hasSize(2);
		assertThat(elements(owners.get(0), BPMN, "resourceRef")).singleElement()
				.satisfies(ref -> assertThat(ref.getTextContent()).isEqualTo("Resource_FX-RES-REVIEWER"));
		assertThat(owners.get(1).getElementsByTagNameNS(BPMN, "formalExpression").item(0).getTextContent())
				.isEqualTo("team lead of the author");
		assertThat(elements(task, BPMN, "humanPerformer")).singleElement().satisfies(performer -> assertThat(
				performer.getElementsByTagNameNS(BPMN, "formalExpression").item(0).getTextContent())
				.isEqualTo("the reviewer who claimed the task"));
	}

	@Test
	void anErrorEndEventReferencesTheErrorWithItsCodeAndException() throws Exception {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.CreditRejected.class,
				ValidProcesses.Loan.class));
		var xml = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("FX-LOAN"));
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);

		var error = byId(document, "Error_FX-ERR-CREDIT");
		assertThat(error.getLocalName()).isEqualTo("error");
		assertThat(error.getAttribute("errorCode")).isEqualTo("CREDIT_REJECTED");
		assertThat(byId(document, error.getAttribute("structureRef")).getAttribute("structureRef"))
				.endsWith("ValidProcesses.CreditRejected");
		assertThat(elements(byId(document, "FX-LOAN-REJECTED"), BPMN, "errorEventDefinition")).singleElement()
				.satisfies(definition -> assertThat(definition.getAttribute("errorRef")).isEqualTo("Error_FX-ERR-CREDIT"));
		assertThat(elements(byId(document, "FX-LOAN-GRANTED"), BPMN, "errorEventDefinition")).isEmpty();
	}

	@Test
	void messageVersionsAreExportedWithTheirSchemaHash() throws Exception {
		var definitions = AnnotationProcessModelReader.read(List.of(VersionedMessages.PaymentReceived.class,
				VersionedMessages.PaymentReceivedV1.class, VersionedMessages.PaymentReceivedV2.class,
				VersionedMessages.BillingV1.class, VersionedMessages.BillingV2.class, VersionedMessages.Fulfilment.class));
		var xml = BpmnXmlExporter.export(definitions, new BpmnDiagram.CollaborationDiagram(List.of("FX-BILLING",
				"FX-FULFILMENT")));
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);

		var versions = elements(byId(document, "Message_FX-EV-PAID"), BpmnDocument.PAC_NS, "messageVersion");
		assertThat(versions).extracting(version -> version.getAttribute("version")).containsExactly("1", "2");
		assertThat(versions).extracting(version -> version.getAttribute("schemaHash"))
				.containsExactly(definitions.message("FX-EV-PAID").orElseThrow().versions().stream()
						.map(version -> version.schema().fingerprint()).toArray(String[]::new));
		assertThat(byId(document, "ItemDefinition_FX-EV-PAID_v2").getAttribute("structureRef"))
				.isEqualTo(VersionedMessages.PaymentReceivedV2.class.getCanonicalName());
		// Se muestra la versión vigente del cobro (v2), que publica la versión 2 del mensaje.
		assertThat(byId(document, "MessageFlow_FX-PAID_FX-FUL-START").getAttributeNS(BpmnDocument.PAC_NS, "version")).isEqualTo("2");
		assertThat(elements(byId(document, "FX-PAID"), BPMN, "messageEventDefinition").getFirst()
				.getAttributeNS(BpmnDocument.PAC_NS, "version")).isEqualTo("2");
		assertThat(elements(byId(document, "FX-FUL-START"), BPMN, "messageEventDefinition").getFirst()
				.getAttributeNS(BpmnDocument.PAC_NS, "acceptedVersions")).isEqualTo("1 2");
	}

	@Test
	void aStandardLoopAndACycleAreExportedAsValidBpmn() throws Exception {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.Documents.class));
		var spanish = Map.of("pac.flowNode.DOC-UPLOAD.loopCondition", "Algún documento no se lee");
		LabelResolver labels = (key, fallback, arguments) -> spanish.getOrDefault(key, fallback);
		var xml = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("FX-DOCS"), labels);
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);

		var loop = (Element) byId(document, "DOC-UPLOAD").getElementsByTagNameNS(BpmnDocument.BPMN_NS,
				"standardLoopCharacteristics").item(0);
		assertThat(loop.getAttribute("testBefore")).isEqualTo("false");
		assertThat(loop.getAttribute("loopMaximum")).isEqualTo("3");
		assertThat(loop.getTextContent().strip()).isEqualTo("Algún documento no se lee");
		assertThat(byId(document, "Flow_DOC-COMPLETE_DOC-UPLOAD").getAttribute("targetRef")).isEqualTo("DOC-UPLOAD");
		assertThat(byId(document, "Flow_DOC-COMPLETE_DOC-UPLOAD").getAttributeNS(BpmnDocument.PAC_NS, "when"))
				.isEqualTo("INCOMPLETE");
	}

	@Test
	void timerStartEventsAreExportedAsValidBpmnTimerDefinitions() throws Exception {
		var definitions = AnnotationProcessModelReader.read(List.of(ValidProcesses.NightlyCleanup.class,
				ValidProcesses.Polling.class));

		var nightly = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("FX-NIGHTLY"));
		validateAgainstBpmnSchema(nightly);
		var cron = (Element) byId(parse(nightly), "NIG-START").getElementsByTagNameNS(BpmnDocument.BPMN_NS,
				"timerEventDefinition").item(0);
		assertThat(cron.getAttributeNS(BpmnDocument.PAC_NS, "schedule")).isEqualTo("cron");
		assertThat(cron.getAttributeNS(BpmnDocument.PAC_NS, "zone")).isEqualTo("Europe/Madrid");
		assertThat(cron.getTextContent().strip()).isEqualTo("0 0 2 * * *");

		var polling = BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("FX-POLLING"));
		validateAgainstBpmnSchema(polling);
		var rate = (Element) byId(parse(polling), "POL-START").getElementsByTagNameNS(BpmnDocument.BPMN_NS,
				"timerEventDefinition").item(0);
		assertThat(rate.getAttributeNS(BpmnDocument.PAC_NS, "schedule")).isEqualTo("fixedRate");
		assertThat(rate.getAttributeNS(BpmnDocument.PAC_NS, "initialDelay")).isEqualTo("PT30S");
		assertThat(rate.getTextContent().strip()).isEqualTo("R/PT5M");
	}

	@Test
	void generationIsDeterministic() {
		var view = new BpmnDiagram.CollaborationDiagram(List.of("FX-ORDER", "FX-SHIPPING"));
		assertThat(BpmnXmlExporter.export(DEFINITIONS, view)).isEqualTo(BpmnXmlExporter.export(DEFINITIONS, view));
	}

	@Test
	void physicalOrderOfAnnotatedMethodsDoesNotChangeTheDiagram() {
		var original = generateOrder(ValidProcesses.OrderV1.class);
		var reordered = generateOrder(ValidProcesses.OrderV1Reordered.class);
		// Solo puede cambiar la extensión pac:implementation, que nombra la clase y los métodos Java.
		assertThat(withoutImplementation(reordered)).isEqualTo(withoutImplementation(original));
	}

	@Test
	void changingTheAnnotatedMetadataChangesTheGeneratedBpmn() throws Exception {
		var v1 = parse(generateOrder(ValidProcesses.OrderV1.class));
		var v2Xml = generateOrder(ValidProcesses.OrderV2.class);
		validateAgainstBpmnSchema(v2Xml);
		var v2 = parse(v2Xml);

		assertThat(v1.getElementById("FX-NOTIFY")).isNull();
		assertThat(byId(v1, "Flow_FX-CREATE_FX-DONE").getAttribute("targetRef")).isEqualTo("FX-DONE");
		assertThat(byId(v2, "FX-NOTIFY").getLocalName()).isEqualTo("serviceTask");
		assertThat(byId(v2, "Flow_FX-CREATE_FX-NOTIFY").getAttribute("targetRef")).isEqualTo("FX-NOTIFY");
		assertThat(byId(v2, "Flow_FX-NOTIFY_FX-DONE").getAttribute("targetRef")).isEqualTo("FX-DONE");
		assertThat(v2.getElementById("Flow_FX-CREATE_FX-DONE")).isNull();
		assertThat(byId(v2, "Participant_FX-ORDER").getAttributeNS(BpmnDocument.PAC_NS, "version")).isEqualTo("2");
		assertThat(byId(v2, "FX-ORDER").getAttributeNS(BpmnDocument.PAC_NS, "version")).isEqualTo("2");
	}

	@Test
	void visibleTextsComeFromTheLabelResolverWhileIdsStayStable() throws Exception {
		var spanish = Map.of("pac.process.FX-ORDER.name", "Pedido", "pac.flowNode.FX-CHECK.name", "Comprobar crédito",
				"pac.sequenceFlow.Flow_FX-DECIDE_FX-CREATE.name", "Sí", "pac.businessRule.FX-R-001.name", "Límite de crédito");
		LabelResolver labels = (key, fallback, arguments) -> spanish.getOrDefault(key, fallback);
		var xml = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessDiagram("FX-ORDER"), labels);
		validateAgainstBpmnSchema(xml);
		var document = parse(xml);
		assertThat(byId(document, "Participant_FX-ORDER").getAttribute("name")).isEqualTo("Pedido");
		assertThat(byId(document, "FX-CHECK").getAttribute("name")).isEqualTo("Comprobar crédito");
		assertThat(((Element) byId(document, "FX-CHECK").getElementsByTagNameNS(BpmnDocument.PAC_NS, "businessRuleRef").item(0))
				.getAttribute("name")).isEqualTo("Límite de crédito");
		assertThat(byId(document, "Flow_FX-DECIDE_FX-CREATE").getAttribute("name")).isEqualTo("Sí");
		// Sin traducción se usa el nombre canónico de la anotación.
		assertThat(byId(document, "FX-CREATE").getAttribute("name")).isEqualTo("Create order");
	}

	@Test
	void schemaValidationRejectsStructurallyInvalidBpmn() {
		var xml = BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessDiagram("FX-ORDER"));
		var withoutNamespace = xml.replaceFirst(" targetNamespace=\"[^\"]*\"", "");
		var taskInCollaboration = xml.replaceFirst("</bpmn:collaboration>", "<bpmn:serviceTask id=\"X\"/></bpmn:collaboration>");
		assertThatThrownBy(() -> validateAgainstBpmnSchema(withoutNamespace)).isInstanceOf(SAXParseException.class);
		assertThatThrownBy(() -> validateAgainstBpmnSchema(taskInCollaboration)).isInstanceOf(SAXParseException.class);
	}

	@Test
	void unknownProcessesCannotBeDrawn() {
		assertThatThrownBy(() -> BpmnXmlExporter.export(DEFINITIONS, new BpmnDiagram.ProcessDiagram("FX-UNKNOWN")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void thereAreNoHandWrittenBpmnFiles() throws IOException {
		// Recorre las fuentes de todos los módulos Maven (el directorio de trabajo es el del módulo).
		try (Stream<Path> files = Files.walk(Path.of(".."))) {
			assertThat(files.map(Path::toString)
					.filter(name -> name.contains("src"))
					.filter(name -> name.endsWith(".bpmn") || name.endsWith(".bpmn20.xml")))
					.isEmpty();
		}
	}

	// --- utilidades -------------------------------------------------------------------------------------------------

	private static String generateOrder(Class<?> orderProcess) {
		var definitions = AnnotationProcessModelReader.read(List.of(orderProcess, ValidProcesses.CreditRule.class,
				ValidProcesses.OrderAccepted.class));
		return BpmnXmlExporter.export(definitions, new BpmnDiagram.ProcessDiagram("FX-ORDER"));
	}

	private static String withoutImplementation(String xml) {
		return xml.replaceAll("<pac:(implementation|definedBy|implementedBy) [^>]*/>", "");
	}

	/** Valida contra los XSD oficiales de OMG BPMN 2.0 (en {@code src/test/resources/bpmn/xsd}). */
	public static void validateAgainstBpmnSchema(String xml) throws Exception {
		var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
		var schema = schemaFactory.newSchema(BpmnXmlExporterTests.class.getResource("/bpmn/xsd/BPMN20.xsd"));
		schema.newValidator().validate(new StreamSource(new StringReader(xml)));
	}

	static Document parse(String xml) throws Exception {
		var factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
		// Registra los atributos "id" para que getElementById funcione sin DTD.
		var all = document.getElementsByTagName("*");
		for (int i = 0; i < all.getLength(); i++) {
			var element = (Element) all.item(i);
			if (element.hasAttribute("id")) {
				element.setIdAttribute("id", true);
			}
		}
		return document;
	}

	static Element byId(Document document, String id) {
		var element = document.getElementById(id);
		assertThat(element).as("element with id " + id).isNotNull();
		return element;
	}

	static Element shapeFor(Document document, String elementId) {
		return elements(document.getDocumentElement(), BPMNDI, "BPMNShape").stream()
				.filter(shape -> shape.getAttribute("bpmnElement").equals(elementId)).findFirst().orElse(null);
	}

	static Element edgeFor(Document document, String elementId) {
		return elements(document.getDocumentElement(), BPMNDI, "BPMNEdge").stream()
				.filter(edge -> edge.getAttribute("bpmnElement").equals(elementId)).findFirst().orElse(null);
	}

	static List<Element> elements(Element parent, String namespace, String localName) {
		var nodes = parent.getElementsByTagNameNS(namespace, localName);
		var result = new ArrayList<Element>();
		for (int i = 0; i < nodes.getLength(); i++) {
			result.add((Element) nodes.item(i));
		}
		return result;
	}
}
