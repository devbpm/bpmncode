/**
 * Estadísticas de ejecución calculadas sobre la evidencia del journal: mapa de calor de una versión de un proceso,
 * camino seguido por una instancia, instancias en espera por nodo y recuento por versión.
 * <p>
 * Devuelve identificadores y cifras, nunca textos: cada aplicación los presenta y traduce a su manera (por ejemplo con
 * {@link io.github.eldiegos.bpmncode.i18n.LabelResolver}). No depende de ninguna tecnología web ni de interfaz.
 */
@NamedInterface("statistics")
package io.github.eldiegos.bpmncode.statistics;

import org.springframework.modulith.NamedInterface;
