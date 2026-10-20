package io.github.eldiegos.bpmncode.model.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.github.eldiegos.bpmncode.annotation.BusinessRule;
import io.github.eldiegos.bpmncode.model.BusinessRuleDefinition;
import io.github.eldiegos.bpmncode.model.BusinessRuleRef;
import io.github.eldiegos.bpmncode.model.CodeReference;
import io.github.eldiegos.bpmncode.model.ViolationKind;

/**
 * Reglas de negocio declaradas con {@code @BusinessRule}.
 * <p>
 * Responsabilidad: descubrir las versiones de las reglas anotadas en tipos y métodos y resolver las referencias que
 * hacen los nodos ({@code R-PER-001} o {@code R-PER-001@2}).
 * <p>
 * Invariantes: cada versión de regla tiene un identificador válido, una versión mayor que cero, un nombre legible y es
 * única; una referencia sin versión solo se resuelve si la regla tiene una única versión.
 *
 * @param byKey reglas por identificador y versión, ordenadas
 * @see BusinessRuleDefinition
 */
record DeclaredBusinessRules(SortedMap<BusinessRuleRef, BusinessRuleDefinition> byKey) {

	/** {@code ID} o {@code ID@versión}. */
	private static final Pattern REFERENCE = Pattern.compile("(.+?)(?:" + BusinessRuleRef.SEPARATOR + "(\\d+))?");

	/**
	 * Lee las reglas declaradas.
	 *
	 * @param classes clases con metadatos, en orden canónico
	 * @param diagnostics colector de problemas
	 * @return las reglas válidas
	 */
	static DeclaredBusinessRules read(List<Class<?>> classes, ModelDiagnostics diagnostics) {
		var rules = new TreeMap<BusinessRuleRef, BusinessRuleDefinition>();
		for (var type : classes) {
			var rule = type.getAnnotation(BusinessRule.class);
			if (rule != null) {
				add(rules, rule, CodeReference.ofType(type), diagnostics);
			}
			for (var method : ProcessMetadata.methodsOf(type)) {
				var methodRule = method.getAnnotation(BusinessRule.class);
				if (methodRule != null) {
					add(rules, methodRule, CodeReference.ofMethod(method), diagnostics);
				}
			}
		}
		return new DeclaredBusinessRules(rules);
	}

	/**
	 * Resuelve las reglas que referencia un nodo.
	 *
	 * @param references referencias declaradas: {@code ID} o {@code ID@versión}
	 * @param location dónde se referencian
	 * @param diagnostics colector de problemas
	 * @return las versiones de regla existentes, sin repetir; los problemas quedan registrados
	 */
	List<BusinessRuleRef> resolve(String[] references, String location, ModelDiagnostics diagnostics) {
		var keys = new ArrayList<BusinessRuleRef>();
		for (var reference : references) {
			var matcher = REFERENCE.matcher(reference);
			if (reference.isBlank() || !matcher.matches()) {
				diagnostics.report(ViolationKind.UNIDENTIFIED_ELEMENT, location, "reference to a rule without identifier");
				continue;
			}
			var id = matcher.group(1);
			var versions = byKey.keySet().stream().filter(key -> key.id().equals(id)).toList();
			if (versions.isEmpty()) {
				diagnostics.report(ViolationKind.UNKNOWN_BUSINESS_RULE_REF, location, "references the unknown rule '" + id
						+ "' (there is no @BusinessRule with that id)");
				continue;
			}
			BusinessRuleRef key;
			if (matcher.group(2) != null) {
				var version = Integer.parseInt(matcher.group(2));
				key = versions.stream().filter(candidate -> candidate.version() == version).findFirst().orElse(null);
				if (key == null) {
					diagnostics.report(ViolationKind.UNKNOWN_BUSINESS_RULE_REF, location, "references version " + version
							+ " of rule " + id + ", which does not exist (versions: " + versions + ")");
					continue;
				}
			}
			else if (versions.size() > 1) {
				diagnostics.report(ViolationKind.AMBIGUOUS_REFERENCE, location, "references rule " + id
						+ " without a version, but it has several (" + versions + "); use " + id + BusinessRuleRef.SEPARATOR
						+ "<version>");
				continue;
			}
			else {
				key = versions.getFirst();
			}
			if (!keys.contains(key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/**
	 * Devuelve todas las versiones de las reglas.
	 *
	 * @return las reglas por identificador y versión
	 */
	List<BusinessRuleDefinition> all() {
		return List.copyOf(byKey.values());
	}

	private static void add(SortedMap<BusinessRuleRef, BusinessRuleDefinition> rules, BusinessRule rule, CodeReference code,
                            ModelDiagnostics diagnostics) {
		var location = "Rule at " + code;
		if (!diagnostics.isValidId(rule.id(), "rule", location)) {
			return;
		}
		if (rule.version() < 1) {
			diagnostics.report(ViolationKind.INCOMPATIBLE_METADATA, location,
					"rule " + rule.id() + " has version " + rule.version() + "; versions start at 1");
			return;
		}
		var key = BusinessRuleRef.of(rule.id(), rule.version());
		var existing = rules.get(key);
		if (existing != null) {
			diagnostics.report(ViolationKind.DUPLICATE_ID, location,
					"rule " + key + " is already declared at " + existing.implementation());
			return;
		}
		if (diagnostics.hasName(rule.name(), location)) {
			rules.put(key, new BusinessRuleDefinition(rule.id(), rule.version(), rule.name(), rule.documentation(), code));
		}
	}
}
