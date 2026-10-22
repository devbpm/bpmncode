# Calidad del código

> 🇬🇧 [English version](../en/quality.md)

Herramientas de calidad del **proyecto**, no de quien usa la librería: nada de esto llega al jar principal ni a sus
dependencias.

## Cobertura con JaCoCo

JaCoCo forma parte del build normal: `prepare-agent` instrumenta las pruebas y `report` genera el informe en la fase
`prepare-package`.

```bash
./mvnw verify
```

Deja en `target/site/jacoco/` el informe HTML (`index.html`) y `jacoco.xml`, que es el que lee SonarQube.

## Análisis con SonarQube

```bash
./mvnw -Psonar verify
```

El perfil `sonar` ejecuta con `maven-failsafe-plugin` la prueba `SonarQubeAnalysisIT`
(`src/test/java/io/github/eldiegos/bpmncode/quality`) después de compilar, probar y generar la cobertura. La prueba:

1. arranca con Testcontainers un SonarQube efímero (imagen de la propiedad `sonarqube.image`, por defecto
   `sonarqube:community`) y espera a que `/api/system/status` responda `UP`;
2. cambia la contraseña de `admin` y crea un token;
3. lanza `sonar-maven-plugin` sobre el proyecto ya compilado, con `target/site/jacoco/jacoco.xml` como cobertura;
4. espera a que el servidor procese el análisis y escribe el informe;
5. si se pide, deja el servidor abierto unos minutos (véase [Revisarlo en el navegador](#revisarlo-en-el-navegador));
6. elimina el contenedor.

Requisitos: Docker en marcha y unos 2-3 GB de memoria libres para el contenedor. Otra imagen:

```bash
./mvnw -Psonar verify -Dsonarqube.image=sonarqube:<etiqueta>
```

### Informe

En `target/sonar/`:

| Fichero | Contenido |
|---|---|
| `issues.txt` | puerta de calidad, métricas, número de incidencias y puntos de seguridad, y una línea por incidencia abierta (gravedad, tipo, regla, fichero:línea, mensaje) |
| `measures.json` | métricas: cobertura, bugs, vulnerabilidades, *code smells*, duplicación, líneas, calificaciones |
| `quality-gate.json` | estado de la puerta de calidad |
| `hotspots.json` | puntos de seguridad (*security hotspots*) |
| `scanner.log` | salida del escáner |
| `url.txt` | solo con `sonar.keepOpenMinutes` > 0: URL del panel del proyecto y usuario `admin` con su contraseña |

Las primeras líneas de `issues.txt` también se muestran en la consola.

### Revisarlo en el navegador

```bash
./mvnw -Psonar verify -Dsonar.keepOpenMinutes=30
```

La propiedad `sonar.keepOpenMinutes` (del `pom.xml`, por defecto `0`: el contenedor se cierra al terminar) deja el
SonarQube abierto N minutos después de escribir el informe. Escribe en `target/sonar/url.txt` la URL del panel
(`http://localhost:<puerto>/dashboard?id=bpmncode`, con el puerto que asigna Testcontainers) y el usuario `admin` con
la contraseña que fija la prueba, y lo imprime en el log:

```
SonarQube stays open for 30 min at http://localhost:<puerto>/dashboard?id=bpmncode (admin / <contraseña>)
```

Pasado ese tiempo el contenedor se elimina como siempre; mientras tanto la build espera.

### Cuándo falla

No falla por las incidencias: son el informe. Falla si el servidor no arranca (5 minutos de espera), si el escáner
termina con error (véase `scanner.log`) o si el análisis no se procesa en 5 minutos o no termina en `SUCCESS`.

### Ciclo de trabajo

Analizar, corregir las incidencias de `issues.txt` y volver a analizar. Cada ejecución parte de un servidor nuevo, sin
historial.

## Fuera de la librería

Testcontainers es dependencia `test` (no transitiva) y el paquete `io.github.eldiegos.bpmncode.quality` se excluye del
jar de pruebas que se publica (clasificador `tests`). El perfil `release` no ejecuta el análisis (véase
[Publicación](publicacion.md)).
