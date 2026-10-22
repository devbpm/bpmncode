package io.github.eldiegos.bpmncode.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

/**
 * Análisis de calidad del proyecto con SonarQube en un contenedor efímero: arranca el servidor, analiza el código ya
 * compilado con su cobertura de JaCoCo y deja el resultado en {@code target/sonar} para corregirlo y volver a analizar.
 * <p>
 * Solo se ejecuta con {@code ./mvnw -Psonar verify}; no forma parte de la librería ni del jar de pruebas que se publica.
 * El análisis no falla por las incidencias que encuentre (son el informe): falla si el servidor no arranca o el análisis
 * no termina.
 */
class SonarQubeAnalysisIT {

	private static final Path BASEDIR = Path.of(System.getProperty("project.basedir", ".")).toAbsolutePath();
	private static final Path REPORT = BASEDIR.resolve("target/sonar");
	private static final String IMAGE = System.getProperty("sonarqube.image", "sonarqube:community");
	private static final String PROJECT = System.getProperty("sonar.projectKey", "bpmncode");
	private static final String SCANNER = "org.sonarsource.scanner.maven:sonar-maven-plugin:"
			+ System.getProperty("sonar-maven-plugin.version", "5.1.0.4751") + ":sonar";
	private static final String PASSWORD = "Bpmncode-Analysis-2026!";
	private static final String METRICS = "coverage,line_coverage,branch_coverage,bugs,vulnerabilities,code_smells,"
			+ "security_hotspots,duplicated_lines_density,ncloc,sqale_rating,reliability_rating,security_rating";

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	@Test
	void analyzesTheProjectAndWritesTheReport() throws Exception {
		Files.createDirectories(REPORT);
		try (var sonar = new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(9000)
				.withEnv("SONAR_ES_BOOTSTRAP_CHECKS_DISABLE", "true")
				.waitingFor(Wait.forHttp("/api/system/status").forPort(9000)
						.forResponsePredicate(body -> body.contains("\"UP\"")).withStartupTimeout(Duration.ofMinutes(5)))) {
			sonar.start();
			var url = "http://" + sonar.getHost() + ":" + sonar.getMappedPort(9000);

			post(url, "/api/users/change_password", basic("admin", "admin"),
					Map.of("login", "admin", "previousPassword", "admin", "password", PASSWORD));
			var token = JsonPath.<String> read(post(url, "/api/user_tokens/generate", basic("admin", PASSWORD),
					Map.of("name", "bpmncode-" + Instant.now().toEpochMilli())), "$.token");
			var auth = basic(token, "");

			assertThat(scan(url, token)).as("scanner exit code (see target/sonar/scanner.log)").isZero();
			waitForAnalysis(url, auth);
			writeReport(url, auth);
			keepOpen(url);
		}
	}

	/**
	 * Deja el servidor abierto para revisar el análisis en el navegador si se pide con
	 * {@code -Dsonar.keepOpenMinutes=N}; después el contenedor se elimina como siempre.
	 */
	private static void keepOpen(String url) throws IOException, InterruptedException {
		var minutes = Long.getLong("sonar.keepOpenMinutes", 0);
		if (minutes <= 0) {
			return;
		}
		var dashboard = url + "/dashboard?id=" + encode(PROJECT);
		Files.writeString(REPORT.resolve("url.txt"), dashboard + System.lineSeparator() + "admin / " + PASSWORD
				+ System.lineSeparator());
		System.out.println("SonarQube stays open for " + minutes + " min at " + dashboard + " (admin / " + PASSWORD + ")");
		Thread.sleep(Duration.ofMinutes(minutes));
	}

	/** Ejecuta el escáner de Maven sobre el proyecto ya compilado y probado, con su informe de JaCoCo. */
	private int scan(String url, String token) throws IOException, InterruptedException {
		var windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
		// El wrapper por su ruta absoluta: cmd no siempre busca en el directorio de trabajo.
		var wrapper = BASEDIR.resolve(windows ? "mvnw.cmd" : "mvnw").toString();
		var command = new ArrayList<String>(windows ? List.of("cmd", "/c", wrapper) : List.of(wrapper));
		command.addAll(List.of("-B", SCANNER, "-Dsonar.host.url=" + url, "-Dsonar.token=" + token,
				"-Dsonar.projectKey=" + PROJECT, "-Dsonar.projectName=bpmncode",
				"-Dsonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml",
				// Su directorio de trabajo por defecto es target/sonar, que vacía al empezar: ahí va el informe.
				"-Dsonar.working.directory=target/sonar-scanner"));
		var process = new ProcessBuilder(command).directory(BASEDIR.toFile()).redirectErrorStream(true)
				.redirectOutput(REPORT.resolve("scanner.log").toFile()).start();
		return process.waitFor();
	}

	/** Espera a que el servidor procese el análisis enviado (la cola de tareas de cálculo). */
	private void waitForAnalysis(String url, String auth) throws IOException, InterruptedException {
		var deadline = Instant.now().plus(Duration.ofMinutes(5));
		while (Instant.now().isBefore(deadline)) {
			var task = get(url, "/api/ce/component?component=" + encode(PROJECT), auth);
			List<Object> queue = JsonPath.read(task, "$.queue");
			List<String> status = JsonPath.read(task, "$..current.status");
			if (queue.isEmpty() && !status.isEmpty()) {
				assertThat(status.getFirst()).as("analysis task").isEqualTo("SUCCESS");
				return;
			}
			Thread.sleep(2000);
		}
		throw new IllegalStateException("SonarQube did not process the analysis in time");
	}

	/** Deja en target/sonar las métricas, la puerta de calidad, las incidencias abiertas y los puntos de seguridad. */
	private void writeReport(String url, String auth) throws IOException, InterruptedException {
		var measures = get(url, "/api/measures/component?component=" + encode(PROJECT) + "&metricKeys=" + METRICS, auth);
		var gate = get(url, "/api/qualitygates/project_status?projectKey=" + encode(PROJECT), auth);
		var issues = new ArrayList<Map<String, Object>>();
		for (var page = 1;; page++) {
			var json = get(url, "/api/issues/search?componentKeys=" + encode(PROJECT)
					+ "&resolved=false&ps=500&p=" + page, auth);
			List<Map<String, Object>> found = JsonPath.read(json, "$.issues");
			issues.addAll(found);
			int total = JsonPath.read(json, "$.paging.total");
			if (issues.size() >= total || found.isEmpty()) {
				break;
			}
		}
		var hotspots = get(url, "/api/hotspots/search?project=" + encode(PROJECT) + "&ps=500", auth);
		Files.writeString(REPORT.resolve("measures.json"), measures);
		Files.writeString(REPORT.resolve("quality-gate.json"), gate);
		Files.writeString(REPORT.resolve("hotspots.json"), hotspots);

		var lines = new ArrayList<String>();
		lines.add("Quality gate: " + JsonPath.read(gate, "$.projectStatus.status"));
		List<Map<String, Object>> metrics = JsonPath.read(measures, "$.component.measures");
		metrics.forEach(metric -> lines.add(metric.get("metric") + " = " + metric.get("value")));
		lines.add("Open issues: " + issues.size());
		lines.add("Security hotspots: " + JsonPath.read(hotspots, "$.paging.total"));
		lines.add("");
		issues.stream().map(SonarQubeAnalysisIT::issueLine).sorted().forEach(lines::add);
		Files.write(REPORT.resolve("issues.txt"), lines);
		System.out.println(String.join(System.lineSeparator(), lines.subList(0, Math.min(lines.size(), 20))));
	}

	/** Una incidencia legible: gravedad, tipo, regla, fichero y línea, mensaje. */
	private static String issueLine(Map<String, Object> issue) {
		var component = String.valueOf(issue.get("component"));
		return String.join(" | ", String.valueOf(issue.get("severity")), String.valueOf(issue.get("type")),
				String.valueOf(issue.get("rule")), component.substring(component.indexOf(':') + 1) + ":"
						+ issue.getOrDefault("line", "-"), String.valueOf(issue.get("message")));
	}

	private String get(String url, String path, String auth) throws IOException, InterruptedException {
		var request = HttpRequest.newBuilder(URI.create(url + path)).header("Authorization", auth).GET().build();
		return send(request);
	}

	private String post(String url, String path, String auth, Map<String, String> form)
			throws IOException, InterruptedException {
		var body = form.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
				.collect(Collectors.joining("&"));
		var request = HttpRequest.newBuilder(URI.create(url + path)).header("Authorization", auth)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		return send(request);
	}

	private String send(HttpRequest request) throws IOException, InterruptedException {
		var response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 300) {
			throw new IllegalStateException(request.method() + " " + request.uri().getPath() + " answered "
					+ response.statusCode() + ": " + response.body());
		}
		return response.body();
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
