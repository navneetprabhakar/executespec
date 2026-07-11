package com.navneet.executespec.tool.remote;

import java.time.Instant;
import java.util.List;

/**
 * Durable journal for local-hands calls. The journal is deliberately not a worker queue: a JVM
 * restart cannot recreate the waiting CompletableFuture. It preserves the exact frame so a
 * reconnecting client/operator can diagnose or re-drive the call through a future resume flow.
 */
public interface RemoteToolCallStore {

  void record(
      String callId,
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      String frameJson,
      Instant createdAt);

  void complete(String runId, String callId, String resultJson, Instant completedAt);

  void expire(String runId, String callId, String errorJson, Instant completedAt);

  List<PendingCall> pending(String runId);

  record PendingCall(
      String callId,
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      String frameJson,
      Instant createdAt) {}
}
