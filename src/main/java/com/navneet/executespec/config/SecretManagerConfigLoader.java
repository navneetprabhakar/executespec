package com.navneet.executespec.config;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import com.google.cloud.secretmanager.v1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1.ListSecretsRequest;
import com.google.cloud.secretmanager.v1.ProjectName;
import com.google.cloud.secretmanager.v1.Secret;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Loads secrets from GCP Secret Manager into the Spring Environment
 * <b>before</b> bean creation — so {@code ${VAR:}} placeholders resolve correctly.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>Lists all secrets in the GCP project with label {@code app=executespec}</li>
 *   <li>Maps secret names to environment variable names:
 *       {@code executespec-anthropic-api-key → ANTHROPIC_API_KEY}</li>
 *   <li>Accesses the {@code latest} version of each secret</li>
 *   <li>Injects values as a high-priority {@link MapPropertySource}</li>
 * </ol>
 *
 * <p><b>Fallback:</b> If Secret Manager is unreachable (local dev, missing creds),
 * this silently falls back to whatever env vars are already set. Zero disruption.
 *
 * <p><b>Registration:</b> Listed in {@code META-INF/spring.factories} under
 * {@code EnvironmentPostProcessor} so Spring loads it before any {@code @Bean} wiring.
 *
 * <p><b>IAM requirement:</b> The GCE service account needs
 * {@code roles/secretmanager.secretAccessor} (already provisioned in Terraform).
 */
@Slf4j
public class SecretManagerConfigLoader implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "secretManager";
    static final String SECRET_PREFIX = "executespec-";
    static final String LABEL_FILTER = "app=executespec";

    /**
     * Secret name → env var name mapping.
     * Secret names use kebab-case; env vars use UPPER_SNAKE_CASE.
     */
    // Package-private: accessed by ConfigAdminController for reverse lookup
    static final Map<String, String> SECRET_TO_ENV = Map.ofEntries(
            Map.entry("executespec-anthropic-api-key", "ANTHROPIC_API_KEY"),
            Map.entry("executespec-openai-api-key", "OPENAI_API_KEY"),
            Map.entry("executespec-google-api-key", "GOOGLE_API_KEY"),
            Map.entry("executespec-github-pat", "GITHUB_PAT"),
            Map.entry("executespec-jwt-secret", "JWT_SECRET"),
            Map.entry("executespec-razorpay-key-id", "RAZORPAY_KEY_ID"),
            Map.entry("executespec-razorpay-key-secret", "RAZORPAY_KEY_SECRET"),
            Map.entry("executespec-razorpay-webhook-secret", "RAZORPAY_WEBHOOK_SECRET"),
            Map.entry("executespec-smtp-password", "SMTP_PASSWORD"),
            Map.entry("executespec-smtp-username", "SMTP_USERNAME"),
            Map.entry("executespec-slack-client-secret", "SLACK_CLIENT_SECRET"),
            Map.entry("executespec-slack-signing-secret", "SLACK_SIGNING_SECRET"),
            Map.entry("executespec-jira-api-token", "JIRA_API_TOKEN"),
            Map.entry("executespec-confluence-api-token", "CONFLUENCE_API_TOKEN"),
            Map.entry("executespec-db-password", "POSTGRES_PASSWORD")
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Skip in local/test profiles
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("local".equals(profile) || "test".equals(profile)) {
                log.info("[SecretManager] Skipping — profile '{}' active", profile);
                return;
            }
        }

        // Determine GCP project from existing env
        String gcpProject = environment.getProperty("GCP_PROJECT");
        if (gcpProject == null || gcpProject.isBlank()) {
            gcpProject = environment.getProperty("agent.deployment.gcp-project");
        }
        if (gcpProject == null || gcpProject.isBlank()) {
            log.info("[SecretManager] No GCP_PROJECT configured — skipping Secret Manager (likely local dev)");
            return;
        }

        Map<String, Object> secrets = loadSecrets(gcpProject);
        if (!secrets.isEmpty()) {
            // Add with high priority (after system env, before application.yml)
            environment.getPropertySources()
                    .addAfter("systemEnvironment", new MapPropertySource(PROPERTY_SOURCE_NAME, secrets));
            log.info("[SecretManager] Loaded {} secrets from project '{}'", secrets.size(), gcpProject);
        }
    }

    /**
     * Load all secrets with the executespec label from Secret Manager.
     * Returns a map of env-var-name → secret-value.
     */
    Map<String, Object> loadSecrets(String gcpProject) {
        Map<String, Object> result = new HashMap<>();

        try (SecretManagerServiceClient client = SecretManagerServiceClient.create()) {
            // List all secrets with our label
            ListSecretsRequest request = ListSecretsRequest.newBuilder()
                    .setParent(ProjectName.of(gcpProject).toString())
                    .setFilter("labels." + LABEL_FILTER)
                    .build();

            for (Secret secret : client.listSecrets(request).iterateAll()) {
                String secretId = extractSecretId(secret.getName());

                String envVar = SECRET_TO_ENV.get(secretId);
                if (envVar == null) {
                    // Auto-derive: executespec-foo-bar → FOO_BAR
                    if (secretId.startsWith(SECRET_PREFIX)) {
                        envVar = secretId.substring(SECRET_PREFIX.length())
                                .toUpperCase()
                                .replace('-', '_');
                    } else {
                        continue;
                    }
                }

                try {
                    AccessSecretVersionResponse response = client.accessSecretVersion(
                            SecretVersionName.of(gcpProject, secretId, "latest"));
                    String value = response.getPayload().getData().toStringUtf8().trim();
                    if (!value.isEmpty()) {
                        result.put(envVar, value);
                    }
                } catch (Exception e) {
                    log.warn("[SecretManager] Failed to access secret '{}': {}", secretId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SecretManager] Could not connect to Secret Manager ({}). " +
                     "Falling back to env vars / env file.", e.getMessage());
        }

        return result;
    }

    /**
     * Extracts the secret ID from the full resource name.
     * e.g., "projects/123/secrets/executespec-anthropic-api-key" → "executespec-anthropic-api-key"
     */
    private static String extractSecretId(String fullName) {
        int lastSlash = fullName.lastIndexOf('/');
        return lastSlash >= 0 ? fullName.substring(lastSlash + 1) : fullName;
    }

    /**
     * Reload secrets into System properties (used by ConfigRefreshService at runtime).
     * Unlike postProcessEnvironment, this operates on the live JVM.
     */
    public Map<String, String> refreshSecrets(String gcpProject) {
        Map<String, Object> loaded = loadSecrets(gcpProject);
        Map<String, String> changed = new HashMap<>();

        for (var entry : loaded.entrySet()) {
            String current = System.getProperty(entry.getKey());
            String newValue = entry.getValue().toString();
            if (!newValue.equals(current)) {
                System.setProperty(entry.getKey(), newValue);
                changed.put(entry.getKey(), "***");
            }
        }

        return changed;
    }
}
