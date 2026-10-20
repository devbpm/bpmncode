package io.github.eldiegos.bpmncode.model.reader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import org.springframework.core.env.StandardEnvironment;

import io.github.eldiegos.bpmncode.model.CodeReference;
import io.github.eldiegos.bpmncode.model.Definitions;
import io.github.eldiegos.bpmncode.model.MessageContractLock;
import io.github.eldiegos.bpmncode.model.ViolationKind;
import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.model.ProcessModelException;

/**
 * Lector del modelo de procesos a partir de las clases anotadas.
 * <p>
 * Responsabilidad: coordinar las fases de lectura (mensajes, reglas, procesos, nodos, grafo y mensajes entre procesos)
 * y devolver el {@link Definitions} o todos los problemas encontrados.
 * <p>
 * Invariantes: el resultado depende solo de las anotaciones, nunca del orden físico de los métodos; si hay algún
 * problema no se devuelve modelo.
 *
 * @see ProcessDefinitionRegistry
 */
public final class AnnotationProcessModelReader {

	private AnnotationProcessModelReader() {
	}

	/**
	 * Busca metadatos de proceso en los paquetes indicados y los lee.
	 *
	 * @param basePackages paquetes raíz
	 * @return el modelo validado
	 * @throws ProcessModelException si el modelo tiene problemas
	 */
	public static Definitions scan(String... basePackages) {
		return read(AnnotatedClassScanner.scan(basePackages));
	}

	/**
	 * Busca metadatos de proceso en los paquetes indicados y los lee, resolviendo los {@code ${…}} de los
	 * {@code @Scheduled} con la configuración de la aplicación.
	 *
	 * @param placeholders resolvedor de {@code ${…}}, p. ej. {@code environment::resolvePlaceholders}
	 * @param basePackages paquetes raíz
	 * @return el modelo validado
	 * @throws ProcessModelException si el modelo tiene problemas
	 */
	public static Definitions scan(UnaryOperator<String> placeholders, String... basePackages) {
		return read(AnnotatedClassScanner.scan(basePackages), placeholders);
	}

	/**
	 * Busca metadatos de proceso en los paquetes indicados y los lee, resolviendo los {@code ${…}} con la configuración
	 * de la aplicación y comprobando cada versión de mensaje contra sus huellas congeladas.
	 *
	 * @param placeholders resolvedor de {@code ${…}}, p. ej. {@code environment::resolvePlaceholders}
	 * @param lock contenido de {@code pac/message-contracts.lock}, o vacío si la aplicación no lo tiene (no se comprueba)
	 * @param basePackages paquetes raíz
	 * @return el modelo validado
	 * @throws ProcessModelException si el modelo tiene problemas
	 */
	public static Definitions scan(UnaryOperator<String> placeholders, Optional<MessageContractLock> lock,
			String... basePackages) {
		return read(AnnotatedClassScanner.scan(basePackages), placeholders, lock);
	}

	/**
	 * Lee el modelo de un conjunto de clases; los {@code ${…}} de los {@code @Scheduled} se resuelven con las
	 * propiedades del sistema, las variables de entorno y sus valores por defecto.
	 *
	 * @param classes clases anotadas
	 * @return el modelo validado
	 * @throws ProcessModelException si el modelo tiene problemas
	 */
	public static Definitions read(Collection<Class<?>> classes) {
		return read(classes, new StandardEnvironment()::resolvePlaceholders);
	}

	/**
	 * Lee el modelo de un conjunto de clases resolviendo los {@code ${…}} de los {@code @Scheduled}.
	 *
	 * @param classes clases anotadas
	 * @param placeholders resolvedor de {@code ${…}}
	 * @return el modelo validado
	 * @throws ProcessModelException si el modelo tiene problemas
	 */
	public static Definitions read(Collection<Class<?>> classes, UnaryOperator<String> placeholders) {
		return read(classes, placeholders, Optional.empty());
	}

	/**
	 * Lee el modelo de un conjunto de clases resolviendo los {@code ${…}} de los {@code @Scheduled} y comprobando cada
	 * versión de mensaje contra sus huellas congeladas.
	 *
	 * @param classes clases anotadas
	 * @param placeholders resolvedor de {@code ${…}}
	 * @param lock contenido de {@code pac/message-contracts.lock}, o vacío si no se comprueba
	 * @return el modelo validado
	 * @throws ProcessModelException si el modelo tiene problemas
	 */
	public static Definitions read(Collection<Class<?>> classes, UnaryOperator<String> placeholders,
			Optional<MessageContractLock> lock) {
		var diagnostics = new ModelDiagnostics();
		var sorted = classes.stream().distinct().sorted(Comparator.comparing(Class::getName)).toList();
		var messages = DeclaredMessages.read(sorted, lock, diagnostics);
		var errors = DeclaredErrors.read(sorted, diagnostics);
		var resources = DeclaredResources.read(sorted, diagnostics);
		var rules = DeclaredBusinessRules.read(sorted, diagnostics);
		var processes = DeclaredProcesses.read(sorted, diagnostics);
		var parser = new FlowNodeParser(messages, errors, resources, rules, diagnostics, placeholders);
		var nodes = parseFlowNodes(sorted, processes, parser, diagnostics);
		var graphs = new ProcessGraphBuilder(diagnostics);
		var definitions = processes.byRef().values().stream()
				.map(header -> graphs.build(header, nodes))
				.flatMap(Optional::stream)
				.toList();
		var messageFlows = new MessageFlowLinker(diagnostics).link(nodes);
		diagnostics.throwIfAny();
		return new Definitions(definitions, rules.all(), messages.sorted(), errors.errors(),
				resources.resources(), messageFlows);
	}

	private static List<ParsedFlowNode> parseFlowNodes(List<Class<?>> classes, DeclaredProcesses processes,
			FlowNodeParser parser, ModelDiagnostics diagnostics) {
		var nodes = new ArrayList<ParsedFlowNode>();
		for (var type : classes) {
			var membership = processes.membershipOf(type, diagnostics);
			for (var method : ProcessMetadata.methodsOf(type)) {
				var location = CodeReference.ofMethod(method).toString();
				var declared = ProcessMetadata.flowNodeAnnotationsOf(method);
				if (declared.isEmpty()) {
					continue;
				}
				if (declared.size() > 1) {
					diagnostics.report(ViolationKind.AMBIGUOUS_REFERENCE, location, "the method declares several"
							+ " flow nodes: " + declared.stream().map(a -> "@" + a.annotationType().getSimpleName()).toList());
					continue;
				}
				switch (membership) {
					case ProcessMembership.Member member -> parser.parse(method, member.ref()).ifPresent(parsed -> {
						nodes.add(parsed);
						processes.byRef().get(member.ref()).implementedBy().add(type.getName());
					});
					case ProcessMembership.Undeclared undeclared -> diagnostics.report(
							ViolationKind.MISSING_PROCESS_ANNOTATION, location,
							"class " + type.getSimpleName() + " declares neither @Process nor @ProcessPart");
					case ProcessMembership.Unresolved unresolved -> {
						// El problema de la declaración ya se registró al resolver la pertenencia.
					}
				}
			}
		}
		return nodes;
	}
}
