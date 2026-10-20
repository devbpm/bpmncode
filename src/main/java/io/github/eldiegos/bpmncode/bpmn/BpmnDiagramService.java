package io.github.eldiegos.bpmncode.bpmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import io.github.eldiegos.bpmncode.i18n.LabelResolver;
import io.github.eldiegos.bpmncode.model.ProcessDefinition;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRef;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;

/**
 * Diagramas BPMN de la aplicación.
 * <p>
 * Responsabilidad: ofrecer los diagramas disponibles (uno por proceso en su versión vigente, uno por cada versión
 * anterior que siga desplegada y el de colaboración si hay mensajes) y su BPMN en un idioma, exportándolo una sola vez
 * por diagrama e idioma.
 * <p>
 * Invariantes: el registro es inmutable en ejecución, así que un diagrama exportado no cambia hasta reiniciar.
 *
 * @see BpmnXmlExporter
 */
public class BpmnDiagramService {

	private final ProcessDefinitionRegistry registry;
	private final String targetNamespace;
	private final Map<String, String> exported = new ConcurrentHashMap<>();

	BpmnDiagramService(ProcessDefinitionRegistry registry, String targetNamespace) {
		this.registry = registry;
		this.targetNamespace = targetNamespace;
	}

	/**
	 * Devuelve los diagramas disponibles.
	 *
	 * @return un diagrama por proceso y, si hay mensajes entre procesos, el de colaboración al final
	 */
	public List<BpmnDiagram> diagrams() {
		var definitions = registry.definitions();
		var diagrams = new ArrayList<BpmnDiagram>();
		definitions.processes().stream().map(ProcessDefinition::id).distinct().forEach(processId -> {
			diagrams.add(new BpmnDiagram.ProcessDiagram(processId));
			definitions.versions(processId).stream().map(ProcessDefinition::ref).filter(ref -> !definitions.isCurrent(ref))
					.forEach(ref -> diagrams.add(new BpmnDiagram.ProcessVersionDiagram(ref)));
		});
		var collaborating = new TreeSet<String>();
		definitions.messageFlows().forEach(flow -> {
			collaborating.add(flow.sourceProcessId());
			collaborating.add(flow.targetProcessId());
		});
		if (collaborating.size() > 1) {
			diagrams.add(new BpmnDiagram.CollaborationDiagram(List.copyOf(collaborating)));
		}
		return diagrams;
	}

	/**
	 * Busca un diagrama.
	 *
	 * @param diagramId identificador del diagrama
	 * @return el diagrama, o vacío si no existe
	 */
	public Optional<BpmnDiagram> diagram(String diagramId) {
		return diagrams().stream().filter(diagram -> diagram.id().equals(diagramId)).findFirst();
	}

	/**
	 * Devuelve el diagrama que muestra una versión concreta de un proceso: el del proceso si es la vigente o el de esa
	 * versión anterior si no lo es.
	 *
	 * @param ref proceso y versión
	 * @return el diagrama, o vacío si esa versión no está desplegada
	 */
	public Optional<BpmnDiagram> diagramOf(ProcessDefinitionRef ref) {
		var definitions = registry.definitions();
		if (definitions.process(ref).isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(definitions.isCurrent(ref) ? new BpmnDiagram.ProcessDiagram(ref.processId())
				: new BpmnDiagram.ProcessVersionDiagram(ref));
	}

	/**
	 * Devuelve los elementos del BPMN de un diagrama con el modelo del que proceden: participantes, nodos de flujo,
	 * flujos de secuencia y flujos de mensaje, cada uno con la versión exacta del proceso que muestra el diagrama.
	 *
	 * @param diagram diagrama
	 * @return un elemento por identificador del BPMN que el visor puede seleccionar
	 */
	public List<DiagramElement> elements(BpmnDiagram diagram) {
		var scope = DiagramScope.of(registry.definitions(), diagram, LabelResolver.CANONICAL);
		var elements = new ArrayList<DiagramElement>();
		scope.pools().forEach(processId -> elements.add(
				new DiagramElement.Participant(DiagramScope.participantId(processId), scope.shown(processId))));
		scope.processes().forEach(process -> {
			process.flowNodes().forEach(node -> elements.add(new DiagramElement.FlowNode(process, node)));
			process.sequenceFlows().forEach(flow -> elements.add(new DiagramElement.SequenceFlow(process, flow)));
		});
		scope.messageFlows().forEach(flow -> elements.add(new DiagramElement.MessageFlow(flow,
				scope.shown(flow.sourceProcessId()), scope.shown(flow.targetProcessId()))));
		return List.copyOf(elements);
	}

	/**
	 * Devuelve el BPMN de un diagrama en un idioma.
	 *
	 * @param diagram diagrama
	 * @param language identificador del idioma de {@code labels}; forma parte de la clave de caché
	 * @param labels traductor de los textos visibles
	 * @return el documento BPMN 2.0
	 */
	public String xml(BpmnDiagram diagram, String language, LabelResolver labels) {
		return exported.computeIfAbsent(diagram.id() + "|" + language,
				key -> BpmnXmlExporter.export(registry.definitions(), diagram, labels, targetNamespace));
	}
}
