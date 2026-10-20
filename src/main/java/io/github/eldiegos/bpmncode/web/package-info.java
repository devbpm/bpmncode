/**
 * Módulo web opcional: API de solo lectura y visor bpmn-js (diagramas, fichas de cada elemento, camino de una instancia y
 * mapa de calor de una versión), en el idioma de cada petición.
 * <p>
 * Está apagado por defecto: solo se activa en una aplicación web (servlet) con Spring MVC y
 * {@code pac.process.web.enabled=true}. No es una interfaz con nombre: la aplicación no usa sus clases, solo sus rutas.
 */
package io.github.eldiegos.bpmncode.web;
