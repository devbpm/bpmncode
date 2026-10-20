/**
 * Almacén de instancias con JPA: {@link io.github.eldiegos.bpmncode.runtime.jpa.JpaProcessInstanceStore} y sus dos
 * entidades, sobre las mismas tablas que el almacén JDBC.
 * <p>
 * Solo se carga si la aplicación usa JPA; la autoconfiguración añade este paquete a la unidad de persistencia de la
 * aplicación. Las entidades son internas.
 */
package io.github.eldiegos.bpmncode.runtime.jpa;
