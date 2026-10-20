package io.github.eldiegos.bpmncode.model.reader;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/**
 * Buscador de las clases que llevan metadatos de proceso.
 * <p>
 * Responsabilidad: cargar (sin inicializar) todo tipo de primer nivel o anidado estático de los paquetes indicados y
 * quedarse con los que tienen anotaciones del metamodelo, incluidas las de métodos privados y las de clases que
 * olvidaron su {@code @Process}/{@code @ProcessPart}.
 * <p>
 * Invariantes: el resultado está ordenado por nombre de clase y no tiene repetidos.
 *
 * @see AnnotationProcessModelReader#scan(String...)
 */
final class AnnotatedClassScanner {

	private AnnotatedClassScanner() {
	}

	/**
	 * Busca las clases con metadatos de proceso.
	 *
	 * @param basePackages paquetes raíz
	 * @return las clases encontradas, ordenadas por nombre
	 */
	static List<Class<?>> scan(String... basePackages) {
		var scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				return definition.getMetadata().isIndependent();
			}
		};
		scanner.addIncludeFilter((reader, factory) -> true);
		var classLoader = ClassUtils.getDefaultClassLoader();
		return Arrays.stream(basePackages)
				.flatMap(basePackage -> scanner.findCandidateComponents(basePackage).stream())
				.map(BeanDefinition::getBeanClassName)
				.distinct()
				.sorted()
				.<Class<?>> map(name -> ClassUtils.resolveClassName(name, classLoader))
				.filter(ProcessMetadata::isPresentOn)
				.toList();
	}
}
