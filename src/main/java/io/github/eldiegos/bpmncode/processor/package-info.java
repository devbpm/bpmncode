/**
 * Procesador de anotaciones del framework: hace fallar {@code javac} con las declaraciones que el lector del modelo
 * rechazaría al arrancar y que dependen solo del elemento anotado (p. ej. un {@code @TimerStartEvent} sin su
 * {@code @Scheduled}).
 * <p>
 * Lo usa el compilador, no la aplicación: no depende de Spring ni del resto del framework (reconoce las anotaciones por
 * su nombre) y no es una interfaz con nombre del módulo, así que ningún módulo de la aplicación puede usarlo. Se activa
 * en el {@code maven-compiler-plugin} de la aplicación con
 * {@code <annotationProcessors>io.github.eldiegos.bpmncode.processor.ProcessAnnotationProcessor</annotationProcessors>}.
 */
package io.github.eldiegos.bpmncode.processor;
