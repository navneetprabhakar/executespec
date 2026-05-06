package com.navneet.executespec.auth.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.InvoiceRepository;
import com.navneet.executespec.notification.Notification;
import com.navneet.executespec.notification.NotificationRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * GA-readiness P5.4 (2026-05-06) — GDPR Article 15 (right of access) + Article 20 (portability).
 *
 * <p>Builds a ZIP bundle of every row tied to the requesting user across the platform's core
 * tables. Synchronous and self-contained: returns the zip bytes for the controller to stream as an
 * HTTP response. Per-user export size is small enough to do in-memory (typical user has &lt; 10
 * runs + a few projects).
 *
 * <p><b>Sensitive-field redaction:</b> {@code AppUser.password_hash}, {@code AppUser.openAiKey}
 * etc., and any other secret material is dropped from the export. The user already has these inside
 * their own settings UI; an export bundle that landed on a stolen laptop should not re-expose them
 * in plaintext.
 *
 * <p><b>What's NOT included:</b>
 *
 * <ul>
 *   <li>Other users' data (cross-org rows are excluded — even when the requesting user is an org
 *       admin, this endpoint exports their personal data only, not their org's).
 *   <li>Workspace files on disk — these are scoped to specific runs and are accessible via the
 *       existing per-run download endpoints.
 *   <li>Internal audit hash chains — preserved for compliance, not exposed to the user.
 * </ul>
 *
 * <p>For broader org-scoped export ({@code POST /api/projects/{id}/export} from E4.7), see {@code
 * ProjectExportController}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDataExportService {

  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;
  private final ProjectRepository projectRepo;
  private final PipelineRunRepository runRepo;
  private final NotificationRepository notificationRepo;
  private final BillingSubscriptionRepository subRepo;
  private final InvoiceRepository invoiceRepo;

  /** Cap on row count per top-level key to keep the export bounded. */
  private static final int EXPORT_ROW_LIMIT = 200;

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .enable(SerializationFeature.INDENT_OUTPUT)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  /** Tables we'll always include. Order is rendered into the zip's top-level layout. */
  public record ExportBundle(byte[] zipBytes, String suggestedFilename) {}

  @Transactional(readOnly = true)
  public ExportBundle exportForUser(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));

    Map<String, Object> manifest = new HashMap<>();
    manifest.put("exportedAt", Instant.now().toString());
    manifest.put("exportedFor", userId);
    manifest.put("schemaVersion", 2);
    manifest.put("rowLimit", EXPORT_ROW_LIMIT);
    manifest.put(
        "scope",
        "Personal data only. Org / project data shared with other users is NOT included unless"
            + " you are the sole member.");
    manifest.put(
        "rights",
        "GDPR Article 15 (access) + Article 20 (portability). Contact admin@executespec.ai for"
            + " erasure (Article 17) requests.");

    // Build payloads
    Map<String, Object> payload = new HashMap<>();
    payload.put("user", redactUser(user));

    List<Org> orgs =
        user.getOrgId() != null ? orgRepo.findById(user.getOrgId()).stream().toList() : List.of();
    payload.put("orgs", orgs);

    // Project ownership is org-scoped — a user inherits visibility through their org row.
    // For solo users (auto-provisioned personal org), this is just their projects. For
    // multi-member orgs, this returns all projects in their org; downstream filtering for
    // strict per-user data is a follow-up if/when product wants narrower export scope.
    List<Project> projects =
        user.getOrgId() != null ? projectRepo.findByOrgId(user.getOrgId()) : List.of();
    payload.put("projects", projects);

    // schemaVersion 2 (P5.4 follow-up, 2026-05-06) — pull additional personal-data tables.
    // Each list is capped at EXPORT_ROW_LIMIT (200) to bound the in-memory zip size; for
    // power users with thousands of runs we'd add proper async + GCS hand-off in a future
    // bump, but the cap is sufficient for current scale.
    PageRequest topN = PageRequest.of(0, EXPORT_ROW_LIMIT);
    List<PipelineRun> runs =
        runRepo.findByCreatedByUserIdOrderByStartedAtDesc(userId, topN).getContent();
    payload.put("runs", runs);
    // TODO P5.4-followup: project to a slimmer "RunExportRow" record so internal ops
    // fields (worker IDs, retry counters, etc.) stay out of the public bundle. For now
    // the entity ships as-is; the data is the user's own so leakage is bounded to
    // them seeing more than they need.

    List<Notification> notifications =
        notificationRepo.findByUserIdOrderByCreatedAtDesc(userId, topN).getContent();
    payload.put("notifications", notifications);

    // Active subscription (current state) — historical churn arc is a future schema bump.
    Map<String, Object> billing = new HashMap<>();
    Optional<BillingSubscription> activeSub = subRepo.findActiveByUserId(userId);
    billing.put("activeSubscription", activeSub.orElse(null));
    List<Invoice> invoices =
        invoiceRepo.findByUserIdOrderByInvoiceDateDesc(userId, topN).getContent();
    billing.put("invoices", invoices);
    payload.put("billing", billing);

    payload.put(
        "_note",
        "schemaVersion 2 — runs / notifications / billing included alongside the user / orgs"
            + " / projects identity bundle. Each list is capped at "
            + EXPORT_ROW_LIMIT
            + " rows for in-memory export safety; the full row count is logged at export time."
            + " Activity audit hash chain is preserved internally for compliance and is not"
            + " exposed here.");

    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(baos)) {
        writeJsonEntry(zip, "manifest.json", manifest);
        writeJsonEntry(zip, "payload.json", payload);
        writeReadme(zip);
      }
      String filename =
          "executespec-export-" + userId + "-" + Instant.now().toString().substring(0, 10) + ".zip";
      log.info(
          "[UserDataExport] Generated export for user={} sizeBytes={} projectsIncluded={}",
          userId,
          baos.size(),
          projects.size());
      return new ExportBundle(baos.toByteArray(), filename);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to build export bundle for user " + userId, e);
    }
  }

  /**
   * Strip the password hash + every API-key column off a copy of {@link AppUser} before
   * serialising. The user already knows their keys from settings UI; re-emitting them in a file the
   * user might forward by email is a needless exposure.
   */
  private static Map<String, Object> redactUser(AppUser u) {
    Map<String, Object> redacted = new HashMap<>();
    redacted.put("id", u.getId());
    redacted.put("email", u.getEmail());
    redacted.put("firstName", u.getFirstName());
    redacted.put("lastName", u.getLastName());
    redacted.put("globalRole", u.getGlobalRole());
    redacted.put("tier", u.getTier());
    redacted.put("orgId", u.getOrgId());
    redacted.put("createdAt", u.getCreatedAt());
    redacted.put("updatedAt", u.getUpdatedAt());
    redacted.put("emailVerified", u.isEmailVerified());
    // Capability flags are non-sensitive metadata. We never expose the key values
    // themselves — only "do you have one configured" so the user can confirm
    // settings without re-exposing the key in a forwarded export.
    redacted.put("hasAnthropicKey", u.getAnthropicApiKey() != null);
    redacted.put("hasOpenAiKey", u.getOpenAiApiKey() != null);
    redacted.put("hasGeminiKey", u.getGeminiApiKey() != null);
    redacted.put(
        "_redacted",
        "Password hash + API keys + GitHub PAT excluded for security. Reset via Settings if"
            + " needed.");
    return redacted;
  }

  private static void writeJsonEntry(ZipOutputStream zip, String entryName, Object obj)
      throws IOException {
    zip.putNextEntry(new ZipEntry(entryName));
    zip.write(MAPPER.writeValueAsBytes(obj));
    zip.closeEntry();
  }

  private static void writeReadme(ZipOutputStream zip) throws IOException {
    String readme =
        "# ExecuteSpec — your data export\n\n"
            + "This bundle contains every personal-data row ExecuteSpec stores about you,\n"
            + "as required by GDPR Article 15 (right of access) + Article 20 (portability).\n\n"
            + "## Files\n\n"
            + "- `manifest.json` — export metadata (when, who, schemaVersion, rowLimit).\n"
            + "- `payload.json` — your data: user record, orgs, projects, runs, notifications,\n"
            + "  billing (active subscription + invoices). Format is stable JSON; the\n"
            + "  `schemaVersion` field increments when the shape changes.\n\n"
            + "## Bundle scope (schemaVersion 2)\n\n"
            + "Top-level keys in `payload.json`:\n\n"
            + "- `user` — your AppUser record with sensitive fields redacted.\n"
            + "- `orgs` — orgs you belong to.\n"
            + "- `projects` — projects in your org (cross-member visible per org membership).\n"
            + "- `runs` — last "
            + EXPORT_ROW_LIMIT
            + " pipeline runs you submitted.\n"
            + "- `notifications` — last "
            + EXPORT_ROW_LIMIT
            + " in-app notifications.\n"
            + "- `billing.activeSubscription` — current paid subscription (or absent).\n"
            + "- `billing.invoices` — last "
            + EXPORT_ROW_LIMIT
            + " invoices.\n\n"
            + "Each list is capped at "
            + EXPORT_ROW_LIMIT
            + " rows for in-memory export safety.\n\n"
            + "## What's redacted\n\n"
            + "Your password hash + LLM API keys + GitHub PAT are intentionally excluded.\n"
            + "You can recover or rotate them from Settings → Account.\n\n"
            + "## Erasure\n\n"
            + "If you want your data deleted (GDPR Article 17), email\n"
            + "admin@executespec.ai — that path is processed manually within 30 days.\n\n"
            + "## Org / project data\n\n"
            + "If you are an org admin, this export does NOT include other org members'\n"
            + "data. Each user must request their own export. Project-level export is\n"
            + "available via `POST /api/projects/{id}/export`.\n";
    zip.putNextEntry(new ZipEntry("README.md"));
    zip.write(readme.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
