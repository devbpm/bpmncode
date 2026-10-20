package io.github.eldiegos.bpmncode.bpmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Trazado ortogonal de las aristas del diagrama.
 * <p>
 * Responsabilidad: calcular los puntos de paso de los flujos de secuencia dentro de una piscina y de los flujos de
 * mensaje entre piscinas.
 * <p>
 * Invariantes: toda arista tiene al menos dos puntos; los flujos de mensaje salen por el lateral del evento que publica
 * para no cruzar la etiqueta que tiene debajo.
 *
 * @see DiagramInterchangeWriter
 */
final class EdgeRouting {

	private EdgeRouting() {
	}

	/**
	 * Traza un flujo de secuencia entre dos nodos ya situados.
	 *
	 * @param source rectángulo del origen
	 * @param target rectángulo del destino
	 * @param sourceIsGateway si el origen es una decisión (sus ramas salen por arriba o por abajo)
	 * @return los puntos de paso
	 */
	static List<Point> sequenceFlow(Bounds source, Bounds target, boolean sourceIsGateway) {
		if (target.centerX() > source.centerX()) {
			if (Math.abs(target.centerY() - source.centerY()) < 1) {
				return List.of(new Point(source.right(), source.centerY()), new Point(target.x(), target.centerY()));
			}
			if (sourceIsGateway) {
				var leaveY = target.centerY() > source.centerY() ? source.bottom() : source.y();
				return List.of(new Point(source.centerX(), leaveY), new Point(source.centerX(), target.centerY()),
						new Point(target.x(), target.centerY()));
			}
			var middleX = (source.right() + target.x()) / 2;
			return List.of(new Point(source.right(), source.centerY()), new Point(middleX, source.centerY()),
					new Point(middleX, target.centerY()), new Point(target.x(), target.centerY()));
		}
		// Bucle hacia atrás: se enruta por encima de ambos nodos.
		var aboveY = Math.min(source.y(), target.y()) - 25;
		return List.of(new Point(source.centerX(), source.y()), new Point(source.centerX(), aboveY),
				new Point(target.centerX(), aboveY), new Point(target.centerX(), target.y()));
	}

	/**
	 * Traza un flujo de mensaje entre dos piscinas.
	 *
	 * @param sourcePool piscina que publica
	 * @param source evento que publica, o vacío si su piscina está colapsada
	 * @param targetPool piscina que consume
	 * @param target evento que consume, o vacío si su piscina está colapsada
	 * @param poolGap separación vertical entre piscinas
	 * @return los puntos de paso
	 */
	static List<Point> messageFlow(Bounds sourcePool, Optional<Bounds> source, Bounds targetPool, Optional<Bounds> target,
			double poolGap) {
		var downwards = sourcePool.y() < targetPool.y();
		var points = new ArrayList<Point>();
		double laneX;
		if (source.isPresent()) {
			var event = source.get();
			laneX = event.right() + 30;
			points.add(new Point(event.right(), event.centerY()));
			points.add(new Point(laneX, event.centerY()));
		}
		else {
			laneX = target.map(Bounds::centerX).orElse(sourcePool.centerX());
			points.add(new Point(laneX, downwards ? sourcePool.bottom() : sourcePool.y()));
		}
		if (target.isEmpty()) {
			points.add(new Point(laneX, downwards ? targetPool.y() : targetPool.bottom()));
			return points;
		}
		var event = target.get();
		var end = new Point(event.centerX(), downwards ? event.y() : event.bottom());
		if (Math.abs(laneX - end.x()) >= 1) {
			var middleY = downwards ? targetPool.y() - poolGap / 2 : targetPool.bottom() + poolGap / 2;
			points.add(new Point(laneX, middleY));
			points.add(new Point(end.x(), middleY));
		}
		points.add(end);
		return points;
	}
}
