package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.deployment.credential.CredentialEncryptionService;
import com.navneet.executespec.deployment.credential.CredentialService;
import com.navneet.executespec.deployment.credential.DeploymentCredential;
import com.navneet.executespec.deployment.credential.DeploymentCredentialRepository;
import com.navneet.executespec.deployment.credential.UserGcpCredentialProvider;
import com.navneet.executespec.deployment.preview.EnvVarDetector;
import com.navneet.executespec.deployment.preview.GlobalPreviewLimiter;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.repository.DeploymentRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Batch remediation for the deployment-package P1 findings fixed in this lane. Each test carries
 * the RR-id of the behavior it pins.
 */
class DeploymentP1RemediationTest {

  // ---- RR-689 — real credential values from .env.example must never become display defaults ----

  @Test
  void secretClassifiedVarNeverExposesItsExampleValue(@TempDir Path root) throws Exception {
    Path ws = Files.createDirectory(root.resolve("ws"));
    Files.writeString(
        ws.resolve(".env.example"),
        "STRIPE_SECRET_KEY=sk_live_51ABCdefGHIjklMNOpqrs\nAPP_TITLE=My App\n");

    var detected = new EnvVarDetector().detect(ws);

    var stripe = detected.stream().filter(v -> v.name().equals("STRIPE_SECRET_KEY")).findFirst();
    assertThat(stripe).isPresent();
    assertThat(stripe.get().defaultValue()).isNull(); // presence only, value quarantined
    // A non-secret standard var keeps its harmless default.
    var title = detected.stream().filter(v -> v.name().equals("APP_TITLE")).findFirst();
    assertThat(title).isPresent();
    assertThat(title.get().defaultValue()).isEqualTo("My App");
  }

  @Test
  void recognizedCredentialInInnocuouslyNamedVarIsRedacted(@TempDir Path root) throws Exception {
    Path ws = Files.createDirectory(root.resolve("ws"));
    // GitHub PAT format lives in a var the name-classifier would treat as STANDARD.
    Files.writeString(
        ws.resolve(".env.example"), "DEPLOY_HOOK=ghp_abcdefghijklmnopqrstuvwxyz0123456789\n");

    var detected = new EnvVarDetector().detect(ws);

    var hook = detected.stream().filter(v -> v.name().equals("DEPLOY_HOOK")).findFirst();
    assertThat(hook).isPresent();
    assertThat(hook.get().defaultValue()).isNull();
  }

  // ---- RR-691 — a large candidate file is read under a byte ceiling but still detected ----

  @Test
  void largeEnvFileStillDetectedUnderBoundedRead(@TempDir Path root) throws Exception {
    Path ws = Files.createDirectory(root.resolve("ws"));
    StringBuilder sb = new StringBuilder("API_KEY=\n");
    sb.append("# padding\n".repeat(50_000)); // ~500 KB of trailing comment lines
    Files.writeString(ws.resolve(".env.example"), sb.toString());

    var detected = new EnvVarDetector().detect(ws);

    assertThat(detected).anyMatch(v -> v.name().equals("API_KEY"));
  }

  // ---- RR-166 — PRO_PLUS and BYOK are paid tiers, not FREE-limited ----

  @Test
  void paidTiersAreNotFreeLimited() throws Exception {
    GlobalPreviewLimiter limiter = new GlobalPreviewLimiter(null);
    Method m = GlobalPreviewLimiter.class.getDeclaredMethod("limitForTier", String.class);
    m.setAccessible(true);

    int free = (int) m.invoke(limiter, "FREE");
    int proPlus = (int) m.invoke(limiter, "PRO_PLUS");
    int byok = (int) m.invoke(limiter, "BYOK");
    int enterprise = (int) m.invoke(limiter, "ENTERPRISE");

    assertThat(proPlus).isGreaterThan(free).isEqualTo(enterprise);
    assertThat(byok).isGreaterThan(free).isEqualTo(enterprise);
  }

  // ---- RR-1000 — health probe target must be an HTTPS Cloud Run origin ----

  @Test
  void healthProbeRejectsNonCloudRunAndNonHttpsTargets() {
    assertThat(CloudRunClient.isTrustedCloudRunUrl("https://svc-abc-uc.a.run.app")).isTrue();
    assertThat(CloudRunClient.isTrustedCloudRunUrl("https://svc-123.us-east4.run.app")).isTrue();
    // SSRF vectors: loopback, metadata, RFC1918, plain http, and a look-alike host.
    assertThat(CloudRunClient.isTrustedCloudRunUrl("http://svc-abc.a.run.app")).isFalse();
    assertThat(CloudRunClient.isTrustedCloudRunUrl("http://169.254.169.254/")).isFalse();
    assertThat(CloudRunClient.isTrustedCloudRunUrl("http://127.0.0.1:8080")).isFalse();
    assertThat(CloudRunClient.isTrustedCloudRunUrl("https://10.0.0.1")).isFalse();
    assertThat(CloudRunClient.isTrustedCloudRunUrl("https://run.app.evil.com")).isFalse();
    assertThat(CloudRunClient.isTrustedCloudRunUrl("not a url")).isFalse();
  }

  // ---- RR-1395 — a pre-strategy failure row is schema-valid (non-null strategy) ----

  @Test
  void deployStrategyHasUnknownSentinelForFailureRows() {
    assertThat(Deployment.DeployStrategy.valueOf("UNKNOWN")).isNotNull();
    assertThat(Deployment.DeployStrategy.UNKNOWN.name().length()).isLessThanOrEqualTo(32);
  }

  // ---- RR-1003 / RR-1396 — validation authenticates the exact scoped credential row ----

  @Test
  void validateCredentialAuthenticatesTheScopedRowNotTheDefault() {
    DeploymentCredentialRepository repo = mock(DeploymentCredentialRepository.class);
    UserGcpCredentialProvider provider = mock(UserGcpCredentialProvider.class);
    DeploymentCredential scoped =
        DeploymentCredential.create("user-1", "proj-1", new byte[] {1}, "gcp-scoped", "sa@x");
    when(repo.findByUserIdAndProjectIdAndCloudProvider("user-1", "proj-1", "GCP"))
        .thenReturn(Optional.of(scoped));
    // The scoped overload is the one that must be used; make it fail so we never touch IAM.
    when(provider.getCredentialsOrThrow("user-1", "proj-1"))
        .thenThrow(new IllegalStateException("scoped key invalid"));

    CredentialService service =
        new CredentialService(
            repo,
            mock(CredentialEncryptionService.class),
            provider,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            mock(com.navneet.executespec.auth.repository.ProjectRepository.class),
            mock(com.navneet.executespec.auth.repository.ProjectMemberRepository.class),
            mock(com.navneet.executespec.auth.repository.AppUserRepository.class));

    CredentialService.ValidationResult result = service.validateCredential("user-1", "proj-1");

    assertThat(result.valid()).isFalse();
    verify(provider).getCredentialsOrThrow("user-1", "proj-1");
    verify(provider, never()).getCredentialsOrThrow(eq("user-1"));
  }

  // ---- RR-1399 — a failed cloud cleanup is not reported as a successful deletion ----

  @Test
  void deleteDeploymentSurfacesCloudCleanupFailure() throws Exception {
    DeploymentRepository repo = mock(DeploymentRepository.class);
    CloudRunClient cloudRun = mock(CloudRunClient.class);
    Deployment d = new Deployment();
    d.setId("dep-1");
    d.setRunId("run-1");
    d.setVersionTag("v1");
    d.setServiceName("demo-run-1");
    d.setImageUri("img");
    when(repo.findById("dep-1")).thenReturn(Optional.of(d));
    org.mockito.Mockito.doThrow(new RuntimeException("gcloud delete failed"))
        .when(cloudRun)
        .deleteService("demo-run-1");

    DeploymentService service = deploymentServiceWith(repo, cloudRun);

    assertThatThrownBy(() -> service.deleteDeployment("dep-1"))
        .isInstanceOf(DeploymentService.DeploymentCleanupException.class);
    // The row is marked FAILED for retry — not silently marked deleted.
    assertThat(d.getStatus()).isEqualTo(Deployment.DeployStatus.FAILED);
  }

  // The delete-failure path exercises only deploymentRepo + cloudRun; the remaining constructor
  // deps
  // are unused here (DeploymentProperties/AgentProperties are final and unmockable), so pass null.
  private static DeploymentService deploymentServiceWith(
      DeploymentRepository repo, CloudRunClient cloudRun) {
    return new DeploymentService(
        null, null, repo, null, null, null, cloudRun, null, null, null, null, null, null, null,
        null, null);
  }
}
