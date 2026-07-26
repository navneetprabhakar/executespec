package com.navneet.executespec.sandbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Maps the durable {@code run_sandbox} row into its immutable domain snapshot. */
final class RunSandboxPersistenceCodec {

  private static final int MAX_REPOSITORY_SET_CHARS = 200_000;

  private RunSandboxPersistenceCodec() {}

  static RunSandbox map(ResultSet rs, int ignored) throws SQLException {
    return new RunSandbox(
        rs.getObject("id", UUID.class),
        rs.getString("run_id"),
        rs.getString("project_id"),
        rs.getObject("session_id", UUID.class),
        rs.getString("requested_by_user_id"),
        rs.getString("org_id"),
        rs.getString("source_revision"),
        rs.getString("repository_set"),
        SandboxState.valueOf(rs.getString("state")),
        SandboxIsolationProfile.valueOf(rs.getString("isolation_profile")),
        rs.getInt("isolation_profile_version"),
        rs.getString("runtime_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        rs.getLong("runtime_epoch"),
        instant(rs, "heartbeat_at"),
        rs.getString("runtime_handle"),
        rs.getString("image_digest"),
        rs.getString("config_digest"),
        SandboxNetworkPolicy.valueOf(rs.getString("network_policy")),
        SandboxSecretPolicy.valueOf(rs.getString("secret_policy")),
        instant(rs, "expires_at"),
        rs.getString("failure_class"),
        rs.getString("failure_detail"),
        nullableState(rs, "cleanup_target"),
        rs.getString("cleanup_failure_class"),
        rs.getString("cleanup_failure_detail"),
        rs.getInt("cleanup_attempts"),
        rs.getString("cleanup_last_error"),
        SandboxRetentionState.valueOf(rs.getString("retention_state")),
        rs.getString("artifact_uri"),
        rs.getString("idempotency_key"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "ready_at"),
        instant(rs, "released_at"),
        instant(rs, "terminal_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static SandboxState nullableState(ResultSet rs, String column) throws SQLException {
    String value = rs.getString(column);
    return value == null ? null : SandboxState.valueOf(value);
  }

  static String bounded(String value, int max) {
    if (value == null) {
      return null;
    }
    String clean =
        value.replaceAll("(?i)(api[-_ ]?key|token|secret|bearer)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
    return clean.length() <= max ? clean : clean.substring(0, max);
  }

  static void requireBoundedJson(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sandbox repository set is required");
    }
    if (value.length() > MAX_REPOSITORY_SET_CHARS) {
      throw new IllegalArgumentException(
          "sandbox repository set exceeds " + MAX_REPOSITORY_SET_CHARS + " characters");
    }
  }
}
