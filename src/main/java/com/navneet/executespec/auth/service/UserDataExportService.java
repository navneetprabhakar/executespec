package com.navneet.executespec.auth.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
import com.navneet.executespec.auth.repository.ProjectMemberRepository;
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
  private final ProjectMemberRepository projectMemberRepo;
  private final PipelineRunRepository runRepo;
  private final NotificationRepository notificationRepo;
  private final BillingSubscriptionRepository subRepo;
  private final InvoiceRepository invoiceRepo;

  /** Cap on row count per top-level key to keep the export bounded. */
  private static final int EXPORT_ROW_LIMIT = 200;

  /**
   * RR-207 — total serialized-payload byte ceiling. The row cap alone does not bound bytes (a run's
   * {@code userRequest} spec text is unbounded), so once the projected rows exceed this budget the
   * remaining rows are dropped and the collection is marked truncated in the manifest rather than
   * risking heap exhaustion. 20 MiB comfortably fits the {@link #EXPORT_ROW_LIMIT}-row projections
   * for ordinary users while capping pathological payloads.
   *
   * <p>Package-private and non-final only so tests can drive the truncation path with a tiny cap
   * instead of allocating 20 MiB; production never mutates it.
   */
  static long MAX_EXPORT_PAYLOAD_BYTES = 20L * 1024 * 1024;

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

    // RR-207: one shared serialized-byte budget across the (large) run/notification/invoice
    // collections. When exhausted, later rows are dropped and the collection is marked truncated
    // in the manifest instead of building an unbounded object graph in heap.
    ByteBudget budget = new ByteBudget(MAX_EXPORT_PAYLOAD_BYTES);

    // RR-206: honest per-collection truncation accounting so the compliance claim is not false.
    Map<String, Object> truncation = new LinkedHashMap<>();

    // Build payloads
    Map<String, Object> payload = new HashMap<>();
    payload.put("user", redactUser(user));

    // RR-204: NEVER serialise the persistence entity here. `Org` has Jackson-visible getters for
    // the DECRYPTED Anthropic / OpenAI / Gemini / OpenRouter / DeepSeek / Fireworks keys and the
    // JIRA / Confluence API tokens; `@ToString.Exclude` is not JSON redaction. Every top-level key
    // below is now built from an explicit, deny-by-default field allowlist.
    List<Map<String, Object>> orgs =
        user.getOrgId() != null
            ? orgRepo.findById(user.getOrgId()).stream()
                .map(UserDataExportService::exportOrg)
                .toList()
            : List.of();
    payload.put("orgs", orgs);

    // RR-205: a "personal" DSAR must export only projects ATTRIBUTABLE to the requester, not every
    // project in their org. `findByOrgId` returns all org projects (tombstones included); we retain
    // only those the requester created or is a member of, then add any membership projects that
    // live in another org (external collaboration).
    List<Project> attributableProjects = resolveAttributableProjects(userId, user.getOrgId());
    payload.put(
        "projects",
        attributableProjects.stream().map(UserDataExportService::exportProject).toList());

    // schemaVersion 2 (P5.4 follow-up, 2026-05-06) — pull additional personal-data tables.
    // Each list is row-capped at EXPORT_ROW_LIMIT and byte-capped by the shared budget above.
    PageRequest topN = PageRequest.of(0, EXPORT_ROW_LIMIT);

    // RR-204: `PipelineRun` exposes the DECRYPTED GitHub PAT + Bitbucket app password copied onto
    // the run row, so the entity must not be serialised either — allowlist projection only.
    var runsPage = runRepo.findByCreatedByUserIdOrderByStartedAtDesc(userId, topN);
    BoundedList runs =
        boundedProject(runsPage.getContent(), UserDataExportService::exportRun, budget);
    payload.put("runs", runs.rows());
    truncation.put("runs", collectionStat(runs, runsPage.getTotalElements()));

    var notificationsPage = notificationRepo.findByUserIdOrderByCreatedAtDesc(userId, topN);
    BoundedList notifications =
        boundedProject(
            notificationsPage.getContent(), UserDataExportService::exportNotification, budget);
    payload.put("notifications", notifications.rows());
    truncation.put(
        "notifications", collectionStat(notifications, notificationsPage.getTotalElements()));

    // Active subscription (current state) — historical churn arc is a future schema bump.
    Map<String, Object> billing = new HashMap<>();
    Optional<BillingSubscription> activeSub = subRepo.findActiveByUserId(userId);
    billing.put(
        "activeSubscription",
        activeSub.map(UserDataExportService::exportSubscription).orElse(null));
    var invoicesPage = invoiceRepo.findByUserIdOrderByInvoiceDateDesc(userId, topN);
    BoundedList invoices =
        boundedProject(invoicesPage.getContent(), UserDataExportService::exportInvoice, budget);
    billing.put("invoices", invoices.rows());
    payload.put("billing", billing);
    truncation.put("invoices", collectionStat(invoices, invoicesPage.getTotalElements()));

    // Projects are not row/byte-capped here; report an honest, non-truncated stat for parity.
    truncation.put(
        "projects",
        collectionStat(
            new BoundedList(List.of(), attributableProjects.size(), false),
            attributableProjects.size()));

    // RR-206: surface truncation in the manifest so an incomplete Article 15/20 export is never
    // presented as complete.
    boolean anyTruncated =
        runs.byteCapped()
            || notifications.byteCapped()
            || invoices.byteCapped()
            || runsPage.getTotalElements() > runs.includedRows()
            || notificationsPage.getTotalElements() > notifications.includedRows()
            || invoicesPage.getTotalElements() > invoices.includedRows();
    manifest.put("truncation", truncation);
    manifest.put("truncated", anyTruncated);
    manifest.put("byteCapBytes", MAX_EXPORT_PAYLOAD_BYTES);

    payload.put(
        "_note",
        "schemaVersion 2 — runs / notifications / billing included alongside the user / orgs"
            + " / projects identity bundle. Projects are scoped to those you own or belong to."
            + " Each list is row-capped at "
            + EXPORT_ROW_LIMIT
            + " rows and byte-capped for in-memory export safety; see manifest.truncation for the"
            + " true total counts and whether any collection was truncated."
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
          "[UserDataExport] Generated export for user={} sizeBytes={} projectsIncluded={}"
              + " truncated={}",
          userId,
          baos.size(),
          attributableProjects.size(),
          Boolean.TRUE.equals(manifest.get("truncated")));
      return new ExportBundle(baos.toByteArray(), filename);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to build export bundle for user " + userId, e);
    }
  }

  /**
   * RR-205 — the set of projects a "personal" DSAR may include: those the requester created within
   * their org, plus every project the requester is a {@link ProjectMember} of (which may live in
   * another org through external collaboration). Deliberately EXCLUDES other org members' projects
   * the requester merely inherits org-wide visibility to.
   */
  private List<Project> resolveAttributableProjects(String userId, String orgId) {
    Set<String> memberProjectIds = new HashSet<>(projectMemberRepo.findProjectIdsByUserId(userId));

    List<Project> orgProjects = orgId != null ? projectRepo.findByOrgId(orgId) : List.of();
    List<Project> attributable = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Set<String> orgProjectIds = new HashSet<>();
    for (Project p : orgProjects) {
      orgProjectIds.add(p.getId());
      boolean owned = userId.equals(p.getCreatedBy());
      if ((owned || memberProjectIds.contains(p.getId())) && seen.add(p.getId())) {
        attributable.add(p);
      }
    }

    // Membership projects in another org (external collaboration) are attributable to the
    // requester but not returned by the org-scoped lookup above.
    List<String> foreignMemberIds =
        memberProjectIds.stream().filter(id -> !orgProjectIds.contains(id)).toList();
    if (!foreignMemberIds.isEmpty()) {
      for (Project p : projectRepo.findAllById(foreignMemberIds)) {
        if (seen.add(p.getId())) {
          attributable.add(p);
        }
      }
    }
    return attributable;
  }

  /**
   * RR-207 — project each entity through its allowlist mapper, admitting rows only while the shared
   * serialized-byte {@code budget} holds. Stops (byte-capped) rather than growing the heap without
   * bound. The caller's row list is already {@link #EXPORT_ROW_LIMIT}-capped.
   */
  private static <T> BoundedList boundedProject(
      List<T> rows, Function<T, Map<String, Object>> mapper, ByteBudget budget) {
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    boolean byteCapped = false;
    for (T row : rows) {
      Map<String, Object> projected = mapper.apply(row);
      byte[] serialized;
      try {
        serialized = MAPPER.writeValueAsBytes(projected);
      } catch (IOException e) {
        // A row that cannot be serialised is skipped rather than aborting the whole export.
        log.warn("[UserDataExport] Skipping unserialisable export row: {}", e.getMessage());
        continue;
      }
      if (!budget.tryConsume(serialized.length)) {
        byteCapped = true;
        break;
      }
      out.add(projected);
    }
    return new BoundedList(out, out.size(), byteCapped);
  }

  /** RR-206 — per-collection completion stat for the manifest. */
  private static Map<String, Object> collectionStat(BoundedList included, long totalRows) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("includedRows", included.includedRows());
    m.put("totalRows", totalRows);
    m.put("truncated", totalRows > included.includedRows());
    m.put("byteCapReached", included.byteCapped());
    return m;
  }

  /** RR-207 — projected rows plus the count admitted and whether the byte budget stopped early. */
  private record BoundedList(
      List<Map<String, Object>> rows, int includedRows, boolean byteCapped) {}

  /** RR-207 — mutable serialized-byte budget shared across the exported collections. */
  private static final class ByteBudget {
    private long remaining;

    ByteBudget(long cap) {
      this.remaining = cap;
    }

    boolean tryConsume(long bytes) {
      if (bytes > remaining) {
        return false;
      }
      remaining -= bytes;
      return true;
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

  /**
   * RR-204 — deny-by-default projection of {@link Org}.
   *
   * <p>The entity carries six decrypted provider API keys plus JIRA and Confluence API tokens. Only
   * the identity/plan fields named here are exported; credential presence is reported as a boolean
   * so the user can still confirm their configuration without the bundle containing the secret.
   *
   * <p>Adding a field to {@code Org} does NOT add it to the export — that is the point of the
   * allowlist, and {@code UserDataExportSecretCanaryTest} pins it.
   */
  private static Map<String, Object> exportOrg(Org o) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", o.getId());
    m.put("name", o.getName());
    m.put("tier", o.getTier());
    m.put("status", o.getStatus());
    m.put("seatCount", o.getSeatCount());
    m.put("keySourceMode", o.getKeySourceMode());
    m.put("isPersonal", o.isPersonal());
    m.put("planExpiresAt", o.getPlanExpiresAt());
    m.put("monthlyRunCount", o.getMonthlyRunCount());
    m.put("hasOwnProviderKeys", o.isApiKeysProvided());
    m.put(
        "_redacted",
        "Org provider API keys (Anthropic / OpenAI / Gemini / OpenRouter / DeepSeek / Fireworks)"
            + " and JIRA / Confluence API tokens are shared org credentials and are never"
            + " exported.");
    return m;
  }

  /** RR-204 — deny-by-default projection of {@link Project}. */
  private static Map<String, Object> exportProject(Project p) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", p.getId());
    m.put("orgId", p.getOrgId());
    m.put("name", p.getName());
    m.put("description", p.getDescription());
    m.put("status", p.getStatus());
    m.put("aiProvider", p.getAiProvider());
    m.put("createdBy", p.getCreatedBy());
    m.put("createdAt", p.getCreatedAt());
    m.put("updatedAt", p.getUpdatedAt());
    m.put("targetBranch", p.getTargetBranch());
    m.put("archivedAt", p.getArchivedAt());
    m.put("deletedAt", p.getDeletedAt());
    return m;
  }

  /**
   * RR-204 — deny-by-default projection of {@link PipelineRun}.
   *
   * <p>The entity exposes {@code githubPat} and {@code bitbucketAppPassword} — decrypted SCM
   * credentials copied onto the run row — which must never leave the server.
   */
  private static Map<String, Object> exportRun(PipelineRun r) {
    Map<String, Object> m = new HashMap<>();
    m.put("runId", r.getRunId());
    m.put("userRequest", r.getUserRequest());
    m.put("runTitle", r.getRunTitle());
    m.put("runSummary", r.getRunSummary());
    m.put("status", r.getStatus());
    m.put("statusReason", r.getStatusReason());
    m.put("failureReason", r.getFailureReason());
    m.put("runMode", r.getRunMode());
    m.put("executionMode", r.getExecutionMode());
    m.put("aiProvider", r.getAiProvider());
    m.put("requestedProvider", r.getRequestedProvider());
    m.put("requestedModel", r.getRequestedModel());
    m.put("projectId", r.getProjectId());
    m.put("orgId", r.getOrgId());
    m.put("createdByUserId", r.getCreatedByUserId());
    m.put("startedAt", r.getStartedAt());
    m.put("completedAt", r.getCompletedAt());
    m.put("creditsCharged", r.getCreditsCharged());
    m.put("actualCostUsd", r.getActualCostUsd());
    m.put("totalInputTokens", r.getTotalInputTokens());
    m.put("totalOutputTokens", r.getTotalOutputTokens());
    m.put("githubPrUrl", r.getGithubPrUrl());
    m.put("scmPrUrl", r.getScmPrUrl());
    m.put("archivedAt", r.getArchivedAt());
    m.put("deletedAt", r.getDeletedAt());
    m.put(
        "_redacted",
        "Run-scoped SCM credentials (GitHub PAT, Bitbucket app password) and internal execution"
            + " state are never exported.");
    return m;
  }

  /** RR-204 — deny-by-default projection of {@link Notification}. */
  private static Map<String, Object> exportNotification(Notification n) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", n.getId());
    m.put("type", n.getType());
    m.put("title", n.getTitle());
    m.put("body", n.getBody());
    m.put("link", n.getLink());
    m.put("read", n.isRead());
    m.put("createdAt", n.getCreatedAt());
    return m;
  }

  /**
   * RR-204 — deny-by-default projection of {@link BillingSubscription}. Gateway customer /
   * subscription identifiers and the free-form {@code metadata} blob are deliberately excluded.
   */
  private static Map<String, Object> exportSubscription(BillingSubscription s) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", s.getId());
    m.put("status", s.getStatus());
    m.put("paymentGateway", s.getPaymentGateway());
    m.put("currentPeriodStart", s.getCurrentPeriodStart());
    m.put("currentPeriodEnd", s.getCurrentPeriodEnd());
    m.put("totalSeats", s.getTotalSeats());
    m.put("cancelAtPeriodEnd", s.isCancelAtPeriodEnd());
    m.put("cancelledAt", s.getCancelledAt());
    m.put("termsAcceptedAt", s.getTermsAcceptedAt());
    m.put("termsVersion", s.getTermsVersion());
    m.put("createdAt", s.getCreatedAt());
    return m;
  }

  /**
   * RR-204 — deny-by-default projection of {@link Invoice}. Gateway payment/order identifiers, the
   * PDF storage path and the free-form {@code metadata} blob are deliberately excluded.
   */
  private static Map<String, Object> exportInvoice(Invoice i) {
    Map<String, Object> m = new HashMap<>();
    m.put("id", i.getId());
    m.put("invoiceNumber", i.getInvoiceNumber());
    m.put("invoiceType", i.getInvoiceType());
    m.put("status", i.getStatus());
    m.put("currency", i.getCurrency());
    m.put("subtotal", i.getSubtotal());
    m.put("totalTax", i.getTotalTax());
    m.put("totalAmount", i.getTotalAmount());
    m.put("invoiceDate", i.getInvoiceDate());
    m.put("dueDate", i.getDueDate());
    m.put("paidAt", i.getPaidAt());
    m.put("paymentMethod", i.getPaymentMethod());
    m.put("description", i.getDescription());
    m.put("buyerName", i.getBuyerName());
    m.put("buyerGstin", i.getBuyerGstin());
    m.put("createdAt", i.getCreatedAt());
    return m;
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
            + "- `projects` — only projects you created or are a member of (NOT every project in\n"
            + "  your org).\n"
            + "- `runs` — up to "
            + EXPORT_ROW_LIMIT
            + " pipeline runs you submitted.\n"
            + "- `notifications` — up to "
            + EXPORT_ROW_LIMIT
            + " in-app notifications.\n"
            + "- `billing.activeSubscription` — current paid subscription (or absent).\n"
            + "- `billing.invoices` — up to "
            + EXPORT_ROW_LIMIT
            + " invoices.\n\n"
            + "Each list is capped at "
            + EXPORT_ROW_LIMIT
            + " rows and bounded by a total serialized-byte budget for in-memory export safety.\n"
            + "`manifest.json` → `truncation` reports the TRUE total row count per collection and\n"
            + "whether it was truncated (`manifest.truncated` is true if any collection was cut).\n"
            + "If truncated, contact admin@executespec.ai for a full paged export.\n\n"
            + "## What's redacted\n\n"
            + "Your password hash + LLM API keys + GitHub PAT are intentionally excluded.\n"
            + "You can recover or rotate them from Settings → Account.\n\n"
            + "Every section of `payload.json` is built from an explicit field allowlist rather\n"
            + "than a dump of the stored row, so shared org provider keys, JIRA/Confluence API\n"
            + "tokens, run-scoped SCM credentials, payment-gateway identifiers and internal\n"
            + "execution state are structurally absent — not merely omitted by convention.\n\n"
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
