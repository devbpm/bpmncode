package io.github.eldiegos.bpmncode.model;

/**
 * Rol de recurso de una tarea humana ({@code bpmn:resourceRole}): a quién corresponde, como propietario potencial
 * ({@code bpmn:potentialOwner}) o como persona que la realiza ({@code bpmn:humanPerformer}).
 * <p>
 * Responsabilidad: describir quién, por un recurso declarado o por una expresión, sin asignar la tarea: eso lo hace la
 * aplicación.
 * <p>
 * Invariantes: un rol es exactamente una de las dos formas que admite BPMN, nunca las dos.
 *
 * @see FlowNode.UserTask#potentialOwners()
 * @see FlowNode.UserTask#humanPerformers()
 */
public sealed interface ResourceRole permits ResourceRole.Reference, ResourceRole.Expression {

	/**
	 * Rol que cumple un recurso declarado (elemento {@code bpmn:resourceRef}).
	 * <p>
	 * Responsabilidad: apuntar a un {@link ResourceDefinition}.
	 * <p>
	 * Invariantes: {@code resourceRef} es el identificador de un recurso declarado y nunca está vacío.
	 *
	 * @param resourceRef identificador del recurso
	 * @see Definitions#resource(String)
	 */
	record Reference(String resourceRef) implements ResourceRole {

		public Reference {
			if (resourceRef == null || resourceRef.isBlank()) {
				throw new IllegalArgumentException("A resource role needs a resourceRef");
			}
		}
	}

	/**
	 * Rol que identifica una expresión que resuelve la aplicación (elemento {@code bpmn:resourceAssignmentExpression}).
	 * <p>
	 * Responsabilidad: describir quién en texto, p. ej. {@code manager of the employee}.
	 * <p>
	 * Invariantes: la expresión nunca está vacía.
	 *
	 * @param expression la expresión
	 */
	record Expression(String expression) implements ResourceRole {

		public Expression {
			if (expression == null || expression.isBlank()) {
				throw new IllegalArgumentException("A resource assignment expression cannot be blank");
			}
		}
	}
}
