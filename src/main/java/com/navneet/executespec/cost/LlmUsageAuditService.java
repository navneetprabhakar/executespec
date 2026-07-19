package com.navneet.executespec.cost;

import static com.navneet.executespec.cost.SpreadsheetSafeCsv.number;
import static com.navneet.executespec.cost.SpreadsheetSafeCsv.text;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.cost.LlmUsageAuditReport.Limits;
import com.navneet.executespec.cost.LlmUsageAuditReport.Page;
import com.navneet.executespec.cost.LlmUsageAuditReport.Reconciliation;
import com.navneet.executespec.cost.LlmUsageAuditReport.ReconciliationResult;
import com.navneet.executespec.cost.LlmUsageAuditReport.UsageAttempt;
import com.navneet.executespec.cost.LlmUsageAuditReport.UsageRollup;
import com.navneet.executespec.cost.LlmUsageAuditReport.UsageTotals;
import com.navneet.executespec.cost.LlmUsageAuditReport.Window;
import com.navneet.executespec.cost.LlmUsageReconciliation.UsageSide;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Bounded Enterprise audit/reporting surface over immutable physical provider attempts. */
@Service
@RequiredArgsConstructor
public class LlmUsageAuditService {

  private static final String RESOLVED_CTE =
      """
WITH resolved AS (
  SELECT i.physical_attempt_id AS id,
         COALESCE(u.created_at, i.started_at) AS created_at,
         i.llm_execution_context_id,
         i.correlation_id,
         i.org_id,
         i.user_id,
         i.project_id,
         u.repo_id,
         i.feature,
         i.source_type,
         i.source_id,
         i.run_id,
         i.task_id,
         i.agent_role,
         i.step_id,
         u.retry_of,
         i.provider,
         i.model,
         i.model_tier,
         i.logical_call_id,
         i.physical_attempt_id,
         i.parent_attempt_id,
         i.attempt_kind,
         i.attempt_ordinal,
         i.key_source,
         COALESCE(u.attempt_status, 'MISSING_TERMINAL') AS attempt_status,
         COALESCE(u.attempt_number, i.attempt_ordinal)::int AS attempt_number,
         COALESCE(u.latency_ms, 0) AS latency_ms,
         COALESCE(u.input_tokens, 0) AS input_tokens,
         COALESCE(u.output_tokens, 0) AS output_tokens,
         COALESCE(u.cache_read_tokens, 0) AS cache_read_tokens,
         COALESCE(u.cache_write_tokens, 0) AS cache_write_tokens,
         COALESCE(u.reasoning_tokens, 0) AS reasoning_tokens,
         u.cost_usd,
         u.provider_usage_raw_json,
         u.error_code,
         COALESCE(m.ai_credits_charged, 0) AS managed_ai_credits,
         CASE
           WHEN m.id IS NOT NULL THEN 'RECORDED'
           WHEN u.id IS NULL THEN 'PENDING_TERMINAL'
           WHEN i.key_source IN ('USER', 'ORG') THEN 'NOT_APPLICABLE'
           ELSE 'UNRECONCILED'
         END AS managed_ai_projection_status,
         a.email AS user_email,
         i.human_role_snapshot AS user_role,
         p.name AS project_name,
         COALESCE(run_direct.run_id, run_source.run_id, turn_run.run_id,
                  session_direct.run_id, session_source.run_id) AS resolved_run_id,
         COALESCE(turn_row.session_id, session_direct.id, session_source.id)
           AS resolved_session_id,
         CASE
           WHEN u.attempt_status = 'SUCCESS' AND u.cost_usd >= 0 THEN u.cost_usd::numeric
           WHEN jsonb_typeof(u.provider_usage_raw_json->'provider_upstream_cost_usd') = 'number'
                AND (u.provider_usage_raw_json->>'provider_upstream_cost_usd')::numeric >= 0
             THEN (u.provider_usage_raw_json->>'provider_upstream_cost_usd')::numeric
           WHEN jsonb_typeof(u.provider_usage_raw_json->'provider_reported_cost_usd') = 'number'
                AND (u.provider_usage_raw_json->>'provider_reported_cost_usd')::numeric >= 0
             THEN (u.provider_usage_raw_json->>'provider_reported_cost_usd')::numeric
           WHEN jsonb_typeof(u.provider_usage_raw_json->'accounted_cost_usd') = 'number'
                AND (u.provider_usage_raw_json->>'accounted_cost_usd')::numeric >= 0
             THEN (u.provider_usage_raw_json->>'accounted_cost_usd')::numeric
           ELSE NULL
         END AS known_cost_usd
  FROM llm_usage_attempt_intent i
  LEFT JOIN run_step_usage u ON u.physical_attempt_id = i.physical_attempt_id
  LEFT JOIN managed_ai_usage_event m ON m.usage_event_id = u.id
  LEFT JOIN app_user a ON a.id = i.user_id AND a.org_id = i.org_id
  LEFT JOIN project p ON p.id = i.project_id AND p.org_id = i.org_id
  LEFT JOIN pipeline_run run_direct
    ON run_direct.run_id = i.run_id AND run_direct.org_id = i.org_id
  LEFT JOIN pipeline_run run_source
    ON run_source.run_id = i.source_id AND run_source.org_id = i.org_id
  LEFT JOIN agent_turn turn_row
    ON turn_row.id = i.source_id AND turn_row.org_id = i.org_id
  LEFT JOIN pipeline_run turn_run
    ON turn_run.run_id = turn_row.linked_run_id AND turn_run.org_id = i.org_id
  LEFT JOIN agent_session session_direct
    ON session_direct.id = i.run_id AND session_direct.org_id = i.org_id
  LEFT JOIN agent_session session_source
    ON session_source.id = i.source_id AND session_source.org_id = i.org_id
  WHERE i.org_id = ? AND i.started_at >= ? AND i.started_at < ?
)
""";

  private final JdbcTemplate jdbc;

  @Transactional(readOnly = true)
  public LlmUsageAuditReport report(LlmUsageAuditQuery query) {
    Object[] range = range(query);
    long totalRows =
        jdbc.queryForObject(RESOLVED_CTE + "SELECT COUNT(*) FROM resolved", Long.class, range);
    UsageTotals totals = totals(range);
    List<UsageRollup> allRollups = rollups(range);
    boolean rollupsTruncated = allRollups.size() > LlmUsageAuditQuery.GROUP_ROW_LIMIT;
    List<UsageRollup> rollups =
        rollupsTruncated
            ? List.copyOf(allRollups.subList(0, LlmUsageAuditQuery.GROUP_ROW_LIMIT))
            : List.copyOf(allRollups);
    List<UsageAttempt> attempts = attempts(query, query.size(), query.offset());
    long pages = totalRows == 0 ? 0 : (totalRows + query.size() - 1) / query.size();
    return new LlmUsageAuditReport(
        4,
        "canonical_llm_usage_event",
        query.orgId(),
        new Window(
            query.fromDate(),
            query.toDate(),
            query.timezone(),
            query.fromInclusive(),
            query.toExclusive()),
        new Limits(
            LlmUsageAuditQuery.MAX_WINDOW_DAYS,
            LlmUsageAuditQuery.MAX_PAGE_SIZE,
            LlmUsageAuditQuery.GROUP_ROW_LIMIT,
            rollupsTruncated,
            LlmUsageAuditQuery.CSV_ROW_LIMIT),
        new Page(query.page(), query.size(), totalRows, pages, attempts.size()),
        totals,
        rollups,
        attempts,
        reconcile(query));
  }

  @Transactional(readOnly = true)
  public CsvExport csv(LlmUsageAuditQuery query) {
    List<UsageAttempt> rows = attempts(query, LlmUsageAuditQuery.CSV_ROW_LIMIT + 1, 0);
    boolean truncated = rows.size() > LlmUsageAuditQuery.CSV_ROW_LIMIT;
    if (truncated) rows = new ArrayList<>(rows.subList(0, LlmUsageAuditQuery.CSV_ROW_LIMIT));

    SpreadsheetSafeCsv csv = new SpreadsheetSafeCsv();
    csv.row(
        text("usage_attempt_id"),
        text("created_at"),
        text("execution_context_id"),
        text("user_id"),
        text("user_email"),
        text("user_role"),
        text("project_id"),
        text("project_name"),
        text("run_id"),
        text("session_id"),
        text("source_type"),
        text("source_id"),
        text("task_id"),
        text("agent_role"),
        text("feature"),
        text("step_id"),
        text("retry_of"),
        text("correlation_id"),
        text("model_tier"),
        text("logical_call_id"),
        text("physical_attempt_id"),
        text("parent_attempt_id"),
        text("attempt_kind"),
        text("attempt_ordinal"),
        text("provider"),
        text("model"),
        text("key_source"),
        text("attempt_status"),
        text("attempt_number"),
        text("latency_ms"),
        text("input_tokens"),
        text("output_tokens"),
        text("cache_read_tokens"),
        text("cache_write_tokens"),
        text("reasoning_tokens"),
        text("known_cost_usd"),
        text("cost_status"),
        text("managed_ai_credits"),
        text("managed_ai_projection_status"),
        text("error_code"));
    for (UsageAttempt row : rows) {
      csv.row(
          text(row.id()),
          text(row.createdAt()),
          text(row.llmExecutionContextId()),
          text(row.userId()),
          text(row.userEmail()),
          text(row.userRole()),
          text(row.projectId()),
          text(row.projectName()),
          text(row.runId()),
          text(row.sessionId()),
          text(row.sourceType()),
          text(row.sourceId()),
          text(row.taskId()),
          text(row.agentRole()),
          text(row.feature()),
          text(row.stepId()),
          text(row.retryOf()),
          text(row.correlationId()),
          text(row.modelTier()),
          text(row.logicalCallId()),
          text(row.physicalAttemptId()),
          text(row.parentAttemptId()),
          text(row.attemptKind()),
          number(row.attemptOrdinal()),
          text(row.provider()),
          text(row.model()),
          text(row.keySource()),
          text(row.attemptStatus()),
          number(row.attemptNumber()),
          number(row.latencyMs()),
          number(row.inputTokens()),
          number(row.outputTokens()),
          number(row.cacheReadTokens()),
          number(row.cacheWriteTokens()),
          number(row.reasoningTokens()),
          number(row.knownCostUsd()),
          text(row.costStatus()),
          number(row.managedAiCredits()),
          text(row.managedAiProjectionStatus()),
          text(row.errorCode()));
    }
    String safeOrg = query.orgId().replaceAll("[^A-Za-z0-9_-]", "_");
    String filename =
        "llm-usage-" + safeOrg + "-" + query.fromDate() + "-to-" + query.toDate() + ".csv";
    return new CsvExport(csv.bytes(), rows.size(), truncated, filename);
  }

  private UsageTotals totals(Object[] range) {
    Map<String, Object> row =
        jdbc.queryForMap(
            RESOLVED_CTE
                + """
SELECT COUNT(*) AS attempts,
       COUNT(*) FILTER (WHERE attempt_status='SUCCESS') AS successful_attempts,
       COUNT(*) FILTER (WHERE attempt_status='FAILED') AS failed_attempts,
       COUNT(*) FILTER (WHERE attempt_status='MISSING_TERMINAL') AS missing_terminal_attempts,
       COALESCE(SUM(input_tokens), 0) AS input_tokens,
       COALESCE(SUM(output_tokens), 0) AS output_tokens,
       COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
       COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
       COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens,
       COALESCE(SUM(known_cost_usd), 0) AS known_cost_usd,
       COUNT(*) FILTER (WHERE known_cost_usd IS NULL) AS unknown_cost_attempts
FROM resolved
""",
            range);
    long unknown = longValue(row, "unknown_cost_attempts");
    return new UsageTotals(
        longValue(row, "attempts"),
        longValue(row, "successful_attempts"),
        longValue(row, "failed_attempts"),
        longValue(row, "missing_terminal_attempts"),
        longValue(row, "input_tokens"),
        longValue(row, "output_tokens"),
        longValue(row, "cache_read_tokens"),
        longValue(row, "cache_write_tokens"),
        longValue(row, "reasoning_tokens"),
        decimal(row, "known_cost_usd"),
        unknown,
        unknown > 0);
  }

  private List<UsageRollup> rollups(Object[] range) {
    Object[] args = append(range, LlmUsageAuditQuery.GROUP_ROW_LIMIT + 1);
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            RESOLVED_CTE
                + """
SELECT user_id, COALESCE(user_email, 'Unknown') AS user_email, user_role,
       provider, model, agent_role, feature, project_id,
       COALESCE(project_name, 'No project') AS project_name,
       resolved_run_id, resolved_session_id, source_type, source_id,
       key_source, attempt_status,
       COUNT(*) AS attempts,
       COALESCE(SUM(input_tokens), 0) AS input_tokens,
       COALESCE(SUM(output_tokens), 0) AS output_tokens,
       COALESCE(SUM(cache_read_tokens), 0) AS cache_read_tokens,
       COALESCE(SUM(cache_write_tokens), 0) AS cache_write_tokens,
       COALESCE(SUM(reasoning_tokens), 0) AS reasoning_tokens,
       COALESCE(SUM(known_cost_usd), 0) AS known_cost_usd,
       COUNT(*) FILTER (WHERE known_cost_usd IS NULL) AS unknown_cost_attempts
FROM resolved
GROUP BY user_id, user_email, user_role, provider, model, agent_role, feature, project_id,
         project_name, resolved_run_id, resolved_session_id, source_type, source_id,
         key_source, attempt_status
ORDER BY known_cost_usd DESC, attempts DESC, user_id, provider, model
LIMIT ?
""",
            args);
    return rows.stream().map(this::rollup).toList();
  }

  private List<UsageAttempt> attempts(LlmUsageAuditQuery query, int limit, int offset) {
    Object[] args = append(range(query), limit, offset);
    return jdbc
        .queryForList(
            RESOLVED_CTE
                + """
SELECT id, created_at, llm_execution_context_id, user_id,
       COALESCE(user_email, 'Unknown') AS user_email,
       user_role,
       project_id, COALESCE(project_name, 'No project') AS project_name,
       resolved_run_id, resolved_session_id, source_type, source_id, task_id,
       agent_role, feature, step_id, retry_of, correlation_id, model_tier, logical_call_id,
       physical_attempt_id, parent_attempt_id, attempt_kind, attempt_ordinal,
       provider, model, key_source, attempt_status,
       attempt_number, latency_ms, input_tokens, output_tokens, cache_read_tokens,
       cache_write_tokens, reasoning_tokens, known_cost_usd,
       CASE
         WHEN known_cost_usd IS NOT NULL THEN 'KNOWN'
         WHEN attempt_status = 'MISSING_TERMINAL' THEN 'PENDING_TERMINAL'
         WHEN attempt_status = 'SUCCESS' AND cost_usd < 0 THEN 'INVALID_NEGATIVE_COST'
         ELSE 'UNKNOWN_PROVIDER_NOT_REPORTED'
       END AS cost_status,
       managed_ai_credits, managed_ai_projection_status,
       error_code
FROM resolved
ORDER BY created_at DESC, id DESC
LIMIT ? OFFSET ?
""",
            args)
        .stream()
        .map(this::attempt)
        .toList();
  }

  private Reconciliation reconcile(LlmUsageAuditQuery query) {
    ReconciliationResult run = reconcileRunTotals(query);
    ReconciliationResult managed = reconcileManagedEvents(query);
    return new Reconciliation(
        LlmUsageReconciliation.tolerancePolicy(),
        run,
        managed,
        "NOT_AVAILABLE_NO_PROVIDER_INVOICE_OR_REQUEST_IDENTIFIER");
  }

  private ReconciliationResult reconcileRunTotals(LlmUsageAuditQuery query) {
    Map<String, Object> row =
        jdbc.queryForMap(
            RESOLVED_CTE
                + """
, by_run AS (
  SELECT resolved_run_id AS run_id,
         COALESCE(SUM(known_cost_usd), 0) AS ledger_cost,
         COUNT(*) FILTER (WHERE known_cost_usd IS NULL) AS unknown_cost_attempts
  FROM resolved
  WHERE resolved_run_id IS NOT NULL
  GROUP BY resolved_run_id
)
SELECT COALESCE(SUM(b.ledger_cost), 0) AS ledger_cost,
       COALESCE(SUM(r.actual_cost_usd), 0) AS run_cost,
       COUNT(*) FILTER (WHERE r.actual_cost_usd IS NOT NULL) AS comparable_records,
       COUNT(*) FILTER (WHERE r.actual_cost_usd IS NULL) AS missing_records,
       COALESCE(SUM(b.unknown_cost_attempts), 0) AS unknown_cost_attempts
FROM by_run b
LEFT JOIN pipeline_run r ON r.run_id = b.run_id AND r.org_id = ?
""",
            append(range(query), query.orgId()));
    UsageSide ledger = new UsageSide(decimal(row, "ledger_cost"), 0, 0, 0, 0, 0);
    UsageSide runs = new UsageSide(decimal(row, "run_cost"), 0, 0, 0, 0, 0);
    return LlmUsageReconciliation.calculate(
        "NO_COMPARABLE_RUNS",
        "INDETERMINATE_INCOMPLETE_OR_UNKNOWN_COST",
        ledger,
        runs,
        longValue(row, "comparable_records"),
        longValue(row, "missing_records"),
        0,
        longValue(row, "unknown_cost_attempts"),
        false,
        "Canonical known attempt cost matches pipeline_run.actual_cost_usd.",
        "A run total is missing or a failed provider attempt has unreported cost; known cost is a"
            + " lower bound.",
        "No pipeline runs with recorded cost occurred in this report window.");
  }

  private ReconciliationResult reconcileManagedEvents(LlmUsageAuditQuery query) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
WITH ledger AS (
  SELECT u.id, u.input_tokens, u.output_tokens, u.reasoning_tokens,
         u.cache_read_tokens, u.cache_write_tokens, u.cost_usd::numeric AS cost_usd
  FROM run_step_usage u
  WHERE u.org_id = ? AND u.created_at >= ? AND u.created_at < ?
    AND u.attempt_status = 'SUCCESS' AND u.key_source = 'PLATFORM'
), managed_all AS (
  SELECT id AS managed_event_id, usage_event_id, input_tokens, output_tokens, reasoning_tokens,
         cache_read_tokens, cache_write_tokens, raw_cost_usd::numeric AS cost_usd
  FROM managed_ai_usage_event
  WHERE org_id = ? AND source_type = 'PLATFORM'
), managed_window AS (
  SELECT *
  FROM managed_all
  WHERE managed_event_id IN (
    SELECT id FROM managed_ai_usage_event
    WHERE org_id = ? AND created_at >= ? AND created_at < ? AND source_type = 'PLATFORM'
  )
), paired AS (
  SELECT l.*, m.usage_event_id,
         m.input_tokens AS managed_input_tokens,
         m.output_tokens AS managed_output_tokens,
         m.reasoning_tokens AS managed_reasoning_tokens,
         m.cache_read_tokens AS managed_cache_read_tokens,
         m.cache_write_tokens AS managed_cache_write_tokens,
         m.cost_usd AS managed_cost_usd,
         (m.usage_event_id IS NULL) AS ledger_only,
         FALSE AS managed_only
  FROM ledger l
  LEFT JOIN managed_all m ON m.usage_event_id = l.id
  UNION ALL
  SELECT NULL AS id,
         NULL AS input_tokens,
         NULL AS output_tokens,
         NULL AS reasoning_tokens,
         NULL AS cache_read_tokens,
         NULL AS cache_write_tokens,
         NULL AS cost_usd,
         m.usage_event_id,
         m.input_tokens AS managed_input_tokens,
         m.output_tokens AS managed_output_tokens,
         m.reasoning_tokens AS managed_reasoning_tokens,
         m.cache_read_tokens AS managed_cache_read_tokens,
         m.cache_write_tokens AS managed_cache_write_tokens,
         m.cost_usd AS managed_cost_usd,
         FALSE AS ledger_only,
         TRUE AS managed_only
  FROM managed_window m
  LEFT JOIN run_step_usage u ON u.id = m.usage_event_id
  WHERE u.id IS NULL
)
SELECT COALESCE(SUM(p.cost_usd), 0) AS ledger_cost,
       COALESCE(SUM(p.managed_cost_usd), 0) AS managed_cost,
       COALESCE(SUM(p.input_tokens), 0) AS ledger_input_tokens,
       COALESCE(SUM(p.managed_input_tokens), 0) AS managed_input_tokens,
       COALESCE(SUM(p.output_tokens), 0) AS ledger_output_tokens,
       COALESCE(SUM(p.managed_output_tokens), 0) AS managed_output_tokens,
       COALESCE(SUM(p.reasoning_tokens), 0) AS ledger_reasoning_tokens,
       COALESCE(SUM(p.managed_reasoning_tokens), 0) AS managed_reasoning_tokens,
       COALESCE(SUM(p.cache_read_tokens), 0) AS ledger_cache_read_tokens,
       COALESCE(SUM(p.managed_cache_read_tokens), 0) AS managed_cache_read_tokens,
       COALESCE(SUM(p.cache_write_tokens), 0) AS ledger_cache_write_tokens,
       COALESCE(SUM(p.managed_cache_write_tokens), 0) AS managed_cache_write_tokens,
       COUNT(*) FILTER (WHERE NOT p.ledger_only AND NOT p.managed_only) AS comparable_records,
       COUNT(*) FILTER (WHERE p.ledger_only) AS missing_records,
       COUNT(*) FILTER (WHERE p.managed_only) AS comparison_only_records
FROM paired p
""",
            query.orgId(),
            Timestamp.from(query.fromInclusive()),
            Timestamp.from(query.toExclusive()),
            query.orgId(),
            query.orgId(),
            Timestamp.from(query.fromInclusive()),
            Timestamp.from(query.toExclusive()));
    UsageSide ledger =
        new UsageSide(
            decimal(row, "ledger_cost"),
            longValue(row, "ledger_input_tokens"),
            longValue(row, "ledger_output_tokens"),
            longValue(row, "ledger_reasoning_tokens"),
            longValue(row, "ledger_cache_read_tokens"),
            longValue(row, "ledger_cache_write_tokens"));
    UsageSide managed =
        new UsageSide(
            decimal(row, "managed_cost"),
            longValue(row, "managed_input_tokens"),
            longValue(row, "managed_output_tokens"),
            longValue(row, "managed_reasoning_tokens"),
            longValue(row, "managed_cache_read_tokens"),
            longValue(row, "managed_cache_write_tokens"));
    return LlmUsageReconciliation.calculate(
        "NOT_APPLICABLE_NO_MANAGED_EVENTS",
        "COVERAGE_INCOMPLETE_OR_NOT_MANAGED",
        ledger,
        managed,
        longValue(row, "comparable_records"),
        longValue(row, "missing_records"),
        longValue(row, "comparison_only_records"),
        0,
        true,
        "Platform-key canonical attempts match managed-AI event cost and token totals.",
        "Some platform-key attempts have no comparable managed-AI event, or vice versa; this can"
            + " mean an unmanaged tier/model or a ledger gap.",
        "Neither comparable platform-key attempts nor managed-AI events exist in this window.");
  }

  private UsageRollup rollup(Map<String, Object> row) {
    return new UsageRollup(
        string(row, "user_id"),
        string(row, "user_email"),
        string(row, "user_role"),
        string(row, "provider"),
        string(row, "model"),
        string(row, "agent_role"),
        string(row, "feature"),
        string(row, "project_id"),
        string(row, "project_name"),
        string(row, "resolved_run_id"),
        string(row, "resolved_session_id"),
        string(row, "source_type"),
        string(row, "source_id"),
        string(row, "key_source"),
        string(row, "attempt_status"),
        longValue(row, "attempts"),
        longValue(row, "input_tokens"),
        longValue(row, "output_tokens"),
        longValue(row, "cache_read_tokens"),
        longValue(row, "cache_write_tokens"),
        longValue(row, "reasoning_tokens"),
        decimal(row, "known_cost_usd"),
        longValue(row, "unknown_cost_attempts"));
  }

  private UsageAttempt attempt(Map<String, Object> row) {
    return new UsageAttempt(
        string(row, "id"),
        instant(row, "created_at"),
        string(row, "llm_execution_context_id"),
        string(row, "user_id"),
        string(row, "user_email"),
        string(row, "user_role"),
        string(row, "project_id"),
        string(row, "project_name"),
        string(row, "resolved_run_id"),
        string(row, "resolved_session_id"),
        string(row, "source_type"),
        string(row, "source_id"),
        string(row, "task_id"),
        string(row, "agent_role"),
        string(row, "feature"),
        string(row, "step_id"),
        string(row, "retry_of"),
        string(row, "correlation_id"),
        string(row, "model_tier"),
        string(row, "logical_call_id"),
        string(row, "physical_attempt_id"),
        string(row, "parent_attempt_id"),
        string(row, "attempt_kind"),
        longValue(row, "attempt_ordinal"),
        string(row, "provider"),
        string(row, "model"),
        string(row, "key_source"),
        string(row, "attempt_status"),
        intValue(row, "attempt_number"),
        longValue(row, "latency_ms"),
        longValue(row, "input_tokens"),
        longValue(row, "output_tokens"),
        longValue(row, "cache_read_tokens"),
        longValue(row, "cache_write_tokens"),
        longValue(row, "reasoning_tokens"),
        nullableDecimal(row, "known_cost_usd"),
        string(row, "cost_status"),
        longValue(row, "managed_ai_credits"),
        string(row, "managed_ai_projection_status"),
        string(row, "error_code"));
  }

  private static Object[] range(LlmUsageAuditQuery query) {
    return new Object[] {
      query.orgId(), Timestamp.from(query.fromInclusive()), Timestamp.from(query.toExclusive())
    };
  }

  private static Object[] append(Object[] base, Object... suffix) {
    Object[] result = new Object[base.length + suffix.length];
    System.arraycopy(base, 0, result, 0, base.length);
    System.arraycopy(suffix, 0, result, base.length, suffix.length);
    return result;
  }

  private static String string(Map<String, Object> row, String key) {
    Object value = row.get(key.toLowerCase(Locale.ROOT));
    return value == null ? null : String.valueOf(value);
  }

  private static long longValue(Map<String, Object> row, String key) {
    Object value = row.get(key.toLowerCase(Locale.ROOT));
    return value instanceof Number number ? number.longValue() : 0L;
  }

  private static int intValue(Map<String, Object> row, String key) {
    Object value = row.get(key.toLowerCase(Locale.ROOT));
    return value instanceof Number number ? number.intValue() : 0;
  }

  private static BigDecimal decimal(Map<String, Object> row, String key) {
    BigDecimal value = nullableDecimal(row, key);
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal nullableDecimal(Map<String, Object> row, String key) {
    Object value = row.get(key.toLowerCase(Locale.ROOT));
    if (value == null) return null;
    if (value instanceof BigDecimal decimal) return decimal;
    if (value instanceof Number number) return new BigDecimal(number.toString());
    return new BigDecimal(String.valueOf(value));
  }

  private static Instant instant(Map<String, Object> row, String key) {
    Object value = row.get(key.toLowerCase(Locale.ROOT));
    if (value instanceof Timestamp timestamp) return timestamp.toInstant();
    if (value instanceof Instant instant) return instant;
    return value == null ? null : Instant.parse(String.valueOf(value));
  }

  public record CsvExport(byte[] content, int rowCount, boolean truncated, String filename) {}
}
