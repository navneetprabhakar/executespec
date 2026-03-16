package com.navneet.executespec.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class CloudRunClient {

    private final DeploymentProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public CloudRunClient(DeploymentProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Deploy image to Cloud Run. Creates or updates a service.
     * Returns the service URL and revision name.
     */
    public DeployResult deploy(String serviceName, String imageUri)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
            "gcloud", "run", "deploy", serviceName,
            "--image", imageUri,
            "--project", props.gcpProject(),
            "--region", props.gcpRegion(),
            "--cpu", props.cloudRun().cpu(),
            "--memory", props.cloudRun().memory(),
            "--max-instances", String.valueOf(props.cloudRun().maxInstances()),
            "--port", "8080",
            "--cpu-boost",
            "--quiet",
            "--format", "json"
        ));
        if (props.cloudRun().allowUnauthenticated()) {
            cmd.add("--allow-unauthenticated");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exit = process.waitFor();

        if (exit != 0) {
            if (output.contains("UNAUTHENTICATED") || output.contains("token has been expired")) {
                throw new IOException("GCP auth expired. Run: gcloud auth login");
            }
            throw new IOException("Cloud Run deploy failed: " + output);
        }

        JsonNode root = objectMapper.readTree(output);
        String serviceUrl = root.path("status").path("url").asText(null);
        String revisionName = root.path("status").path("latestReadyRevisionName").asText(null);

        if (serviceUrl == null) {
            serviceUrl = root.path("uri").asText(null);
        }

        log.info("Cloud Run deployed: {} → {} (revision: {})", serviceName, serviceUrl, revisionName);
        return new DeployResult(serviceUrl, revisionName);
    }

    /**
     * Deploy a preview image to Cloud Run with ephemeral settings.
     * Uses min-instances=0 for cost savings, labels for cleanup identification.
     */
    public DeployResult deployPreview(String serviceName, String imageUri, String cpu, String memory,
                                       Map<String, String> envVars)
            throws IOException, InterruptedException {

        List<String> cmd = new ArrayList<>(List.of(
            "gcloud", "run", "deploy", serviceName,
            "--image", imageUri,
            "--project", props.gcpProject(),
            "--region", props.gcpRegion(),
            "--cpu", cpu,
            "--memory", memory,
            "--min-instances", "0",
            "--max-instances", "1",
            "--port", "8080",
            "--cpu-boost",
            "--allow-unauthenticated",
            "--no-cpu-throttling",
            "--labels", "managed-by=executespec-preview",
            "--quiet",
            "--format", "json"
        ));

        // Inject env vars
        if (envVars != null && !envVars.isEmpty()) {
            StringBuilder envFlag = new StringBuilder();
            envVars.forEach((k, v) -> {
                if (!envFlag.isEmpty()) envFlag.append(",");
                envFlag.append(k).append("=").append(v);
            });
            cmd.add("--set-env-vars");
            cmd.add(envFlag.toString());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exit = process.waitFor();

        if (exit != 0) {
            if (output.contains("UNAUTHENTICATED") || output.contains("token has been expired")) {
                throw new IOException("GCP auth expired. Run: gcloud auth login");
            }
            throw new IOException("Cloud Run preview deploy failed: " + output);
        }

        JsonNode root = objectMapper.readTree(output);
        String serviceUrl = root.path("status").path("url").asText(null);
        if (serviceUrl == null) {
            serviceUrl = root.path("uri").asText(null);
        }

        log.info("Cloud Run preview deployed: {} → {}", serviceName, serviceUrl);
        return new DeployResult(serviceUrl, null);
    }

    /**
     * List all Cloud Run services with the executespec-preview label.
     * Used by orphan cleanup to find leftover preview services.
     */
    public List<String> listPreviewServices() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "gcloud", "run", "services", "list",
            "--project", props.gcpProject(),
            "--region", props.gcpRegion(),
            "--filter", "metadata.labels.managed-by=executespec-preview",
            "--format", "value(metadata.name)",
            "--quiet"
        ).redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exit = process.waitFor();

        if (exit != 0) {
            log.warn("Failed to list preview services: {}", output);
            return List.of();
        }

        return output.isBlank() ? List.of() : List.of(output.split("\n"));
    }

    /**
     * Rollback traffic to a specific revision — instant, no rebuild.
     */
    public void rollbackToRevision(String serviceName, String revisionName)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
            "gcloud", "run", "services", "update-traffic", serviceName,
            "--to-revisions", revisionName + "=100",
            "--project", props.gcpProject(),
            "--region", props.gcpRegion(),
            "--quiet"
        ).redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        int exit = process.waitFor();

        if (exit != 0) {
            throw new IOException("Rollback failed: " + output);
        }
        log.info("Rolled back {} to revision {}", serviceName, revisionName);
    }

    /** Delete a Cloud Run service entirely (TTL cleanup). */
    public void deleteService(String serviceName) throws IOException, InterruptedException {
        exec("gcloud", "run", "services", "delete", serviceName,
            "--project", props.gcpProject(), "--region", props.gcpRegion(), "--quiet");
        log.info("Deleted Cloud Run service: {}", serviceName);
    }

    /** Delete image from Artifact Registry. */
    public void deleteImage(String imageUri) throws IOException, InterruptedException {
        exec("gcloud", "artifacts", "docker", "images", "delete", imageUri,
            "--quiet", "--delete-tags");
        log.info("Deleted AR image: {}", imageUri);
    }

    /**
     * Post-deploy health check: GET service URL, expect status < 500.
     * Retries 3 times with 5-second delay for cold-start tolerance.
     */
    public boolean healthCheck(String serviceUrl) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl))
                    .GET()
                    .build();
                HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());

                if (resp.statusCode() < 500) {
                    log.info("Health check passed: {} → {}", serviceUrl, resp.statusCode());
                    return true;
                }
                log.warn("Health check attempt {}: {} → {}", attempt, serviceUrl, resp.statusCode());
            } catch (Exception e) {
                log.warn("Health check attempt {}: {} → {}", attempt, serviceUrl, e.getMessage());
            }

            if (attempt < 3) {
                try { Thread.sleep(5000); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private void exec(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed (exit " + exit + "): " + String.join(" ", cmd));
        }
    }

    public record DeployResult(String serviceUrl, String revisionName) {}
}
