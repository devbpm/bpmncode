package io.github.eldiegos.bpmncode.web;

import java.util.List;

/**
 * Ficha de un elemento del BPMN para el visor: qué es, a qué versión de qué proceso pertenece y qué código lo implementa.
 * <p>
 * Responsabilidad: ser el JSON que el visor muestra al seleccionar un elemento, con todos los textos ya traducidos.
 * <p>
 * Invariantes: {@code process} es la versión exacta que dibuja la vista; las listas nunca son nulas; los campos que no
 * aplican a una categoría son nulos (p. ej. {@code transition} en un nodo).
 *
 * @param id identificador del elemento en el BPMN
 * @param category {@code participant}, {@code node}, {@code flow} o {@code message}
 * @param name nombre visible
 * @param kind tipo traducido (p. ej. «Tarea humana»)
 * @param description descripción del modelo, si la hay
 * @param process proceso y versión a los que pertenece
 * @param code código Java que lo implementa; nulo en transiciones y mensajes
 * @param rules reglas de negocio que aplica
 * @param events eventos que consume o publica
 * @param roles quién puede o debe realizar el elemento; solo en tareas humanas
 * @param transition origen, destino y condición; solo en transiciones y mensajes
 * @param runningHere instancias de esta versión detenidas en este nodo; nulo si no es un nodo
 * @see DiagramViews#elements(io.github.eldiegos.bpmncode.bpmn.BpmnDiagram, java.util.Locale)
 */
public record DiagramElementInfo(String id, String category, String name, String kind, String description,
		ProcessInfo process, CodeInfo code, List<RuleInfo> rules, List<EventInfo> events, List<RoleInfo> roles,
		TransitionInfo transition, Long runningHere) {

	public DiagramElementInfo {
		rules = List.copyOf(rules);
		events = List.copyOf(events);
		roles = List.copyOf(roles);
	}

	/**
	 * Rol de una tarea humana: un propietario potencial o una persona que la realiza.
	 * <p>
	 * Responsabilidad: mostrar quién puede o debe hacer la tarea según el modelo, sin decir a quién está asignada (eso lo
	 * decide la aplicación).
	 * <p>
	 * Invariantes: o es un recurso declarado (con identificador y tipo Java) o una expresión (sin identificador ni tipo).
	 *
	 * @param kind «Propietario potencial» o «Persona que la realiza», traducido
	 * @param resourceId identificador del recurso; nulo si el rol es una expresión
	 * @param name nombre visible del recurso, o el texto de la expresión
	 * @param type clase Java que declara el recurso; nulo si el rol es una expresión
	 */
	public record RoleInfo(String kind, String resourceId, String name, String type) {
	}

	/**
	 * Proceso y versión de un elemento.
	 * <p>
	 * Responsabilidad: decir a qué versión exacta de qué proceso pertenece un elemento.
	 * <p>
	 * Invariantes: {@code versionLabel} coincide con {@code current}.
	 *
	 * @param id identificador del proceso
	 * @param name nombre visible
	 * @param version versión
	 * @param current si es la versión vigente
	 * @param versionLabel versión y si es la vigente, p. ej. «v1 · versión anterior»
	 * @param definedBy clase que declara el proceso ({@code @Process})
	 * @param implementedBy clases que implementan sus pasos
	 */
	public record ProcessInfo(String id, String name, int version, boolean current, String versionLabel,
			String definedBy, List<String> implementedBy) {

		public ProcessInfo {
			implementedBy = List.copyOf(implementedBy);
		}
	}

	/**
	 * Código Java que implementa un elemento.
	 * <p>
	 * Responsabilidad: llevar del diagrama a la clase y al método.
	 * <p>
	 * Invariantes: la clase nunca está vacía.
	 *
	 * @param className nombre completo de la clase
	 * @param member método con los tipos de sus parámetros; nulo si la referencia es a la clase
	 */
	public record CodeInfo(String className, String member) {
	}

	/**
	 * Regla de negocio y su implementación.
	 * <p>
	 * Responsabilidad: mostrar qué regla aplica un paso y dónde está su código.
	 * <p>
	 * Invariantes: los textos están traducidos; el código no.
	 *
	 * @param id identificador
	 * @param version versión de la regla que aplica este elemento
	 * @param name nombre visible de esa versión
	 * @param description descripción visible de esa versión
	 * @param code tipo o método Java que implementa esa versión
	 */
	public record RuleInfo(String id, int version, String name, String description, CodeInfo code) {
	}

	/**
	 * Evento que un elemento consume o publica.
	 * <p>
	 * Responsabilidad: mostrar el evento de Spring que conecta procesos.
	 * <p>
	 * Invariantes: el tipo es el nombre completo de la clase del evento.
	 *
	 * @param direction «Consume» o «Publica», traducido
	 * @param id identificador del evento
	 * @param name nombre visible
	 * @param type clase Java del evento
	 */
	public record EventInfo(String direction, String id, String name, String type) {
	}

	/**
	 * Origen y destino de una transición o de un mensaje.
	 * <p>
	 * Responsabilidad: explicar qué conecta una transición y cuándo se elige.
	 * <p>
	 * Invariantes: condición y resultado van juntos: o los dos o ninguno.
	 *
	 * @param source paso de origen, con su proceso y versión
	 * @param target paso de destino, con su proceso y versión
	 * @param condition condición traducida; nula si la transición es incondicional
	 * @param when resultado del código que la elige (p. ej. {@code ELIGIBLE}); nulo si es incondicional
	 */
	public record TransitionInfo(String source, String target, String condition, String when) {
	}
}
