package com.navneet.executespec.tool.remote;

import java.time.Instant;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** PostgreSQL journal for bounded local-hands pending-call recovery evidence. */
@Repository
@RequiredArgsConstructor
@Slf4j
public class JdbcRemoteToolCallStore implements RemoteToolCallStore {

  private final JdbcTemplate jdbc;

  @Override
  public void record(
      String callId,
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      String frameJson,
      Instant createdAt) {
    try {
      jdbc.update(
          "INSERT INTO remote_tool_call (call_id, run_id, task_id, tool_name, args_json,"
              + " frame_json, state, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)",
          callId,
          runId,
          taskId,
          toolName,
          argsJson,
          frameJson,
          createdAt);
    } catch (DataAccessException e) {
      // The journal must not turn a healthy local-hands run into a DB outage. Runtime memory
      // remains authoritative while this is fail-open; the missing evidence is logged.
      log.warn(
          "[RemoteTool] unable to journal pending call {} for run {}: {}",
          callId,
          runId,
          e.getMessage());
    }
  }

  @Override
  public void complete(String runId, String callId, String resultJson, Instant completedAt) {
    updateTerminal(runId, callId, "COMPLETED", resultJson, completedAt);
  }

  @Override
  public void expire(String runId, String callId, String errorJson, Instant completedAt) {
    updateTerminal(runId, callId, "EXPIRED", errorJson, completedAt);
  }

  private void updateTerminal(
      String runId, String callId, String state, String result, Instant completedAt) {
    try {
      jdbc.update(
          "UPDATE remote_tool_call SET state = ?, result_json = ?, completed_at = ? "
              + "WHERE run_id = ? AND call_id = ? AND state = 'PENDING'",
          state,
          result,
          completedAt,
          runId,
          callId);
    } catch (DataAccessException e) {
      log.warn(
          "[RemoteTool] unable to update call {} for run {}: {}", callId, runId, e.getMessage());
    }
  }

  @Override
  public List<PendingCall> pending(String runId) {
    try {
      // Keep the recovery surface bounded. Calls older than one day are no longer actionable.
      jdbc.update("DELETE FROM remote_tool_call WHERE created_at < now() - interval '24 hours'");
      return jdbc.query(
          "SELECT call_id, run_id, task_id, tool_name, args_json, frame_json, created_at "
              + "FROM remote_tool_call WHERE run_id = ? AND state = 'PENDING' "
              + "ORDER BY created_at ASC LIMIT 100",
          (rs, rowNum) ->
              new PendingCall(
                  rs.getString("call_id"),
                  rs.getString("run_id"),
                  rs.getString("task_id"),
                  rs.getString("tool_name"),
                  rs.getString("args_json"),
                  rs.getString("frame_json"),
                  rs.getTimestamp("created_at").toInstant()),
          runId);
    } catch (DataAccessException e) {
      log.warn("[RemoteTool] unable to read pending calls for run {}: {}", runId, e.getMessage());
      return List.of();
    }
  }
}
