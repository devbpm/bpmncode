# Publishing to Maven Central

> 🇪🇸 [Versión en español](../es/publicacion.md)

`pom.xml` does not inherit from any POM: it imports the Spring Boot and Spring Modulith BOMs and carries the metadata
Maven Central requires (license, `scm` and `issueManagement` of the repository <https://github.com/devbpm/bpmncode>,
developer). The `release` profile adds:

- the sources and Javadoc jars;
- the GPG signature;
- the upload with `central-publishing-maven-plugin`.

## Preparation (once)

1. **Namespace.** Sign in at <https://central.sonatype.com> with the GitHub account `eldiegos`. The namespace
   `io.github.eldiegos` is verified automatically. The `groupId` does not depend on where the repository lives: it
   is still `io.github.eldiegos` although the code is in the `devbpm` organisation.
2. **Token.** Generate a user token in the portal (*View Account → Generate User Token*) and store it in
   `~/.m2/settings.xml`:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>token-username</username>
         <password>token-password</password>
       </server>
     </servers>
   </settings>
   ```

3. **GPG key.** Create a key and publish its public part to a key server:

   ```bash
   gpg --gen-key
   gpg --list-keys                                   # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <ID>
   ```

## Each version

```bash
# 1. Set the release version (no -SNAPSHOT)
./mvnw versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
git commit -am "release: 0.1.0" && git tag v0.1.0

# 2. Build, sign and upload; the deployment is left validated and pending publication
./mvnw -Prelease clean deploy

# 3. Review it at central.sonatype.com → Deployments and click "Publish"

# 4. Open the next development version
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
git commit -am "chore: start 0.2.0-SNAPSHOT"
git push --follow-tags
```

The plugin is configured with `autoPublish=false` because **a version published to Central cannot be deleted or
replaced**: step 3 is the last chance to review the files.

Also update `version` in `CITATION.cff` and the dependency example in `README.md` (and `README.en.md`).

## Testing without uploading

```bash
./mvnw -Prelease verify -Dgpg.skip
```

It generates in `target/` the four jars (`bpmncode-<version>.jar`, `-sources.jar`, `-javadoc.jar` and `-tests.jar`)
without signing or uploading anything. The main jar carries `LICENSE` and `NOTICE` in `META-INF/`. The test jar
(classifier `tests`) is built on every build, not only with `release`, and is published with the others: it carries
`ProcessInstanceStoreContract` so applications can test their stores (see
[Storage](storage.md#testing-the-store)), and `ProcessAssertions` with the OMG XSDs under `/bpmn/xsd` so they can test
their diagrams and processes (see [Configuration](configuration.md#processassertions)). The
`io.github.eldiegos.bpmncode.quality` package is excluded from the test jar, and the `release` profile does not run the
SonarQube analysis: it is run separately with `./mvnw -Psonar verify` (see [Code quality](quality.md)).
