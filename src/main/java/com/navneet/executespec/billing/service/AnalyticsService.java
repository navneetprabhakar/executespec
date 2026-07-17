package com.navneet.executespec.billing.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import com.navneet.executespec.api.dto.BillingOverview;
import com.navneet.executespec.api.dto.MemberUsage;
import com.navneet.executespec.api.dto.UsageMetrics;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.repository.InvoiceRepository;
import com.navneet.executespec.billing.repository.RunTopupRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

  private final JdbcTemplate jdbc;
  private final BillingSubscriptionRepository subscriptionRepo;
  private final BillingPlanRepository planRepo;
  private final InvoiceRepository invoiceRepo;
  private final RunTopupRepository topupRepo;
  private final AppUserRepository userRepo;
  private final OrgRepository orgRepo;

  /**
   * Get usage metrics for a user or org over a given period.
   *
   * @param entityType "USER" or "ORG"
   * @param entityId the user ID or org ID
   * @param periodDays number of days to look back
   */
  public UsageMetrics getUsageMetrics(String entityType, String entityId, int periodDays) {
    entityType = normalizeEntityType(entityType);
    Instant since = Instant.now().minus(periodDays, ChronoUnit.DAYS);

    // --- Aggregate run counts ---
    String countSql;
    Object[] countParams;
    if ("ORG".equals(entityType)) {
      countSql =
          """
          SELECT
              COUNT(*)                                                          AS total,
              SUM(CASE WHEN status = 'COMPLETE' THEN 1 ELSE 0 END)              AS success,
              SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END)                AS failed,
              SUM(CASE WHEN status = 'PARTIAL' THEN 1 ELSE 0 END)               AS partial,
              COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - started_at)))
                       FILTER (WHERE status = 'COMPLETE'), 0)                    AS avg_time,
              COALESCE(SUM(actual_cost_usd), 0)                                  AS total_cost
          FROM pipeline_run
          WHERE org_id = ? AND started_at > ?
          """;
      countParams = new Object[] {entityId, Timestamp.from(since)};
    } else {
      countSql =
          """
          SELECT
              COUNT(*)                                                          AS total,
              SUM(CASE WHEN status = 'COMPLETE' THEN 1 ELSE 0 END)              AS success,
              SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END)                AS failed,
              SUM(CASE WHEN status = 'PARTIAL' THEN 1 ELSE 0 END)               AS partial,
              COALESCE(AVG(EXTRACT(EPOCH FROM (completed_at - started_at)))
                       FILTER (WHERE status = 'COMPLETE'), 0)                    AS avg_time,
              COALESCE(SUM(actual_cost_usd), 0)                                  AS total_cost
          FROM pipeline_run
          WHERE created_by_user_id = ? AND started_at > ?
          """;
      countParams = new Object[] {entityId, Timestamp.from(since)};
    }

    int totalRuns = 0, successfulRuns = 0, failedRuns = 0, partialRuns = 0;
    double avgTime = 0, totalCost = 0;

    var countRows = jdbc.queryForList(countSql, countParams);
    if (!countRows.isEmpty()) {
      var row = countRows.get(0);
      totalRuns = ((Number) row.getOrDefault("total", 0)).intValue();
      successfulRuns = ((Number) row.getOrDefault("success", 0)).intValue();
      failedRuns = ((Number) row.getOrDefault("failed", 0)).intValue();
      partialRuns = ((Number) row.getOrDefault("partial", 0)).intValue();
      avgTime = ((Number) row.getOrDefault("avg_time", 0)).doubleValue();
      totalCost = ((Number) row.getOrDefault("total_cost", 0)).doubleValue();
    }

    // --- Runs per day ---
    String dailySql;
    Object[] dailyParams;
    if ("ORG".equals(entityType)) {
      dailySql =
          """
          SELECT DATE(started_at) AS day, COUNT(*) AS total,
                 SUM(CASE WHEN status='COMPLETE' THEN 1 ELSE 0 END) AS success
          FROM pipeline_run
          WHERE org_id = ? AND started_at > ?
          GROUP BY DATE(started_at) ORDER BY day
          """;
      dailyParams = new Object[] {entityId, Timestamp.from(since)};
    } else {
      dailySql =
          """
          SELECT DATE(started_at) AS day, COUNT(*) AS total,
                 SUM(CASE WHEN status='COMPLETE' THEN 1 ELSE 0 END) AS success
          FROM pipeline_run
          WHERE created_by_user_id = ? AND started_at > ?
          GROUP BY DATE(started_at) ORDER BY day
          """;
      dailyParams = new Object[] {entityId, Timestamp.from(since)};
    }

    List<UsageMetrics.DailyRunStat> runsByDay = new ArrayList<>();
    jdbc.queryForList(dailySql, dailyParams)
        .forEach(
            row -> {
              String date = row.get("day").toString();
              int count = ((Number) row.get("total")).intValue();
              int success = ((Number) row.get("success")).intValue();
              double rate = count > 0 ? (success * 100.0 / count) : 0;
              runsByDay.add(
                  new UsageMetrics.DailyRunStat(date, count, Math.round(rate * 10) / 10.0));
            });

    // --- Runs by project ---
    String projectSql;
    Object[] projectParams;
    if ("ORG".equals(entityType)) {
      projectSql =
          """
          SELECT COALESCE(p.name, 'No Project') AS project_name, COUNT(*) AS cnt
          FROM pipeline_run r
          LEFT JOIN project p ON r.project_id = p.id
          WHERE r.org_id = ? AND r.started_at > ?
          GROUP BY project_name ORDER BY cnt DESC LIMIT 20
          """;
      projectParams = new Object[] {entityId, Timestamp.from(since)};
    } else {
      projectSql =
          """
          SELECT COALESCE(p.name, 'No Project') AS project_name, COUNT(*) AS cnt
          FROM pipeline_run r
          LEFT JOIN project p ON r.project_id = p.id
          WHERE r.created_by_user_id = ? AND r.started_at > ?
          GROUP BY project_name ORDER BY cnt DESC LIMIT 20
          """;
      projectParams = new Object[] {entityId, Timestamp.from(since)};
    }

    Map<String, Integer> runsByProject = new LinkedHashMap<>();
    jdbc.queryForList(projectSql, projectParams)
        .forEach(
            row ->
                runsByProject.put(
                    (String) row.get("project_name"), ((Number) row.get("cnt")).intValue()));

    // --- Cost by model ---
    // V258 stores task-scoped spend directly. Keep this query fail-soft for databases that have
    // not yet upgraded; the run-level totals above remain authoritative during the rollout.
    Map<String, Double> costByModel = new LinkedHashMap<>();
    try {
      String costSql;
      Object[] costParams;
      if ("ORG".equals(entityType)) {
        costSql =
            """
SELECT m.model_name AS model, COUNT(*) AS calls, COALESCE(SUM(m.actual_cost_usd), 0) AS total_cost_usd
FROM model_task_metric m
JOIN pipeline_run r ON m.run_id = r.run_id
WHERE r.org_id = ? AND m.created_at > ?
GROUP BY m.model_name ORDER BY calls DESC
""";
        costParams = new Object[] {entityId, Timestamp.from(since)};
      } else {
        costSql =
            """
SELECT m.model_name AS model, COUNT(*) AS calls, COALESCE(SUM(m.actual_cost_usd), 0) AS total_cost_usd
FROM model_task_metric m
JOIN pipeline_run r ON m.run_id = r.run_id
WHERE r.created_by_user_id = ? AND m.created_at > ?
GROUP BY m.model_name ORDER BY calls DESC
""";
        costParams = new Object[] {entityId, Timestamp.from(since)};
      }

      jdbc.queryForList(costSql, costParams)
          .forEach(
              row ->
                  costByModel.put(
                      (String) row.get("model"),
                      ((Number) row.getOrDefault("total_cost_usd", 0)).doubleValue()));
    } catch (Exception e) {
      log.warn("Failed to query cost by model: {}", e.getMessage());
    }

    return new UsageMetrics(
        totalRuns,
        successfulRuns,
        failedRuns,
        partialRuns,
        runsByDay,
        runsByProject,
        avgTime,
        totalCost,
        costByModel);
  }

  // Member Usage (ORG context only)

  public List<MemberUsage> getMemberUsage(String orgId, int periodDays) {
    Instant since = Instant.now().minus(periodDays, ChronoUnit.DAYS);

    String sql =
        """
        SELECT u.id, u.first_name, u.last_name, u.email,
               COUNT(r.run_id) AS runs,
               SUM(CASE WHEN r.status = 'COMPLETE' THEN 1 ELSE 0 END) AS success,
               MAX(r.started_at) AS last_run
        FROM app_user u
        LEFT JOIN pipeline_run r ON r.created_by_user_id = u.id
            AND r.org_id = ? AND r.started_at > ?
        WHERE u.org_id = ? AND u.status = 'ACTIVE'
        GROUP BY u.id, u.first_name, u.last_name, u.email
        ORDER BY runs DESC
        """;

    return jdbc.queryForList(sql, orgId, Timestamp.from(since), orgId).stream()
        .map(
            row -> {
              int runs = ((Number) row.getOrDefault("runs", 0)).intValue();
              int success = ((Number) row.getOrDefault("success", 0)).intValue();
              double rate = runs > 0 ? (success * 100.0 / runs) : 0;
              Timestamp lastRun = (Timestamp) row.get("last_run");
              return new MemberUsage(
                  (String) row.get("id"),
                  (String) row.get("first_name"),
                  (String) row.get("last_name"),
                  (String) row.get("email"),
                  runs,
                  Math.round(rate * 10) / 10.0,
                  lastRun != null ? lastRun.toInstant() : null);
            })
        .toList();
  }

  public BillingOverview getBillingOverview(String entityType, String entityId) {
    entityType = normalizeEntityType(entityType);
    String entityName;
    String currentPlan = "FREE";
    String subStatus = "FREE";
    Instant nextBilling = null;
    int periodUsage = 0;
    int runLimit = 5; // FREE default
    String gateway = null;

    if ("ORG".equals(entityType)) {
      Org org = orgRepo.findById(entityId).orElse(null);
      entityName = org != null ? org.getName() : entityId;
      if (org != null) {
        periodUsage = org.getMonthlyRunCount();
      }
    } else {
      AppUser user = userRepo.findById(entityId).orElse(null);
      entityName =
          user != null
              ? (user.getFirstName() + " " + Optional.ofNullable(user.getLastName()).orElse(""))
              : entityId;
      if (user != null) {
        periodUsage = user.getPersonalRunCount();
      }
    }

    // Find active subscription
    BillingSubscription sub = null;
    if ("ORG".equals(entityType)) {
      // N2 (audit 2026-05-29) — filtered in the DB (was findAll().stream().filter(orgId + status)).
      sub = subscriptionRepo.findActiveByOrgId(entityId).stream().findFirst().orElse(null);
    } else {
      sub = subscriptionRepo.findActiveByUserId(entityId).orElse(null);
    }

    if (sub != null) {
      subStatus = sub.getStatus();
      nextBilling = sub.getCurrentPeriodEnd();
      gateway = sub.getGateway();

      // Resolve plan name and run limit
      BillingPlan plan = planRepo.findById(sub.getBillingPlanId()).orElse(null);
      if (plan != null) {
        currentPlan =
            plan.getTier() + (plan.getPeriod() != null ? " (" + plan.getPeriod() + ")" : "");
        runLimit = plan.getRunLimit();
      }
    }

    int remainingRuns = Math.max(0, runLimit - periodUsage);

    // Topup credits
    int topupCredits;
    if ("ORG".equals(entityType)) {
      topupCredits = (int) topupRepo.sumRemainingByOrgId(entityId, Instant.now());
    } else {
      topupCredits = (int) topupRepo.sumRemainingByUserId(entityId, Instant.now());
    }

    // Recent invoices (last 10)
    List<BillingOverview.RecentInvoice> recentInvoices;
    if ("ORG".equals(entityType)) {
      recentInvoices =
          invoiceRepo
              .findByOrgIdOrderByInvoiceDateDesc(entityId, PageRequest.of(0, 10))
              .getContent()
              .stream()
              .map(this::toRecentInvoice)
              .toList();
    } else {
      recentInvoices =
          invoiceRepo
              .findByUserIdOrderByInvoiceDateDesc(entityId, PageRequest.of(0, 10))
              .getContent()
              .stream()
              .map(this::toRecentInvoice)
              .toList();
    }

    return new BillingOverview(
        entityType,
        entityName,
        currentPlan,
        subStatus,
        nextBilling,
        periodUsage,
        remainingRuns,
        runLimit,
        gateway,
        topupCredits,
        recentInvoices);
  }

  private BillingOverview.RecentInvoice toRecentInvoice(Invoice inv) {
    return new BillingOverview.RecentInvoice(
        inv.getInvoiceNumber(),
        inv.getTotalAmount() / 100.0, // paise → rupees/dollars
        inv.getCurrency(),
        inv.getStatus(),
        inv.getInvoiceDate() != null
            ? inv.getInvoiceDate().atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
            : inv.getCreatedAt());
  }

  // Cost Breakdown (v2.5.8)
  //
  // FinOps support for engineering managers. Returns per-user and per-project
  // spend across pipeline_run + execution_run so teams can attribute LLM spend
  // to the right cost centers.

  /**
   * v2.5.8 — returns a nested map:
   *
   * <pre>
   * {
   *   "byUser": [ {userId, name, email, totalCostUsd, runCount}, ... ],
   *   "byProject": [ {projectId, name, totalCostUsd, runCount}, ... ],
   *   "totalCostUsd": <sum>
   * }
   * </pre>
   */
  public java.util.Map<String, Object> getCostBreakdown(String orgId, int periodDays) {
    Instant since = Instant.now().minus(periodDays, java.time.temporal.ChronoUnit.DAYS);

    // Per-user spend (pipeline_run + execution_run cost)
    String userSql =
        "SELECT u.id, u.first_name, u.last_name, u.email, "
            + "COALESCE(SUM(p.actual_cost_usd), 0.0) + "
            + "COALESCE((SELECT SUM(e.total_cost_usd) FROM execution_run e "
            + "          WHERE e.created_by = u.id AND e.deleted_at IS NULL "
            + "          AND e.created_at >= ?), 0.0) AS total_cost, "
            + "COUNT(p.run_id) AS run_count "
            + "FROM app_user u "
            + "LEFT JOIN pipeline_run p ON p.created_by_user_id = u.id AND p.started_at >= ? "
            + "WHERE u.org_id = ? "
            + "GROUP BY u.id, u.first_name, u.last_name, u.email "
            + "ORDER BY total_cost DESC";
    List<java.util.Map<String, Object>> byUser =
        jdbc.query(
            userSql,
            (rs, i) -> {
              java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
              row.put("userId", rs.getString("id"));
              String fn = rs.getString("first_name");
              String ln = rs.getString("last_name");
              row.put("name", (fn == null ? "" : fn) + " " + (ln == null ? "" : ln));
              row.put("email", rs.getString("email"));
              row.put("totalCostUsd", rs.getDouble("total_cost"));
              row.put("runCount", rs.getLong("run_count"));
              return row;
            },
            java.sql.Timestamp.from(since),
            java.sql.Timestamp.from(since),
            orgId);

    // Per-project spend
    String projectSql =
        "SELECT pr.id, pr.name, "
            + "COALESCE(SUM(p.actual_cost_usd), 0.0) + "
            + "COALESCE((SELECT SUM(e.total_cost_usd) FROM execution_run e "
            + "          WHERE e.project_id = pr.id AND e.deleted_at IS NULL "
            + "          AND e.created_at >= ?), 0.0) AS total_cost, "
            + "COUNT(p.run_id) AS run_count "
            + "FROM project pr "
            + "LEFT JOIN pipeline_run p ON p.project_id = pr.id AND p.started_at >= ? "
            + "WHERE pr.org_id = ? "
            + "GROUP BY pr.id, pr.name "
            + "ORDER BY total_cost DESC";
    List<java.util.Map<String, Object>> byProject =
        jdbc.query(
            projectSql,
            (rs, i) -> {
              java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
              row.put("projectId", rs.getString("id"));
              row.put("name", rs.getString("name"));
              row.put("totalCostUsd", rs.getDouble("total_cost"));
              row.put("runCount", rs.getLong("run_count"));
              return row;
            },
            java.sql.Timestamp.from(since),
            java.sql.Timestamp.from(since),
            orgId);

    double total =
        byUser.stream().mapToDouble(r -> ((Number) r.get("totalCostUsd")).doubleValue()).sum();

    return java.util.Map.of(
        "byUser", byUser,
        "byProject", byProject,
        "totalCostUsd", total,
        "periodDays", periodDays);
  }

  /**
   * Enterprise token audit over the canonical physical-attempt ledger. Unlike run totals or
   * agent-session rollups, this includes failed retries/failovers and groups the same rows by user,
   * project, provider, model, agent role, feature, key source, and outcome.
   */
  public Map<String, Object> getLlmUsageAudit(String orgId, int periodDays) {
    int boundedPeriod = Math.max(1, Math.min(periodDays, 366));
    Instant since = Instant.now().minus(boundedPeriod, ChronoUnit.DAYS);
    Timestamp sinceTimestamp = Timestamp.from(since);

    String aggregateSql =
        """
        SELECT COUNT(*) AS attempts,
               COUNT(*) FILTER (WHERE attempt_status = 'SUCCESS') AS successful_attempts,
               COUNT(*) FILTER (WHERE attempt_status = 'FAILED') AS failed_attempts,
               COALESCE(SUM(input_tokens), 0) AS input_tokens,
               COALESCE(SUM(output_tokens), 0) AS output_tokens,
               COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
               COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
               COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens,
               COALESCE(SUM(cost_usd), 0) AS cost_usd
        FROM run_step_usage
        WHERE org_id = ? AND created_at >= ?
        """;
    Map<String, Object> totals =
        jdbc.queryForList(aggregateSql, orgId, sinceTimestamp).stream()
            .findFirst()
            .map(AnalyticsService::normalizedUsageRow)
            .orElseGet(LinkedHashMap::new);

    List<Map<String, Object>> byUser =
        groupedUsage(
            """
            SELECT u.user_id AS id,
                   COALESCE(a.email, 'Unknown') AS label,
                   COUNT(*) AS attempts,
                   COUNT(*) FILTER (WHERE u.attempt_status = 'FAILED') AS failed_attempts,
                   COALESCE(SUM(u.input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(u.output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(u.cache_read_tokens), 0) AS cache_read_tokens,
                   COALESCE(SUM(u.cache_write_tokens), 0) AS cache_write_tokens,
                   COALESCE(SUM(u.reasoning_tokens), 0) AS reasoning_tokens,
                   COALESCE(SUM(u.cost_usd), 0) AS cost_usd
            FROM run_step_usage u
            LEFT JOIN app_user a ON a.id = u.user_id
            WHERE u.org_id = ? AND u.created_at >= ?
            GROUP BY u.user_id, a.email
            ORDER BY cost_usd DESC, attempts DESC
            """,
            orgId,
            sinceTimestamp);
    List<Map<String, Object>> byProject =
        groupedUsage(
            """
            SELECT u.project_id AS id,
                   COALESCE(p.name, 'No project') AS label,
                   COUNT(*) AS attempts,
                   COUNT(*) FILTER (WHERE u.attempt_status = 'FAILED') AS failed_attempts,
                   COALESCE(SUM(u.input_tokens), 0) AS input_tokens,
                   COALESCE(SUM(u.output_tokens), 0) AS output_tokens,
                   COALESCE(SUM(u.cache_read_tokens), 0) AS cache_read_tokens,
                   COALESCE(SUM(u.cache_write_tokens), 0) AS cache_write_tokens,
                   COALESCE(SUM(u.reasoning_tokens), 0) AS reasoning_tokens,
                   COALESCE(SUM(u.cost_usd), 0) AS cost_usd
            FROM run_step_usage u
            LEFT JOIN project p ON p.id = u.project_id AND p.org_id = u.org_id
            WHERE u.org_id = ? AND u.created_at >= ?
            GROUP BY u.project_id, p.name
            ORDER BY cost_usd DESC, attempts DESC
            """,
            orgId,
            sinceTimestamp);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("source", "run_step_usage_attempt_ledger");
    result.put("orgId", orgId);
    result.put("periodDays", boundedPeriod);
    result.put("since", since);
    result.put("totals", totals);
    result.put("byUser", byUser);
    result.put("byUserModelRole", userModelRoleUsage(orgId, sinceTimestamp));
    result.put("byProject", byProject);
    result.put("byProvider", groupedDimension("provider", orgId, sinceTimestamp));
    result.put("byModel", groupedDimension("model", orgId, sinceTimestamp));
    result.put("byRole", groupedDimension("agent_role", orgId, sinceTimestamp));
    result.put("byFeature", groupedDimension("feature", orgId, sinceTimestamp));
    result.put("byKeySource", groupedDimension("key_source", orgId, sinceTimestamp));
    result.put("byStatus", groupedDimension("attempt_status", orgId, sinceTimestamp));
    return result;
  }

  private List<Map<String, Object>> userModelRoleUsage(String orgId, Timestamp since) {
    return groupedUsage(
        """
        SELECT u.user_id,
               COALESCE(a.email, 'Unknown') AS user_label,
               COALESCE(u.provider, 'Unknown') AS provider,
               COALESCE(u.model, 'Unknown') AS model,
               COALESCE(u.agent_role, 'Unknown') AS agent_role,
               COALESCE(u.feature, 'Unknown') AS feature,
               COALESCE(u.key_source, 'UNKNOWN') AS key_source,
               COUNT(*) AS attempts,
               COUNT(*) FILTER (WHERE u.attempt_status = 'FAILED') AS failed_attempts,
               COALESCE(SUM(u.input_tokens), 0) AS input_tokens,
               COALESCE(SUM(u.output_tokens), 0) AS output_tokens,
               COALESCE(SUM(u.cache_read_tokens), 0) AS cache_read_tokens,
               COALESCE(SUM(u.cache_write_tokens), 0) AS cache_write_tokens,
               COALESCE(SUM(u.reasoning_tokens), 0) AS reasoning_tokens,
               COALESCE(SUM(u.cost_usd), 0) AS cost_usd
        FROM run_step_usage u
        LEFT JOIN app_user a ON a.id = u.user_id
        WHERE u.org_id = ? AND u.created_at >= ?
        GROUP BY u.user_id, a.email, u.provider, u.model, u.agent_role, u.feature, u.key_source
        ORDER BY cost_usd DESC, attempts DESC
        """,
        orgId,
        since);
  }

  private List<Map<String, Object>> groupedDimension(String column, String orgId, Timestamp since) {
    Set<String> allowed =
        Set.of("provider", "model", "agent_role", "feature", "key_source", "attempt_status");
    if (!allowed.contains(column))
      throw new IllegalArgumentException("unsupported usage dimension");
    String sql =
        "SELECT COALESCE("
            + column
            + ", 'Unknown') AS label, COUNT(*) AS attempts, "
            + "COUNT(*) FILTER (WHERE attempt_status = 'FAILED') AS failed_attempts, "
            + "COALESCE(SUM(input_tokens), 0) AS input_tokens, "
            + "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
            + "COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens, "
            + "COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens, "
            + "COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens, "
            + "COALESCE(SUM(cost_usd), 0) AS cost_usd "
            + "FROM run_step_usage WHERE org_id = ? AND created_at >= ? "
            + "GROUP BY "
            + column
            + " ORDER BY cost_usd DESC, attempts DESC";
    return groupedUsage(sql, orgId, since);
  }

  private List<Map<String, Object>> groupedUsage(String sql, String orgId, Timestamp since) {
    return jdbc.queryForList(sql, orgId, since).stream()
        .map(AnalyticsService::normalizedUsageRow)
        .toList();
  }

  private static Map<String, Object> normalizedUsageRow(Map<String, Object> source) {
    Map<String, Object> row = new LinkedHashMap<>();
    source.forEach(
        (key, value) ->
            row.put(
                key.toLowerCase(Locale.ROOT),
                value instanceof java.math.BigDecimal decimal ? decimal.doubleValue() : value));
    return row;
  }

  private static String normalizeEntityType(String entityType) {
    if (entityType == null) return "USER";
    return entityType.trim().toUpperCase(Locale.ROOT);
  }
}
