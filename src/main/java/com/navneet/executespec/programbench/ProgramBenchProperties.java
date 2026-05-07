package com.navneet.executespec.programbench;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Programbench config — disabled by default.
 *
 * <p>To enable on a host: set {@code programbench.enabled=true} + ensure docker is reachable +
 * ensure the inference host is Linux x86_64 (cleanroom images are amd64-only).
 */
@Component
@ConfigurationProperties(prefix = "programbench")
@Data
public class ProgramBenchProperties {

  /**
   * Master switch. When false, the controller endpoints return 503 and the bootstrap is skipped.
   */
  private boolean enabled = false;

  /** When true, on startup create the synthetic Bench User/Org/Project rows if absent. */
  private boolean bootstrap = false;

  /**
   * Stable IDs for the synthetic fixture. These are deterministic so the same rows are reused
   * across JVM restarts. Using all-zero-suffixed UUIDs to make them recognisable in logs.
   */
  private String benchOrgId = "00000000-0000-0000-0000-00000000b0b0";

  private String benchUserId = "00000000-0000-0000-0000-00000000b001";
  private String benchProjectId = "00000000-0000-0000-0000-00000000b002";

  /** Host directory for bind-mounted workspaces — one subdir per run. */
  private String workspaceBase = "/var/programbench/workspaces";

  /** Output base — one subdir per run holds submission/ + submission.tar.gz + traj.json. */
  private String outputBase = "/var/programbench/runs";

  /** Default LLM provider when caller doesn't specify. */
  private String defaultProvider = "anthropic";

  /**
   * Tasks directory location override (otherwise discovered from PYTHONPATH lookup of programbench
   * package).
   */
  private String tasksDir = "";

  /** Docker org for cleanroom images (matches programbench's PROGRAMBENCH_DOCKER_ORG). */
  private String dockerOrg = "programbench";

  /** Per-task wall-clock cap so a stuck run can't hold a container forever. */
  private int taskWallTimeoutMinutes = 90;
}
