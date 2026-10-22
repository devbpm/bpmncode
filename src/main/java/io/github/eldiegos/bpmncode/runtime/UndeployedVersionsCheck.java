package io.github.eldiegos.bpmncode.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.github.eldiegos.bpmncode.model.ProcessDefinitionRegistry;
import io.github.eldiegos.bpmncode.runtime.ProcessStoreProperties.UndeployedVersions;

/**
 * Comprobación, al arrancar, de que las instancias activas guardadas pertenecen a versiones de proceso desplegadas.
 * <p>
 * Responsabilidad: avisar pronto de las instancias que no podrán avanzar porque se retiró la clase {@code @Process} de
 * su versión, en lugar de descubrirlo en el siguiente paso de cada una.
 * <p>
 * Invariantes: consulta solo las versiones con instancias activas ({@link ProcessInstanceStore#activeVersions()}), no las
 * instancias; por defecto solo avisa ({@link UndeployedVersions#WARN}): impedir el arranque es una decisión de la
 * aplicación ({@link UndeployedVersions#FAIL}).
 *
 * @see ProcessStoreProperties#undeployedVersions()
 */
final class UndeployedVersionsCheck implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(UndeployedVersionsCheck.class);

	private final ProcessDefinitionRegistry registry;
	private final ProcessInstanceStore store;
	private final UndeployedVersions mode;

	UndeployedVersionsCheck(ProcessDefinitionRegistry registry, ProcessInstanceStore store, UndeployedVersions mode) {
		this.registry = registry;
		this.store = store;
		this.mode = mode;
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (mode == UndeployedVersions.IGNORE) {
			return;
		}
		try {
			var undeployed = store.activeVersions().stream()
					.filter(version -> registry.definitions().process(version).isEmpty()).toList();
			if (undeployed.isEmpty()) {
				return;
			}
			var message = "Active process instances belong to versions that are no longer deployed: " + undeployed
					+ ". They cannot continue until the @Process class of each version is deployed again";
			if (mode == UndeployedVersions.FAIL) {
				throw new IllegalStateException(message + " (pac.process.store.undeployed-versions=fail)");
			}
			log.warn("{} (set pac.process.store.undeployed-versions=fail to stop the start-up instead)", message);
		}
		catch (RuntimeException e) {
			if (mode == UndeployedVersions.FAIL) {
				throw e;
			}
			// Solo es un aviso: si el almacén aún no puede consultarse, la aplicación arranca igual.
			log.warn("Could not check whether active process instances belong to deployed versions: {}", e.toString());
		}
	}
}
