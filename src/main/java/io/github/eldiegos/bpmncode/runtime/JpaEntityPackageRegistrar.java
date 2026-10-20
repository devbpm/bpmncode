package io.github.eldiegos.bpmncode.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

/**
 * Registro del paquete de las entidades JPA de la librería entre los paquetes de autoconfiguración de la aplicación.
 * <p>
 * Responsabilidad: que la unidad de persistencia de una aplicación con JPA gestione las entidades del almacén JPA,
 * aunque la aplicación tenga otro paquete raíz.
 * <p>
 * Invariantes: no hace nada si la aplicación no tiene JPA o si ha elegido otro almacén ({@code pac.process.store.type}
 * {@code memory} o {@code jdbc}), así que entonces sus tablas no aparecen en el esquema que genere JPA; solo añade el
 * paquete si ninguno de los ya registrados lo contiene; nunca instancia el bean de paquetes (instanciarlo antes de tiempo
 * haría perder los paquetes que registran autoconfiguraciones posteriores); no carga ninguna clase JPA.
 *
 * @see ProcessRuntimeAutoConfiguration
 */
class JpaEntityPackageRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware, BeanClassLoaderAware {

	/** Paquete de las entidades; se escribe como texto para no cargar ninguna clase JPA. */
	static final String ENTITY_PACKAGE = "io.github.eldiegos.bpmncode.runtime.jpa";

	/** Nombre del bean en el que Spring Boot guarda los paquetes (argumento 0 del constructor). */
	private static final String PACKAGES_BEAN = AutoConfigurationPackages.class.getName();

	private Environment environment;
	private ClassLoader classLoader;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		var type = environment.getProperty("pac.process.store.type", "auto").trim().toLowerCase();
		var wanted = type.equals("auto") || type.equals("jpa");
		if (wanted && ProcessRuntimeAutoConfiguration.jpaAvailable(classLoader) && registeredPackages(registry).stream()
				.noneMatch(base -> ENTITY_PACKAGE.equals(base) || ENTITY_PACKAGE.startsWith(base + "."))) {
			AutoConfigurationPackages.register(registry, ENTITY_PACKAGE);
		}
	}

	private static List<String> registeredPackages(BeanDefinitionRegistry registry) {
		if (!registry.containsBeanDefinition(PACKAGES_BEAN)) {
			return List.of();
		}
		return Optional.ofNullable(registry.getBeanDefinition(PACKAGES_BEAN).getConstructorArgumentValues()
						.getIndexedArgumentValue(0, String[].class))
				.map(holder -> (String[]) holder.getValue())
				.map(Arrays::asList)
				.orElse(List.of());
	}

	/** Indica si una clase está en el classpath de la aplicación. */
	static boolean present(String className, ClassLoader classLoader) {
		return ClassUtils.isPresent(className, classLoader);
	}
}
