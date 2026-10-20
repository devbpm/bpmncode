package io.github.eldiegos.bpmncode.model.reader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import io.github.eldiegos.bpmncode.model.FlowNode.StartEvent;
import io.github.eldiegos.bpmncode.model.MessageFlow;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Enlazador de los mensajes publicados con los procesos que inician.
 * <p>
 * Responsabilidad: derivar los {@link MessageFlow} entre los nodos que publican un mensaje (eventos de fin y tareas de
 * envío) y los eventos de inicio que lo consumen, y comprobar que cada consumidor acepta la versión que se le publica.
 * <p>
 * Invariantes: todo mensaje consumido tiene al menos un publicador; toda versión publicada la acepta cada consumidor
 * del mensaje (un consumidor puede aceptar versiones que ya nadie publica, por los mensajes que aún estén en vuelo);
 * los flujos se devuelven ordenados por identificador.
 *
 * @see AnnotationProcessModelReader
 */
final class MessageFlowLinker {

	private final ModelDiagnostics diagnostics;

	MessageFlowLinker(ModelDiagnostics diagnostics) {
		this.diagnostics = diagnostics;
	}

	/**
	 * Enlaza publicadores y consumidores.
	 *
	 * @param nodes nodos convertidos de todos los procesos
	 * @return los flujos de mensaje
	 */
	List<MessageFlow> link(List<ParsedFlowNode> nodes) {
		var publishers = nodes.stream().filter(parsed -> parsed.node().publishedMessage().isPresent()).toList();
		var consumers = nodes.stream().filter(parsed -> parsed.node() instanceof StartEvent start
				&& start.messageDefinition().isPresent()).toList();
		var flows = new ArrayList<MessageFlow>();
		// El mismo par de nodos puede repetirse entre versiones de un proceso: el identificador se reserva una sola vez.
		var linked = new HashSet<String>();
		for (var consumer : consumers) {
			var start = (StartEvent) consumer.node();
			var accepted = start.messageDefinition().orElseThrow();
			var sources = publishers.stream()
					.filter(publisher -> publisher.node().publishedMessage().orElseThrow().messageId()
							.equals(accepted.messageRef()))
					.toList();
			if (sources.isEmpty()) {
				diagnostics.report(ViolationKind.UNKNOWN_MESSAGE_REF, start.implementation().toString(),
						"no process publishes the message " + accepted.messageRef() + " that starts this process");
			}
			for (var source : sources) {
				var publisher = source.node();
				var version = publisher.publishedMessage().orElseThrow().version();
				if (!accepted.accepts(version)) {
					diagnostics.report(ViolationKind.MESSAGE_VERSION_NOT_ACCEPTED, start.implementation().toString(),
							consumer.ref() + " accepts " + accepted.messageRef() + " versions " + accepted.versions() + " but "
									+ source.ref() + " publishes version " + version + " at " + publisher.id());
					continue;
				}
				var id = "MessageFlow_" + publisher.id() + "_" + start.id();
				if (linked.contains(id) || diagnostics.claimBpmnId(id, source.ref(), start.implementation().toString())) {
					linked.add(id);
					flows.add(new MessageFlow(id, accepted.messageRef(), version, source.ref(), publisher.id(),
							consumer.ref(), start.id()));
				}
			}
		}
		flows.sort(Comparator.comparing(MessageFlow::id).thenComparing(MessageFlow::sourceProcess)
				.thenComparing(MessageFlow::targetProcess));
		return flows;
	}
}
