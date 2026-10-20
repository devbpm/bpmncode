package io.github.eldiegos.bpmncode.bpmn;

import java.util.List;
import java.util.Optional;

import org.w3c.dom.Element;

import io.github.eldiegos.bpmncode.model.FlowNode;

/**
 * Escritor del intercambio de diagramas BPMN (BPMNDI): figuras, aristas y coordenadas.
 * <p>
 * Responsabilidad: dar a cada participante, nodo, flujo de secuencia y flujo de mensaje su {@code BPMNShape} o
 * {@code BPMNEdge} con la disposición calculada.
 * <p>
 * Invariantes: todo elemento semántico dibujable tiene exactamente una figura o arista; la etiqueta de una decisión va
 * debajo de la figura, a la izquierda de la rama que baja, para que no se crucen y quede libre el espacio de encima.
 *
 * @see SemanticModelWriter
 */
final class DiagramInterchangeWriter {

	private final BpmnDocument document;
	private final DiagramScope scope;

	DiagramInterchangeWriter(BpmnDocument document, DiagramScope scope) {
		this.document = document;
		this.scope = scope;
	}

	/** Escribe el {@code BPMNDiagram} completo dentro de {@code definitions}. */
	void write() {
		var diagramId = scope.diagram().id();
		var diagram = document.bpmndi("BPMNDiagram");
		diagram.setAttribute("id", "Diagram_" + diagramId);
		var plane = document.bpmndi("BPMNPlane");
		plane.setAttribute("id", "Plane_" + diagramId);
		plane.setAttribute("bpmnElement", "Collaboration_" + diagramId);
		diagram.appendChild(plane);

		for (var processId : scope.pools()) {
			var shape = shape(DiagramScope.participantId(processId), scope.poolBounds().get(processId));
			shape.setAttribute("isHorizontal", "true");
			plane.appendChild(shape);
		}
		for (var process : scope.processes()) {
			var pool = scope.poolBounds().get(process.id());
			var layout = scope.layouts().get(process.id());
			for (var node : process.flowNodes()) {
				plane.appendChild(nodeShape(node, layout.node(node.id(), pool)));
			}
			for (var flow : process.sequenceFlows()) {
				var source = layout.node(flow.sourceRef(), pool);
				var target = layout.node(flow.targetRef(), pool);
				var sourceIsGateway = process.flowNode(flow.sourceRef()).orElseThrow() instanceof FlowNode.ExclusiveGateway;
				plane.appendChild(edge(flow.id(), EdgeRouting.sequenceFlow(source, target, sourceIsGateway)));
			}
		}
		for (var flow : scope.messageFlows()) {
			var sourcePool = scope.poolBounds().get(flow.sourceProcessId());
			var targetPool = scope.poolBounds().get(flow.targetProcessId());
			var source = Optional.ofNullable(scope.layouts().get(flow.sourceProcessId()))
					.map(layout -> layout.node(flow.sourceRef(), sourcePool));
			var target = Optional.ofNullable(scope.layouts().get(flow.targetProcessId()))
					.map(layout -> layout.node(flow.targetRef(), targetPool));
			plane.appendChild(edge(flow.id(),
					EdgeRouting.messageFlow(sourcePool, source, targetPool, target, DiagramScope.POOL_GAP)));
		}
		document.definitionsElement().appendChild(diagram);
	}

	private Element nodeShape(FlowNode node, Bounds bounds) {
		var shape = shape(node.id(), bounds);
		switch (node) {
			case FlowNode.ExclusiveGateway gateway -> {
				shape.setAttribute("isMarkerVisible", "true");
				var label = document.bpmndi("BPMNLabel");
				label.appendChild(bounds(new Bounds(bounds.x() - 80, bounds.bottom() + 4, 100, 42)));
				shape.appendChild(label);
			}
			case FlowNode.StartEvent start -> {
				// bpmn-js sitúa por defecto la etiqueta de los eventos debajo de la figura.
			}
			case FlowNode.EndEvent end -> {
				// bpmn-js sitúa por defecto la etiqueta de los eventos debajo de la figura.
			}
			case FlowNode.Activity activity -> {
				// El nombre de una actividad va dentro de su figura.
			}
		}
		return shape;
	}

	private Element shape(String elementId, Bounds bounds) {
		var shape = document.bpmndi("BPMNShape");
		shape.setAttribute("id", elementId + "_di");
		shape.setAttribute("bpmnElement", elementId);
		shape.appendChild(bounds(bounds));
		return shape;
	}

	private Element bounds(Bounds bounds) {
		var element = document.dc("Bounds");
		element.setAttribute("x", number(bounds.x()));
		element.setAttribute("y", number(bounds.y()));
		element.setAttribute("width", number(bounds.width()));
		element.setAttribute("height", number(bounds.height()));
		return element;
	}

	private Element edge(String elementId, List<Point> waypoints) {
		var edge = document.bpmndi("BPMNEdge");
		edge.setAttribute("id", elementId + "_di");
		edge.setAttribute("bpmnElement", elementId);
		for (var point : waypoints) {
			var waypoint = document.di("waypoint");
			waypoint.setAttribute("x", number(point.x()));
			waypoint.setAttribute("y", number(point.y()));
			edge.appendChild(waypoint);
		}
		return edge;
	}

	private static String number(double value) {
		return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
	}
}
