package com.navneet.executespec.api.controller;

import java.time.LocalDate;
import java.util.List;

import com.navneet.executespec.api.dto.BillingOverview;
import com.navneet.executespec.api.dto.MemberUsage;
import com.navneet.executespec.api.dto.UsageMetrics;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.billing.service.AnalyticsService;
import com.navneet.executespec.cost.LlmUsageAuditQuery;
import com.navneet.executespec.cost.LlmUsageAuditReport;
import com.navneet.executespec.cost.LlmUsageAuditService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin analytics endpoints.
 *
 * <p>RBAC:
 *
 * <ul>
 *   <li>SUPER_ADMIN — any entity
 *   <li>ORG_ADMIN — own org only
 *   <li>BILLING_ADMIN — own org billing only
 *   <li>Standalone user — own usage and billing only
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/analytics")
@RequiredArgsConstructor
@Slf4j
public class AdminAnalyticsController {

  private final AnalyticsService analyticsService;
  private final LlmUsageAuditService llmUsageAuditService;
  private final RunAccessGuard guard;

  /** GET /api/admin/analytics/usage?entityType={USER|ORG}&entityId={id}&period=30 */
  @GetMapping("/usage")
  public ResponseEntity<UsageMetrics> usage(
      @RequestParam String entityType,
      @RequestParam String entityId,
      @RequestParam(defaultValue = "30") int period,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    String normalizedType = normalizeEntityType(entityType);
    assertAnalyticsAccess(normalizedType, entityId, userDetails, false);
    return ResponseEntity.ok(analyticsService.getUsageMetrics(normalizedType, entityId, period));
  }

  /** GET /api/admin/analytics/members?orgId={}&period=30 */
  @GetMapping("/members")
  public ResponseEntity<List<MemberUsage>> members(
      @RequestParam String orgId,
      @RequestParam(defaultValue = "30") int period,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    assertAnalyticsAccess("ORG", orgId, userDetails, false);
    return ResponseEntity.ok(analyticsService.getMemberUsage(orgId, period));
  }

  /** GET /api/admin/analytics/billing?entityType={USER|ORG}&entityId={id} */
  @GetMapping("/billing")
  public ResponseEntity<BillingOverview> billing(
      @RequestParam String entityType,
      @RequestParam String entityId,
      @AuthenticationPrincipal AppUserDetails userDetails) {

    String normalizedType = normalizeEntityType(entityType);
    assertAnalyticsAccess(normalizedType, entityId, userDetails, true);
    return ResponseEntity.ok(analyticsService.getBillingOverview(normalizedType, entityId));
  }

  /**
   * v2.5.8 — cost breakdown for FinOps dashboards. GET
   * /api/admin/analytics/cost-breakdown?orgId={}&period=30
   *
   * <p>Returns per-user and per-project $ spent across pipeline_run + execution_run for the given
   * period. Critical for engineering-manager spend attribution; previously there was no endpoint
   * that summed cost by user or by project.
   */
  @GetMapping("/cost-breakdown")
  public ResponseEntity<java.util.Map<String, Object>> costBreakdown(
      @RequestParam String orgId,
      @RequestParam(defaultValue = "30") int period,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    assertAnalyticsAccess("ORG", orgId, userDetails, true);
    return ResponseEntity.ok(analyticsService.getCostBreakdown(orgId, period));
  }

  /**
   * Canonical per-attempt LLM token/cost audit. Dates are inclusive org-calendar dates; the IANA
   * timezone is converted to an explicit half-open instant interval before SQL executes.
   */
  @GetMapping("/llm-usage-audit")
  public ResponseEntity<LlmUsageAuditReport> llmUsageAudit(
      @RequestParam String orgId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "UTC") String timezone,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    assertLlmAuditAccess(orgId, userDetails);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(llmUsageAuditService.report(auditQuery(orgId, from, to, timezone, page, size)));
  }

  /**
   * Downloadable JSON form of the same bounded report. This is intentionally not an unbounded
   * database dump: attempts retain the normal 500-row page ceiling and rollups retain their
   * independent 500-row ceiling, both advertised in the response body.
   */
  @GetMapping(value = "/llm-usage-audit.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<LlmUsageAuditReport> llmUsageAuditJson(
      @RequestParam String orgId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "UTC") String timezone,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "500") int size,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    assertLlmAuditAccess(orgId, userDetails);
    LlmUsageAuditQuery query = auditQuery(orgId, from, to, timezone, page, size);
    String safeOrg = orgId.replaceAll("[^A-Za-z0-9_-]", "_");
    String filename = "llm-usage-" + safeOrg + "-" + from + "-to-" + to + ".json";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(llmUsageAuditService.report(query));
  }

  /** Spreadsheet-safe, UTF-8 CSV detail export capped at 10,000 physical attempts. */
  @GetMapping(value = "/llm-usage-audit.csv", produces = "text/csv")
  public ResponseEntity<byte[]> llmUsageAuditCsv(
      @RequestParam String orgId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(defaultValue = "UTC") String timezone,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    assertLlmAuditAccess(orgId, userDetails);
    var export = llmUsageAuditService.csv(auditQuery(orgId, from, to, timezone, 0, 100));
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
        .header("X-Export-Row-Limit", String.valueOf(LlmUsageAuditQuery.CSV_ROW_LIMIT))
        .header("X-Export-Row-Count", String.valueOf(export.rowCount()))
        .header("X-Export-Truncated", String.valueOf(export.truncated()))
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
        .body(export.content());
  }

  private static LlmUsageAuditQuery auditQuery(
      String orgId, LocalDate from, LocalDate to, String timezone, int page, int size) {
    try {
      return LlmUsageAuditQuery.of(orgId, from, to, timezone, page, size);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
    }
  }

  /** Detailed physical-attempt facts are restricted to tenant admins, not billing-only members. */
  private void assertLlmAuditAccess(String orgId, AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    AppUser user = userDetails.getUser();
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;
    OrgMember member = userDetails.getOrgMember();
    if (member != null
        && orgId.equals(user.getOrgId())
        && orgId.equals(member.getOrgId())
        && "ORG_ADMIN".equals(member.getOrgRole())) return;
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
  }

  /**
   * Asserts the caller is allowed to view analytics for the given entity.
   *
   * @param billingOnly if true, BILLING_ADMIN is also allowed (for billing endpoints)
   */
  private void assertAnalyticsAccess(
      String entityType, String entityId, AppUserDetails userDetails, boolean billingOnly) {
    guard.requireAuthenticated(userDetails);
    AppUser user = userDetails.getUser();

    // SUPER_ADMIN — unrestricted
    if ("SUPER_ADMIN".equals(user.getGlobalRole())) return;

    OrgMember orgMember = userDetails.getOrgMember();

    if ("ORG".equals(entityType)) {
      // ORG_ADMIN or BILLING_ADMIN for own org
      if (orgMember != null && entityId.equals(user.getOrgId())) {
        if ("ORG_ADMIN".equals(orgMember.getOrgRole())) return;
        if (billingOnly && "BILLING_ADMIN".equals(orgMember.getOrgRole())) return;
      }
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    if ("USER".equals(entityType)) {
      // Standalone user can view own data
      if (entityId.equals(user.getId())) return;

      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
    }

    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST, "Invalid entityType: " + entityType + ". Must be USER or ORG.");
  }

  private static String normalizeEntityType(String entityType) {
    if (entityType == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityType is required");
    }
    String normalized = entityType.trim().toUpperCase(java.util.Locale.ROOT);
    if (!"USER".equals(normalized) && !"ORG".equals(normalized)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid entityType: " + entityType + ". Must be USER or ORG.");
    }
    return normalized;
  }
}
