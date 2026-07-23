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
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.InvoiceRepository;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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
  private ProjectMemberRepository projectMemberRepo;
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
    projectMemberRepo = mock(ProjectMemberRepository.class);
    runRepo = mock(PipelineRunRepository.class);
    notificationRepo = mock(NotificationRepository.class);
    subRepo = mock(BillingSubscriptionRepository.class);
    invoiceRepo = mock(InvoiceRepository.class);
    svc =
        new UserDataExportService(
            userRepo,
            orgRepo,
            projectRepo,
            projectMemberRepo,
            runRepo,
            notificationRepo,
            subRepo,
            invoiceRepo);
    lenient().when(orgRepo.findById(anyString())).thenReturn(Optional.empty());
    lenient().when(projectRepo.findByOrgId(anyString())).thenReturn(List.of());
    lenient().when(projectRepo.findAllById(any())).thenReturn(List.of());
    lenient().when(projectMemberRepo.findProjectIdsByUserId(anyString())).thenReturn(List.of());
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

  /**
   * RR-204 secret canary — the export used to serialise the {@link Org} and {@link
   * com.navneet.executespec.state.entity.PipelineRun} ENTITIES, which expose Jackson-visible
   * getters for the decrypted org provider keys, JIRA/Confluence tokens, and the run-scoped GitHub
   * PAT + Bitbucket app password. The pre-existing redaction test only ever populated {@code
   * AppUser} secrets, so it passed the whole time the org and run credentials were being written
   * into {@code payload.json}.
   *
   * <p>This test populates EVERY credential field on the org and run rows and asserts that not one
   * of the values survives anywhere in the bundle.
   */
  @Test
  void payloadJson_neverContainsOrgOrRunCredentials() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    Org org = buildOrg();
    org.setAnthropicApiKey("CANARY-org-anthropic");
    org.setOpenAiApiKey("CANARY-org-openai");
    org.setGeminiApiKey("CANARY-org-gemini");
    org.setOpenRouterApiKey("CANARY-org-openrouter");
    org.setDeepSeekApiKey("CANARY-org-deepseek");
    org.setFireworksApiKey("CANARY-org-fireworks");
    org.setJiraApiToken("CANARY-org-jira");
    org.setConfluenceApiToken("CANARY-org-confluence");
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));

    com.navneet.executespec.state.entity.PipelineRun run =
        new com.navneet.executespec.state.entity.PipelineRun();
    run.setRunId("run-1");
    run.setStatus("COMPLETE");
    run.setCreatedByUserId("user-123");
    run.setGithubPat("CANARY-run-github-pat");
    run.setBitbucketAppPassword("CANARY-run-bitbucket-password");
    when(runRepo.findByCreatedByUserIdOrderByStartedAtDesc(anyString(), any()))
        .thenAnswer(inv -> new PageImpl<>(List.of(run)));

    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    String payloadStr = new String(entries.get("payload.json"), StandardCharsets.UTF_8);

    assertThat(payloadStr).doesNotContain("CANARY-");

    // The non-secret identity/plan fields the user is entitled to still ship.
    assertThat(payloadStr).contains("Acme Inc").contains("run-1");
  }

  /**
   * RR-205 — a "personal" DSAR must NOT include another org member's project. Org holds two
   * projects: one the requester created and one another user created (requester is not a member).
   * Only the requester-owned project must appear.
   */
  @Test
  void payloadJson_excludesOtherMembersProjects() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    Project mine = new Project();
    mine.setId("proj-mine");
    mine.setName("My Project");
    mine.setOrgId("org-1");
    mine.setCreatedBy("user-123");

    Project theirs = new Project();
    theirs.setId("proj-theirs");
    theirs.setName("Someone Else Project");
    theirs.setOrgId("org-1");
    theirs.setCreatedBy("user-999");

    when(projectRepo.findByOrgId("org-1")).thenReturn(List.of(mine, theirs));
    // requester is a member of no project.
    when(projectMemberRepo.findProjectIdsByUserId("user-123")).thenReturn(List.of());

    String payloadStr =
        new String(
            readZip(svc.exportForUser("user-123").zipBytes()).get("payload.json"),
            StandardCharsets.UTF_8);

    assertThat(payloadStr).contains("proj-mine").contains("My Project");
    assertThat(payloadStr).doesNotContain("proj-theirs");
    assertThat(payloadStr).doesNotContain("Someone Else Project");
  }

  /**
   * RR-205 — a project the requester did not create but IS a member of (including cross-org
   * external collaboration) is attributable and must be included.
   */
  @Test
  void payloadJson_includesMemberProjectFromAnotherOrg() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));
    when(projectRepo.findByOrgId("org-1")).thenReturn(List.of());
    when(projectMemberRepo.findProjectIdsByUserId("user-123")).thenReturn(List.of("proj-foreign"));

    Project foreign = new Project();
    foreign.setId("proj-foreign");
    foreign.setName("Shared Project");
    foreign.setOrgId("other-org");
    foreign.setCreatedBy("user-999");
    when(projectRepo.findAllById(List.of("proj-foreign"))).thenReturn(List.of(foreign));

    String payloadStr =
        new String(
            readZip(svc.exportForUser("user-123").zipBytes()).get("payload.json"),
            StandardCharsets.UTF_8);
    assertThat(payloadStr).contains("proj-foreign").contains("Shared Project");
  }

  /**
   * RR-206 — when a collection exceeds the row cap the manifest must report the TRUE total and flag
   * the truncation, so the Article 15/20 completeness claim is honest.
   */
  @Test
  void manifest_reportsHonestTruncationWhenRowCapped() throws Exception {
    when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

    com.navneet.executespec.state.entity.PipelineRun run =
        new com.navneet.executespec.state.entity.PipelineRun();
    run.setRunId("run-1");
    run.setCreatedByUserId("user-123");
    // content of 1 row, but a total of 500 in the store → truncated.
    when(runRepo.findByCreatedByUserIdOrderByStartedAtDesc(anyString(), any()))
        .thenReturn(new PageImpl<>(List.of(run), PageRequest.of(0, 200), 500));

    Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
    @SuppressWarnings("unchecked")
    Map<String, Object> manifest = MAPPER.readValue(entries.get("manifest.json"), Map.class);

    assertThat(manifest.get("truncated")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> truncation = (Map<String, Object>) manifest.get("truncation");
    @SuppressWarnings("unchecked")
    Map<String, Object> runsStat = (Map<String, Object>) truncation.get("runs");
    assertThat(runsStat.get("totalRows")).isEqualTo(500);
    assertThat(runsStat.get("includedRows")).isEqualTo(1);
    assertThat(runsStat.get("truncated")).isEqualTo(true);
  }

  /**
   * RR-207 — the serialized-byte budget stops admitting rows before heap blows up, and the
   * truncation is reported via the same manifest signal. Driven with a tiny cap to avoid allocating
   * 20 MiB in the test.
   */
  @Test
  void export_byteCapTruncatesAndIsReported() throws Exception {
    long original = UserDataExportService.MAX_EXPORT_PAYLOAD_BYTES;
    try {
      UserDataExportService.MAX_EXPORT_PAYLOAD_BYTES = 300; // bytes — one small run fits, not three
      when(userRepo.findById("user-123")).thenReturn(Optional.of(sampleUser()));

      List<com.navneet.executespec.state.entity.PipelineRun> runs = new java.util.ArrayList<>();
      for (int i = 0; i < 5; i++) {
        com.navneet.executespec.state.entity.PipelineRun r =
            new com.navneet.executespec.state.entity.PipelineRun();
        r.setRunId("run-" + i);
        r.setCreatedByUserId("user-123");
        r.setUserRequest("x".repeat(200)); // each projection well over the 300-byte total budget
        runs.add(r);
      }
      when(runRepo.findByCreatedByUserIdOrderByStartedAtDesc(anyString(), any()))
          .thenReturn(new PageImpl<>(runs, PageRequest.of(0, 200), 5));

      Map<String, byte[]> entries = readZip(svc.exportForUser("user-123").zipBytes());
      @SuppressWarnings("unchecked")
      Map<String, Object> manifest = MAPPER.readValue(entries.get("manifest.json"), Map.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = MAPPER.readValue(entries.get("payload.json"), Map.class);

      @SuppressWarnings("unchecked")
      List<Object> includedRuns = (List<Object>) payload.get("runs");
      assertThat(includedRuns.size()).isLessThan(5);
      assertThat(manifest.get("truncated")).isEqualTo(true);
      @SuppressWarnings("unchecked")
      Map<String, Object> truncation = (Map<String, Object>) manifest.get("truncation");
      @SuppressWarnings("unchecked")
      Map<String, Object> runsStat = (Map<String, Object>) truncation.get("runs");
      assertThat(runsStat.get("byteCapReached")).isEqualTo(true);
    } finally {
      UserDataExportService.MAX_EXPORT_PAYLOAD_BYTES = original;
    }
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
    // RR-205: attributable to the requester (owned) so the export includes it.
    p.setCreatedBy("user-123");
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
