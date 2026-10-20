# Publicación en Maven Central

> 🇬🇧 [English version](../en/publishing.md)

`pom.xml` no hereda de ningún POM: importa los BOM de Spring Boot y Spring Modulith y lleva los metadatos que exige
Maven Central (licencia, `scm`, desarrollador). El perfil `release` añade:

- los jar de fuentes y de Javadoc;
- la firma GPG;
- la subida con `central-publishing-maven-plugin`.

## Preparación (una sola vez)

1. **Espacio de nombres.** Entra en <https://central.sonatype.com> con la cuenta de GitHub `eldiegos`. El espacio de
   nombres `io.github.eldiegos` queda verificado automáticamente.
2. **Token.** Genera un token de usuario en el portal (*View Account → Generate User Token*) y guárdalo en
   `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>usuario-del-token</username>
         <password>clave-del-token</password>
       </server>
     </servers>
   </settings>
   ```

3. **Clave GPG.** Crea una clave y publica su parte pública en un servidor de claves:

   ```bash
   gpg --gen-key
   gpg --list-keys                                   # anota el id de la clave
   gpg --keyserver keyserver.ubuntu.com --send-keys <ID>
   ```

## Cada versión

```bash
# 1. Fijar la versión de release (sin -SNAPSHOT)
./mvnw versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
git commit -am "release: 0.1.0" && git tag v0.1.0

# 2. Construir, firmar y subir; el despliegue queda validado y pendiente de publicar
./mvnw -Prelease clean deploy

# 3. Revisar en central.sonatype.com → Deployments y pulsar «Publish»

# 4. Abrir la siguiente versión de desarrollo
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: start 0.2.0-SNAPSHOT"
git push --follow-tags
```

El plugin está configurado con `autoPublish=false` porque **una versión publicada en Central no se puede borrar ni
sustituir**: el paso 3 es la última oportunidad de revisar los ficheros.

Actualiza también `version` en `CITATION.cff` y el ejemplo de dependencia del `README.md` (y del `README.en.md`).

## Probar sin subir

```bash
./mvnw -Prelease verify -Dgpg.skip
```

Genera en `target/` los cuatro jar (`bpmncode-<versión>.jar`, `-sources.jar`, `-javadoc.jar` y `-tests.jar`) sin
firmar ni subir nada. El jar principal lleva `LICENSE` y `NOTICE` en `META-INF/`. El jar de pruebas (clasificador
`tests`) se genera en todas las construcciones, no solo con `release`, y se publica junto a los demás: lleva
`ProcessInstanceStoreContract` para que las aplicaciones prueben sus almacenes (véase
[Almacenamiento](almacenamiento.md#probar-el-almacén)), y `ProcessAssertions` con los XSD de OMG en `/bpmn/xsd` para
que prueben sus diagramas y procesos (véase [Configuración](configuracion.md#processassertions)).
