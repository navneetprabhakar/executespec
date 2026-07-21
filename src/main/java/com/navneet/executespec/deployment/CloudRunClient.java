package com.navneet.executespec.deployment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.testgen.BoundedProcess;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Google Cloud Run API client — deploys, deletes, and manages Cloud Run services via the {@code
 * gcloud} CLI.
 *
 * <p><b>Reliability:</b> All subprocess calls have timeouts:
 *
 * <ul>
 *   <li>{@code deploy/deployPreview}: 10 minutes
 *   <li>{@code deleteService/deleteImage/rollback}: 5 minutes
 *   <li>{@code listPreviewServices/ensurePublicAccess}: 2 minutes
 * </ul>
 *
 * Stdout and stderr are read in parallel (via {@link java.util.concurrent.CompletableFuture}) to
 * prevent pipe buffer deadlock. Health check HTTP client has 10s connect timeout and 15s
 * per-request timeout.
 */
@Service
@Slf4j
public class CloudRunClient {

  private final DeploymentProperties props;
  private final ObjectMapper objectMapper;

  /** Read-only enumeration of the preview services this client deployed. */
  private final CloudRunServiceInventory inventory;

  private final HttpClient httpClient =
      HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  public CloudRunClient(DeploymentProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    this.inventory = new CloudRunServiceInventory(props, objectMapper);
  }

  private static final int MAX_DEPLOY_RETRIES = 3;
  private static final long[] RETRY_BACKOFF_MS = {2000, 5000, 10000};

  /** Transient HTTP status codes that warrant a retry. */
  private static boolean isTransient(String errorMsg) {
    return errorMsg.contains("429")
        || errorMsg.contains("500")
        || errorMsg.contains("502")
        || errorMsg.contains("503")
        || errorMsg.contains("504")
        || errorMsg.contains("UNAVAILABLE")
        || errorMsg.contains("INTERNAL");
  }

  /**
   * Deploy image to Cloud Run with retry for transient failures. Returns the service URL and
   * revision name.
   */
  public DeployResult deploy(String serviceName, String imageUri)
      throws IOException, InterruptedException {
    for (int attempt = 0; attempt < MAX_DEPLOY_RETRIES; attempt++) {
      try {
        return deployOnce(serviceName, imageUri);
      } catch (IOException e) {
        if (attempt == MAX_DEPLOY_RETRIES - 1 || !isTransient(e.getMessage())) throw e;
        log.warn(
            "Transient Cloud Run failure (attempt {}/{}): {}",
            attempt + 1,
            MAX_DEPLOY_RETRIES,
            e.getMessage());
        Thread.sleep(RETRY_BACKOFF_MS[attempt]);
      }
    }
    throw new IllegalStateException("Unreachable");
  }

  private DeployResult deployOnce(String serviceName, String imageUri)
      throws IOException, InterruptedException {

    List<String> cmd =
        new ArrayList<>(
            List.of(
                "gcloud",
                "run",
                "deploy",
                serviceName,
                "--image",
                imageUri,
                "--project",
                props.gcpProject(),
                "--region",
                props.gcpRegion(),
                "--cpu",
                props.cloudRun().cpu(),
                "--memory",
                props.cloudRun().memory(),
                "--max-instances",
                String.valueOf(props.cloudRun().maxInstances()),
                "--port",
                "8080",
                "--cpu-boost",
                "--quiet",
                "--format",
                "json"));
    if (props.cloudRun().allowUnauthenticated()) {
      cmd.add("--allow-unauthenticated");
    }
    applyRuntimeServiceAccount(cmd);

    // RR-999: drain stdout and stderr on separate threads (kept apart so the JSON on stdout is not
    // polluted by gcloud's progress text on stderr) with the deadline started BEFORE the reads.
    // Reading stdout to EOF on the calling thread first let a hung gcloud that kept its pipe open
    // defeat the 10-minute timeout entirely.
    GcloudResult res = runGcloudSeparate(new ProcessBuilder(cmd), Duration.ofMinutes(10));
    if (!res.finished()) {
      throw new IOException("Cloud Run deploy timed out after 10 minutes");
    }
    String stdout = res.stdout();
    String stderr = res.stderr();
    int exit = res.exitCode();

    String combined = (stdout + "\n" + stderr).trim();
    if (exit != 0) {
      if (combined.contains("UNAUTHENTICATED") || combined.contains("token has been expired")) {
        throw new IOException("GCP auth expired. Run: gcloud auth login");
      }
      throw new IOException("Cloud Run deploy failed: " + combined);
    }

    // Extract JSON from stdout — gcloud may prefix progress text before the JSON object
    String jsonStr = stdout;
    int jsonStart = jsonStr.indexOf('{');
    if (jsonStart > 0) {
      jsonStr = jsonStr.substring(jsonStart);
    }
    if (jsonStr.isEmpty() || !jsonStr.startsWith("{")) {
      log.warn("No JSON output from gcloud run deploy. stdout={}, stderr={}", stdout, stderr);
      return new DeployResult(null, null);
    }

    JsonNode root = objectMapper.readTree(jsonStr);
    String serviceUrl = root.path("status").path("url").asText(null);
    String revisionName = root.path("status").path("latestReadyRevisionName").asText(null);

    if (serviceUrl == null) {
      serviceUrl = root.path("uri").asText(null);
    }

    if (serviceUrl == null || serviceUrl.isBlank()) {
      throw new IOException(
          "Cloud Run returned no service URL for "
              + serviceName
              + ". Response: "
              + stdout.substring(0, Math.min(stdout.length(), 500)));
    }

    log.info("Cloud Run deployed: {} → {} (revision: {})", serviceName, serviceUrl, revisionName);
    return new DeployResult(serviceUrl, revisionName);
  }

  /**
   * Deploy a preview image to Cloud Run with ephemeral settings. Uses min-instances=0 for cost
   * savings, labels for cleanup identification.
   */
  public DeployResult deployPreview(
      String serviceName, String imageUri, String cpu, String memory, Map<String, String> envVars)
      throws IOException, InterruptedException {
    return deployPreview(serviceName, imageUri, cpu, memory, envVars, 8080);
  }

  public DeployResult deployPreview(
      String serviceName,
      String imageUri,
      String cpu,
      String memory,
      Map<String, String> envVars,
      int containerPort)
      throws IOException, InterruptedException {

    log.info("Preview deploy: service={}, port={}, image={}", serviceName, containerPort, imageUri);

    List<String> cmd =
        new ArrayList<>(
            List.of(
                "gcloud",
                "run",
                "deploy",
                serviceName,
                "--image",
                imageUri,
                "--project",
                props.gcpProject(),
                "--region",
                props.gcpRegion(),
                "--cpu",
                cpu,
                "--memory",
                memory,
                "--min-instances",
                "0",
                "--max-instances",
                "1",
                "--port",
                String.valueOf(containerPort),
                "--cpu-boost",
                "--ingress",
                "all",
                "--allow-unauthenticated",
                "--no-cpu-throttling",
                "--timeout",
                "600",
                // Startup probe: give the container up to ~5 minutes (period 10s × threshold 30) to
                // start.
                // initialDelay=15s gives Vite/Spring Boot time to compile before first probe.
                "--startup-probe",
                "httpGet.path=/,httpGet.port="
                    + containerPort
                    + ",initialDelaySeconds=15,periodSeconds=10,timeoutSeconds=5,failureThreshold=30",
                "--labels",
                "managed-by=executespec-preview"
                    + ",deployed-at="
                    + java.time.Instant.now().getEpochSecond()
                    + ",ttl-minutes="
                    + 30,
                "--quiet",
                "--format",
                "json"));
    applyRuntimeServiceAccount(cmd);

    // Inject env vars via YAML file to avoid shell escaping issues with special chars
    Path envFile = null;
    if (envVars != null && !envVars.isEmpty()) {
      envVars = new java.util.HashMap<>(envVars); // defensive copy — caller may pass immutable map
      envVars.remove("PORT"); // Cloud Run sets this automatically via --port flag
      if (!envVars.isEmpty()) {
        StringBuilder yaml = new StringBuilder();
        envVars.forEach(
            (k, v) -> yaml.append(k).append(": '").append(v.replace("'", "''")).append("'\n"));
        envFile = Files.createTempFile("cr-env-", ".yaml");
        Files.writeString(envFile, yaml.toString());
        cmd.add("--env-vars-file");
        cmd.add(envFile.toString());
      }
    }

    try {
      // RR-999: drain stdout and stderr on separate threads (gcloud writes progress text to stderr
      // even with --format=json --quiet, so they must stay apart) with the deadline started BEFORE
      // the reads. The old shape read stdout to EOF first, so a hung gcloud could never time out.
      GcloudResult res = runGcloudSeparate(new ProcessBuilder(cmd), Duration.ofMinutes(10));
      if (!res.finished()) {
        throw new IOException("Cloud Run preview deploy timed out after 10 minutes");
      }
      String stdout = res.stdout();
      String stderr = res.stderr();
      int exit = res.exitCode();

      String combined = (stdout + "\n" + stderr).trim();
      if (exit != 0) {
        if (combined.contains("UNAUTHENTICATED") || combined.contains("token has been expired")) {
          throw new IOException("GCP auth expired. Run: gcloud auth login");
        }
        throw new IOException("Cloud Run preview deploy failed: " + combined);
      }

      // Extract JSON from stdout — gcloud may prefix progress text before the JSON object
      String jsonStr = stdout;
      int jsonStart = jsonStr.indexOf('{');
      if (jsonStart > 0) {
        jsonStr = jsonStr.substring(jsonStart);
      }
      if (jsonStr.isEmpty() || !jsonStr.startsWith("{")) {
        // Fallback: no JSON output, but deploy succeeded — try to find URL in text
        log.warn("No JSON output from gcloud run deploy. stdout={}, stderr={}", stdout, stderr);
        return new DeployResult(null, null);
      }

      JsonNode root = objectMapper.readTree(jsonStr);
      String serviceUrl = root.path("status").path("url").asText(null);
      if (serviceUrl == null) {
        serviceUrl = root.path("uri").asText(null);
      }

      log.info("Cloud Run preview deployed: {} → {}", serviceName, serviceUrl);

      // Ensure public access — --allow-unauthenticated may silently fail. RR-1001: surface a failed
      // binding rather than swallowing it. We do not hard-fail here (the deploy already passed
      // --allow-unauthenticated); the post-deploy 2xx health check is the authoritative
      // reachability
      // gate and will fail the preview if the service is genuinely inaccessible.
      if (!ensurePublicAccess(serviceName)) {
        log.warn(
            "Cloud Run preview {} public-access IAM binding could not be confirmed — the service"
                + " may be unreachable; the health check will decide.",
            serviceName);
      }

      return new DeployResult(serviceUrl, null);
    } finally {
      if (envFile != null) {
        try {
          Files.deleteIfExists(envFile);
        } catch (Exception ignored) {
        }
      }
    }
  }

  /**
   * Explicitly set allUsers → roles/run.invoker on the service. This is a fallback if
   * --allow-unauthenticated failed during deploy.
   *
   * <p>RR-1001: returns whether the IAM binding was actually confirmed. The prior {@code void}
   * signature swallowed every failure as a WARN, so the caller could not distinguish a public,
   * reachable service from one whose public-access binding never took. The reachability gate is the
   * post-deploy 2xx health check; this signal lets the deploy path surface a binding failure.
   */
  private boolean ensurePublicAccess(String serviceName) {
    try {
      ProcessBuilder pb =
          new ProcessBuilder(
                  "gcloud",
                  "run",
                  "services",
                  "add-iam-policy-binding",
                  serviceName,
                  "--member=allUsers",
                  "--role=roles/run.invoker",
                  "--project",
                  props.gcpProject(),
                  "--region",
                  props.gcpRegion(),
                  "--quiet")
              .redirectErrorStream(true);
      BoundedProcess.Outcome outcome = runBounded(pb, Duration.ofMinutes(2));
      if (!outcome.finished()) {
        log.warn("Setting public access timed out for {}", serviceName);
        return false;
      }
      if (outcome.exitCode() != 0) {
        log.warn(
            "Failed to set public access (allUsers→run.invoker) on {}: {}",
            serviceName,
            outcome.output());
        return false;
      }
      log.info("Public access set on Cloud Run service: {}", serviceName);
      return true;
    } catch (Exception e) {
      log.warn("Failed to set public access on {}: {}", serviceName, e.getMessage());
      return false;
    }
  }

  /**
   * List all Cloud Run services with the executespec-preview label. Used by orphan cleanup to find
   * leftover preview services.
   */
  public List<String> listPreviewServices() throws IOException, InterruptedException {
    return inventory.listPreviewServices();
  }

  /**
   * List preview services with their labels (JSON format) for TTL-based orphan cleanup. Returns
   * service name → labels map.
   */
  public Map<String, Map<String, String>> listPreviewServicesWithLabels()
      throws IOException, InterruptedException {
    return inventory.listPreviewServicesWithLabels();
  }

  /** Rollback traffic to a specific revision — instant, no rebuild. */
  public void rollbackToRevision(String serviceName, String revisionName)
      throws IOException, InterruptedException {

    ProcessBuilder pb =
        new ProcessBuilder(
                "gcloud",
                "run",
                "services",
                "update-traffic",
                serviceName,
                "--to-revisions",
                revisionName + "=100",
                "--project",
                props.gcpProject(),
                "--region",
                props.gcpRegion(),
                "--quiet")
            .redirectErrorStream(true);

    BoundedProcess.Outcome outcome = runBounded(pb, Duration.ofMinutes(5));
    if (!outcome.finished()) {
      throw new IOException("Rollback timed out after 5 minutes for " + serviceName);
    }
    if (outcome.exitCode() != 0) {
      throw new IOException("Rollback failed: " + outcome.output());
    }
    log.info("Rolled back {} to revision {}", serviceName, revisionName);
  }

  /** Delete a Cloud Run service entirely (TTL cleanup). */
  public void deleteService(String serviceName) throws IOException, InterruptedException {
    exec(
        "gcloud",
        "run",
        "services",
        "delete",
        serviceName,
        "--project",
        props.gcpProject(),
        "--region",
        props.gcpRegion(),
        "--quiet");
    log.info("Deleted Cloud Run service: {}", serviceName);
  }

  /** Delete image from Artifact Registry. */
  public void deleteImage(String imageUri) throws IOException, InterruptedException {
    exec("gcloud", "artifacts", "docker", "images", "delete", imageUri, "--quiet", "--delete-tags");
    log.info("Deleted AR image: {}", imageUri);
  }

  /**
   * Post-deploy health check: GET service URL, require a real 2xx. Retries 3 times with 5-second
   * delay for cold-start tolerance.
   *
   * <p>RR-1001: the old gate accepted any status below 500, so a service returning 401/403/404 —
   * broken auth/IAM, a missing app route, or an unpublished revision — read as "healthy" and the
   * preview/deployment was marked RUNNING/LIVE while genuinely inaccessible. The client follows
   * redirects (NORMAL), so a legitimate 3xx→2xx flow still resolves to 2xx; a terminal non-2xx now
   * fails the check.
   */
  public boolean healthCheck(String serviceUrl) {
    if (serviceUrl == null || serviceUrl.isBlank()) {
      log.error("Health check called with null/empty service URL");
      return false;
    }
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        HttpRequest req =
            HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl))
                .timeout(java.time.Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());

        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
          log.info("Health check passed: {} → {}", serviceUrl, resp.statusCode());
          return true;
        }
        log.warn(
            "Health check attempt {}: {} → {} (not 2xx — treated as unhealthy)",
            attempt,
            serviceUrl,
            resp.statusCode());
      } catch (Exception e) {
        log.warn("Health check attempt {}: {} → {}", attempt, serviceUrl, e.getMessage());
      }

      if (attempt < 3) {
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }

  private void exec(String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
    BoundedProcess.Outcome outcome = runBounded(pb, Duration.ofMinutes(5));
    if (!outcome.finished()) {
      throw new IOException("Command timed out after 5 minutes: " + String.join(" ", cmd));
    }
    if (outcome.exitCode() != 0) {
      throw new IOException(
          "Command failed (exit "
              + outcome.exitCode()
              + "): "
              + String.join(" ", cmd)
              + "\n"
              + outcome.output());
    }
  }

  /**
   * RR-999: run a gcloud command whose stderr is folded into stdout (combined output is fine) under
   * a reachable wall-clock deadline via {@link BoundedProcess}. {@code pb} must already have {@code
   * redirectErrorStream(true)}.
   */
  private static BoundedProcess.Outcome runBounded(ProcessBuilder pb, Duration timeout)
      throws IOException {
    try {
      return BoundedProcess.run(pb, timeout);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** stdout and stderr kept apart, both bounded, for the JSON-parsing deploy paths (RR-999). */
  /** Package-private for the hung-child regression test. */
  record GcloudResult(boolean finished, int exitCode, String stdout, String stderr) {}

  /** Per-stream capture ceiling so a runaway gcloud cannot exhaust the heap. */
  private static final int MAX_GCLOUD_STREAM_CHARS = 1_000_000;

  /**
   * RR-999: drain stdout and stderr on separate virtual threads (so the JSON on stdout stays clean
   * of gcloud's progress text on stderr) with the deadline started BEFORE the reads. The whole
   * process tree is destroyed on expiry. Reading either stream to EOF on the calling thread first —
   * the shape this replaces — made the timeout unreachable for a child that keeps its pipe open.
   */
  static GcloudResult runGcloudSeparate(ProcessBuilder pb, Duration timeout)
      throws IOException, InterruptedException {
    Process process = pb.start();
    StringBuilder out = new StringBuilder();
    StringBuilder err = new StringBuilder();
    Thread outDrain = startDrain(process.getInputStream(), out, "cloudrun-out");
    Thread errDrain = startDrain(process.getErrorStream(), err, "cloudrun-err");

    boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!finished) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    joinDrain(outDrain);
    joinDrain(errDrain);
    return new GcloudResult(
        finished,
        finished ? process.exitValue() : -1,
        out.toString().trim(),
        err.toString().trim());
  }

  private static Thread startDrain(InputStream stream, StringBuilder sink, String name) {
    return Thread.ofVirtual()
        .name(name)
        .start(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                  synchronized (sink) {
                    if (sink.length() + line.length() + 1 <= MAX_GCLOUD_STREAM_CHARS) {
                      sink.append(line).append('\n');
                    }
                    // Keep draining past the cap so the child is never blocked on a full pipe.
                  }
                }
              } catch (Exception ended) {
                log.debug("gcloud stream drain ended: {}", ended.getMessage());
              }
            });
  }

  private static void joinDrain(Thread drain) {
    try {
      drain.join(Duration.ofSeconds(5));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  public record DeployResult(String serviceUrl, String revisionName) {}

  /**
   * Pins the runtime identity of a generated Cloud Run service (RR-998).
   *
   * <p>Without {@code --service-account} the service runs as the project's default compute
   * identity. Generated customer code can read a token for that identity from the metadata server,
   * and previews are additionally deployed {@code --allow-unauthenticated}, so anyone reaching the
   * preview reaches code holding it. Fails closed by default: an unconfigured runtime SA aborts the
   * deploy rather than quietly granting the default identity.
   *
   * <p>Configure {@code agent.deployment.cloud-run.service-account} with a dedicated,
   * minimal-permission account. Setting {@code require-service-account: false} restores the old
   * behaviour and is an explicit operator decision.
   */
  private void applyRuntimeServiceAccount(List<String> cmd) {
    String sa = props.cloudRun().serviceAccount();
    if (sa != null && !sa.isBlank()) {
      cmd.add("--service-account");
      cmd.add(sa.trim());
      return;
    }
    if (props.cloudRun().serviceAccountRequired()) {
      throw new IllegalStateException(
          "Refusing to deploy without a dedicated runtime service account: set"
              + " agent.deployment.cloud-run.service-account (or explicitly set"
              + " require-service-account: false to accept the project default identity).");
    }
    log.warn(
        "Cloud Run deploy has no runtime service account configured — the service will inherit the"
            + " project default compute identity (agent.deployment.cloud-run.service-account)");
  }
}
