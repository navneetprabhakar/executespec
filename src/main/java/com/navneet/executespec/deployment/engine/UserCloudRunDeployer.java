package com.navneet.executespec.deployment.engine;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.run.v2.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * Deploys containers to a <em>user's</em> Cloud Run project using their stored
 * GCP credentials (not the system ADC).
 * <p>
 * Uses the Cloud Run Admin v2 Java client library for programmatic deployment
 * instead of the gcloud CLI, ensuring full credential isolation.
 */
@Service
@Slf4j
public class UserCloudRunDeployer {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Deploy (create or update) a Cloud Run service in the user's project.
     */
    public DeployResult deploy(GoogleCredentials credentials, String gcpProject,
                                String region, String serviceName, String imageUri,
                                int port, Map<String, String> envVars,
                                boolean allowUnauth) throws IOException {

        ServicesSettings settings = ServicesSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        try (ServicesClient client = ServicesClient.create(settings)) {
            String parent = LocationName.of(gcpProject, region).toString();
            String fullName = ServiceName.of(gcpProject, region, serviceName).toString();

            // Build container spec
            Container.Builder container = Container.newBuilder()
                    .setImage(imageUri)
                    .addPorts(ContainerPort.newBuilder().setContainerPort(port).build());

            // Add env vars
            if (envVars != null) {
                envVars.forEach((k, v) -> container.addEnv(
                        EnvVar.newBuilder().setName(k).setValue(v).build()));
            }

            // Build revision template
            RevisionTemplate revisionTemplate = RevisionTemplate.newBuilder()
                    .addContainers(container.build())
                    .setMaxInstanceRequestConcurrency(80)
                    .setScaling(RevisionScaling.newBuilder()
                            .setMinInstanceCount(0)
                            .setMaxInstanceCount(3)
                            .build())
                    .build();

            // Check if service exists
            boolean exists = serviceExists(client, fullName);

            com.google.cloud.run.v2.Service resultService;
            if (exists) {
                // Update existing
                com.google.cloud.run.v2.Service existing = client.getService(fullName);
                com.google.cloud.run.v2.Service updated = existing.toBuilder()
                        .setTemplate(revisionTemplate)
                        .build();
                var op = client.updateServiceAsync(updated);
                resultService = op.get();
                log.info("Cloud Run service updated: {}", serviceName);
            } else {
                // Create new
                com.google.cloud.run.v2.Service newService = com.google.cloud.run.v2.Service.newBuilder()
                        .setTemplate(revisionTemplate)
                        .setIngress(IngressTraffic.INGRESS_TRAFFIC_ALL)
                        .putLabels("managed-by", "executespec-deploy")
                        .build();
                var op = client.createServiceAsync(
                        CreateServiceRequest.newBuilder()
                                .setParent(parent)
                                .setServiceId(serviceName)
                                .setService(newService)
                                .build());
                resultService = op.get();
                log.info("Cloud Run service created: {}", serviceName);
            }

            // Set IAM policy for unauthenticated access if requested
            if (allowUnauth) {
                setPublicAccess(client, fullName);
            }

            String serviceUrl = resultService.getUri();
            String latestRevision = resultService.getLatestReadyRevision();
            // Extract just the revision name from full resource name
            String revisionName = latestRevision != null && latestRevision.contains("/")
                    ? latestRevision.substring(latestRevision.lastIndexOf('/') + 1)
                    : latestRevision;

            log.info("Deploy complete: {} → {} (revision: {})", serviceName, serviceUrl, revisionName);
            return new DeployResult(serviceUrl, revisionName);

        } catch (Exception e) {
            if (e instanceof IOException ioe) throw ioe;
            throw new IOException("Cloud Run deploy failed: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a Cloud Run service from the user's project.
     */
    public void deleteService(GoogleCredentials credentials, String gcpProject,
                               String region, String serviceName) throws IOException {
        ServicesSettings settings = ServicesSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        try (ServicesClient client = ServicesClient.create(settings)) {
            String fullName = ServiceName.of(gcpProject, region, serviceName).toString();
            var op = client.deleteServiceAsync(fullName);
            op.get();
            log.info("Deleted Cloud Run service: {}", serviceName);
        } catch (Exception e) {
            throw new IOException("Failed to delete Cloud Run service: " + e.getMessage(), e);
        }
    }

    /**
     * Route 100% traffic to a specific revision — instant rollback, no rebuild.
     */
    public void rollbackToRevision(GoogleCredentials credentials, String gcpProject,
                                    String region, String serviceName,
                                    String revisionName) throws IOException {
        ServicesSettings settings = ServicesSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build();

        try (ServicesClient client = ServicesClient.create(settings)) {
            String fullName = ServiceName.of(gcpProject, region, serviceName).toString();
            com.google.cloud.run.v2.Service service = client.getService(fullName);

            com.google.cloud.run.v2.Service updated = service.toBuilder()
                    .clearTraffic()
                    .addTraffic(TrafficTarget.newBuilder()
                            .setRevision(revisionName)
                            .setPercent(100)
                            .setType(TrafficTargetAllocationType.TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION)
                            .build())
                    .build();

            var op = client.updateServiceAsync(updated);
            op.get();
            log.info("Rolled back {} to revision {}", serviceName, revisionName);
        } catch (Exception e) {
            throw new IOException("Rollback failed: " + e.getMessage(), e);
        }
    }

    /**
     * Health check: GET service URL, expect status < 500.
     * Retries 5 times with exponential backoff for cold-start tolerance.
     */
    public boolean healthCheck(String serviceUrl, String healthCheckPath) {
        String url = serviceUrl;
        if (healthCheckPath != null && !healthCheckPath.isBlank()) {
            url = serviceUrl.endsWith("/") ? serviceUrl + healthCheckPath.substring(1)
                    : serviceUrl + healthCheckPath;
        }

        int[] delays = {5, 10, 15, 20, 30};
        for (int attempt = 0; attempt < delays.length; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());

                if (resp.statusCode() < 500) {
                    log.info("Health check passed: {} → {}", url, resp.statusCode());
                    return true;
                }
                log.warn("Health check attempt {}: {} → {}", attempt + 1, url, resp.statusCode());
            } catch (Exception e) {
                log.warn("Health check attempt {}: {} → {}", attempt + 1, url, e.getMessage());
            }

            if (attempt < delays.length - 1) {
                try {
                    Thread.sleep(delays[attempt] * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean serviceExists(ServicesClient client, String fullName) {
        try {
            client.getService(fullName);
            return true;
        } catch (com.google.api.gax.rpc.NotFoundException e) {
            return false;
        }
    }

    /**
     * Sets allUsers → roles/run.invoker on the service for public access.
     */
    private void setPublicAccess(ServicesClient client, String resourceName) {
        try {
            com.google.iam.v1.Policy policy = client.getIamPolicy(
                    com.google.iam.v1.GetIamPolicyRequest.newBuilder()
                            .setResource(resourceName)
                            .build());

            // Check if allUsers binding already exists
            boolean hasPublicBinding = policy.getBindingsList().stream()
                    .anyMatch(b -> b.getRole().equals("roles/run.invoker")
                            && b.getMembersList().contains("allUsers"));

            if (!hasPublicBinding) {
                com.google.iam.v1.Binding publicBinding = com.google.iam.v1.Binding.newBuilder()
                        .setRole("roles/run.invoker")
                        .addMembers("allUsers")
                        .build();

                com.google.iam.v1.Policy newPolicy = policy.toBuilder()
                        .addBindings(publicBinding)
                        .build();

                client.setIamPolicy(com.google.iam.v1.SetIamPolicyRequest.newBuilder()
                        .setResource(resourceName)
                        .setPolicy(newPolicy)
                        .build());

                log.info("Set public access on Cloud Run service: {}", resourceName);
            }
        } catch (Exception e) {
            log.warn("Failed to set public IAM policy on {}: {} (service may require authentication)",
                    resourceName, e.getMessage());
        }
    }

    public record DeployResult(String serviceUrl, String revisionName) {}
}
