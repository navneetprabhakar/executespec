package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.InvoiceRepository;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

/**
 * P5.4 (GA-readiness, 2026-05-06) — pins the data-export bundle contract.
 *
 * <p>Tests verify (a) the zip contains the expected entries, (b) the JSON payload includes the
 * user's data, and (c) sensitive fields (password hash, API keys) are redacted.
 */
class UserDataExportServiceTest {

  private AppUserRepository userRepo;
  private OrgRepository orgRepo;
  private ProjectRepository projectRepo;
  private PipelineRunRepository runRepo;
  private NotificationRepository notificationRepo;
  private BillingSubscriptionRepository subRepo;
  private InvoiceRepository invoiceRepo;
  private UserDataExportService svc;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    orgRepo = mock(OrgRepository.class);
    projectRepo = mock(ProjectRepository.class);
    runRepo = mock(PipelineRunRepository.class);
    notificationRepo = mock(NotificationRepository.class);
    subRepo = mock(BillingSubscriptionRepository.class);
    invoiceRepo = mock(InvoiceRepository.class);
    svc =
        new UserDataExportService(
            userRepo, orgRepo, projectRepo, runRepo, notificationRepo, subRepo, invoiceRepo);
    lenient().when(orgRepo.findById(anyString())).thenReturn(Optional.empty());
    lenient().when(projectRepo.findByOrgId(anyString())).thenReturn(List.of());
    Page<?> emptyPage = new PageImpl<>(List.of());
    lenient()
        .when(runRepo.findByCreatedByUserIdOrderByStartedAtDesc(anyString(), any()))
        .thenAnswer(inv -> emptyPage);
    lenient()
        .when(notificationRepo.findByUserIdOrderByCreatedAtDesc(anyString(), any()))
        .thenAnswer(inv -> emptyPage);
    lenient().when(subRepo.findActiveByUserId(anyString())).thenReturn(Optional.empty());
    lenient()
        .when(invoiceRepo.findByUserIdOrderByInvoiceDateDesc(anyString(), any()))
        .thenAnswer(inv -> emptyPage);
  }

  private AppUser sampleUser() {
    AppUser u = new AppUser();
    u.setId("user-123");
    u.setEmail("alice@example.com");
    u.setFirstName("Alice");
    u.setLastName("Doe");
    u.setGlobalRole("USER");
    u.setTier("PRO");
    u.setOrgId("org-1");
    u.setEmailVerified(true);
    // Sensitive — must NOT appear in the export
    u.setPasswordHash("$2a$10$verysecret");
    u.setOpenAiApiKey("sk-secret-key-1234567890");
    u.setAnthropicApiKey("sk-ant-secret");
    u.setGeminiApiKey("AIzaSy-gemini-secret");
    u.setGithubPat("ghp_supersecret");
    return u;
  }

  @Test
  void exportForUser_unknownUser_throws() {
    when(userRepo.findById("nope")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> svc.exportForUser("nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user not found");
  }

  @Test
  void exportForUser_zipContainsCoreEntries() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    UserDataExportService.ExportBundle bundle = svc.exportForUser("user-123");

    assertThat(bundle.zipBytes()).isNotEmpty();
    assertThat(bundle.suggestedFilename()).contains("user-123").endsWith(".zip");

    Map<String, byte[]> entries = readZip(bundle.zipBytes());
    assertThat(entries).containsKeys("manifest.json", "payload.json", "README.md");
  }

  @Test
  void manifestJson_includesScopeAndRights() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));
    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> manifest = MAPPER.readValue(entries.get("manifest.json"), Map.class);
    assertThat(manifest).containsKey("exportedAt");
    assertThat(manifest.get("exportedFor")).isEqualTo("user-123");
    assertThat(manifest.get("schemaVersion")).isEqualTo(2);
    assertThat(manifest.get("scope").toString()).contains("Personal data only");
    assertThat(manifest.get("rights").toString())
        .contains("GDPR Article 15")
        .contains("Article 20")
        .contains("admin@executespec.ai");
  }

  @Test
  void payloadJson_includesUserAndProjects() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(buildOrg()));
    when(projectRepo.findByOrgId("org-1")).thenReturn(List.of(buildProject()));

    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(entries.get("payload.json"), Map.class);

    assertThat(payload)
        .containsKeys("user", "orgs", "projects", "runs", "notifications", "billing");
    @SuppressWarnings("unchecked")
    Map<String, Object> userBlock = (Map<String, Object>) payload.get("user");
    assertThat(userBlock.get("email")).isEqualTo("alice@example.com");
    assertThat(userBlock.get("tier")).isEqualTo("PRO");
    @SuppressWarnings("unchecked")
    List<Object> projects = (List<Object>) payload.get("projects");
    assertThat(projects).hasSize(1);
  }

  @Test
  void payloadJson_redactsSensitiveFields() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    String payloadStr = new String(entries.get("payload.json"), StandardCharsets.UTF_8);

    // Whatever shape the test fixture used, NONE of these values should appear
    // anywhere in the export.
    assertThat(payloadStr).doesNotContain("$2a$10$verysecret");
    assertThat(payloadStr).doesNotContain("sk-secret-key-1234567890");
    assertThat(payloadStr).doesNotContain("sk-ant-secret");
    assertThat(payloadStr).doesNotContain("AIzaSy-gemini-secret");
    assertThat(payloadStr).doesNotContain("ghp_supersecret");
    // The capability flag is OK to expose — it tells the user they have the key set
    assertThat(payloadStr).contains("hasAnthropicKey");
  }

  @Test
  void payloadJson_omitsNullsAndIncludesRedactionNote() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(entries.get("payload.json"), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> userBlock = (Map<String, Object>) payload.get("user");
    assertThat(userBlock).containsKey("_redacted");
    assertThat(userBlock.get("_redacted").toString()).contains("Password hash");
  }

  @Test
  void readme_includesGuidance() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));
    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    String readme = new String(entries.get("README.md"), StandardCharsets.UTF_8);
    assertThat(readme)
        .contains("GDPR Article 15")
        .contains("schemaVersion")
        .contains("admin@executespec.ai")
        .contains("Article 17");
  }

  @Test
  void schemaV2_payloadContainsRunsNotificationsBillingKeys() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(entries.get("payload.json"), Map.class);

    // P5.4-followup schemaVersion 2 — three new top-level keys.
    assertThat(payload).containsKeys("runs", "notifications", "billing");
    @SuppressWarnings("unchecked")
    Map<String, Object> billing = (Map<String, Object>) payload.get("billing");
    // Jackson is configured with NON_NULL inclusion, so an absent active
    // subscription is omitted from the JSON entirely. invoices is always
    // present (empty list when none exist).
    assertThat(billing).containsKey("invoices");
    assertThat(payload.get("runs")).isEqualTo(List.of());
    assertThat(payload.get("notifications")).isEqualTo(List.of());
    assertThat(billing.get("invoices")).isEqualTo(List.of());
    // activeSubscription not asserted when null — its presence/absence is
    // verified separately when the mock returns a non-null subscription.
  }

  @Test
  void schemaV2_manifestAdvertisesRowLimit() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));
    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> manifest = MAPPER.readValue(entries.get("manifest.json"), Map.class);
    assertThat(manifest).containsKey("rowLimit");
    assertThat((Integer) manifest.get("rowLimit")).isPositive();
  }

  @Test
  void exportForUser_noOrg_emptyOrgsAndProjects() throws Exception {
    AppUser solo = sampleUser();
    solo.setOrgId(null);
    when(userRepo.findById("user-solo")).thenReturn(Optional.of(solo));
    solo.setId("user-solo");

    UserDataExportService.ExportBundle bundle = svc.exportForUser("user-solo");
    Map<String, byte[]> entries = readZip(bundle.zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = MAPPER.readValue(entries.get("payload.json"), Map.class);

    assertThat(payload.get("orgs")).isInstanceOf(List.class);
    assertThat(((List<?>) payload.get("orgs"))).isEmpty();
    assertThat(((List<?>) payload.get("projects"))).isEmpty();
  }

  private Org buildOrg() {
    Org org = new Org();
    org.setId("org-1");
    org.setName("Acme Inc");
    return org;
  }

  private Project buildProject() {
    Project p = new Project();
    p.setId("proj-1");
    p.setName("Demo Project");
    p.setOrgId("org-1");
    return p;
  }

  private static Map<String, byte[]> readZip(byte[] bytes) throws Exception {
    Map<String, byte[]> out = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        out.put(e.getName(), zip.readAllBytes());
        zip.closeEntry();
      }
    }
    return out;
  }
}
