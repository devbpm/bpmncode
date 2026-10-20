package io.github.eldiegos.bpmncode.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades del módulo web opcional ({@code pac.process.web.*}).
 * <p>
 * Responsabilidad: activar el módulo web (API de solo lectura y visor bpmn-js) y decidir dónde se publica y de dónde
 * se carga bpmn-js.
 * <p>
 * Invariantes: el módulo está apagado salvo que la aplicación ponga {@code enabled=true}, porque expone el código que
 * implementa cada paso y los datos de las instancias; la ruta base empieza por {@code /} y no termina en barra (se
 * rechaza, porque las rutas de los controladores la usan tal cual).
 *
 * @param enabled si se publica el módulo web ({@code pac.process.web.enabled}, por defecto {@code false})
 * @param basePath ruta bajo la que se publican el visor y la API ({@code pac.process.web.base-path}, por defecto
 *        {@value #DEFAULT_BASE_PATH})
 * @param bpmnJsLocation carpeta {@code dist} de bpmn-js, absoluta o relativa a la ruta de contexto
 *        ({@code pac.process.web.bpmn-js-location}, por defecto la del WebJar {@value #DEFAULT_BPMN_JS_LOCATION})
 * @see ProcessWebAutoConfiguration
 */
@ConfigurationProperties("pac.process.web")
public record ProcessWebProperties(@DefaultValue("false") boolean enabled,
		@DefaultValue(DEFAULT_BASE_PATH) String basePath,
		@DefaultValue(DEFAULT_BPMN_JS_LOCATION) String bpmnJsLocation) {

	/** Ruta base por defecto. */
	public static final String DEFAULT_BASE_PATH = "/bpmncode";

	/** Ubicación por defecto de bpmn-js: el WebJar {@code org.webjars.npm:bpmn-js}, que aporta la aplicación. */
	public static final String DEFAULT_BPMN_JS_LOCATION = "/webjars/bpmn-js/18.10.1/dist";

	public ProcessWebProperties {
		if (basePath == null || !basePath.startsWith("/") || basePath.endsWith("/")) {
			throw new IllegalArgumentException("pac.process.web.base-path must start with '/' and must not end with it,"
					+ " e.g. /bpmncode");
		}
		if (bpmnJsLocation == null || bpmnJsLocation.isBlank()) {
			throw new IllegalArgumentException("pac.process.web.bpmn-js-location must not be blank");
		}
		bpmnJsLocation = bpmnJsLocation.endsWith("/") ? bpmnJsLocation.substring(0, bpmnJsLocation.length() - 1)
				: bpmnJsLocation;
	}
}
