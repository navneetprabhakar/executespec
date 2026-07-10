package com.navneet.executespec.swebench;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * SWE-bench config — disabled by default.
 *
 * <p>To enable on a host: set {@code swebench.enabled=true} + ensure docker is reachable + ensure
 * the inference host can pull the SWE-bench task images (~1–3 GB each, amd64-only).
 *
 * <p>Each SWE-bench instance ships a docker image at {@code
 * docker.io/swebench/sweb.eval.x86_64.<repo>_1776_<repo-task>:latest}. The image has the repo at
 * {@code /testbed} pre-checked-out to the base commit, with all Python deps installed and the
 * failing test ready to run. The agent's job: edit files in {@code /testbed} so the failing test
 * passes (without breaking existing tests). Submission shape = git diff between the base commit and
 * the agent's final state.
 */
@Component
@ConfigurationProperties(prefix = "swebench")
@Data
public class SwebenchProperties {

  /**
   * Master switch. When false, the controller endpoints return 503 and the bootstrap is skipped.
   */
  private boolean enabled = false;

  /** When true, on startup create the synthetic Bench User/Org/Project rows if absent. */
  private boolean bootstrap = false;

  /**
   * Stable IDs for the synthetic fixture. Deterministic so the same rows are reused across JVM
   * restarts. Different from the programbench fixture (separate project, easier to filter in the UI
   * by project).
   */
  private String benchOrgId = "00000000-0000-0000-0000-00000000bbbb";

  private String benchUserId = "00000000-0000-0000-0000-00000000bbb1";
  private String benchProjectId = "00000000-0000-0000-0000-00000000bbb2";

  /** Output base — one subdir per submit holds preds.json + traj.json + submission.patch. */
  private String outputBase = "/var/swebench/runs";

  /**
   * Dataset/output dialect. Supported values:
   *
   * <ul>
   *   <li>{@code swe-bench} / {@code classic}: Princeton SWE-bench/Lite images under {@code
   *       /testbed}, classic object-shaped {@code preds.json}.
   *   <li>{@code swe-bench-pro} / {@code pro}: Scale SWE-bench Pro images under {@code /app},
   *       Docker tags from the dataset's {@code dockerhub_tag}, and list-shaped patch JSON.
   * </ul>
   */
  private String suite = "swe-bench";

  /** Default LLM provider when caller doesn't specify. */
  private String defaultProvider = "anthropic";

  /** Docker repository for SWE-bench Pro prebuilt images. */
  private String proImageRepository = "jefzda/sweap-images";

  /** Prefix written into SWE-bench Pro patch JSON entries. */
  private String predictionPrefix = "executespec";

  /**
   * Path to a JSON file containing SWE-bench Lite instance metadata. Loaded by {@link
   * SwebenchInstanceLoader}. Format: a JSON array of objects, each having {@code instance_id},
   * {@code repo}, {@code base_commit}, {@code problem_statement}, {@code FAIL_TO_PASS} (JSON string
   * of a list), {@code PASS_TO_PASS} (JSON string), {@code version}.
   *
   * <p>You can dump the dataset locally via:
   *
   * <pre>{@code
   * python -c "
   * from datasets import load_dataset; import json
   * d = load_dataset('princeton-nlp/SWE-Bench_Lite', split='dev')
   * with open('swebench_lite_dev.json','w') as f:
   *     json.dump([dict(x) for x in d], f)
   * "
   * }</pre>
   *
   * Default location matches mini-swe-agent's local install on the dev VM and our local scratchpad.
   */
  private String instancesFile = "/Users/navneetprabhakar/swebench-bench/swebench_lite_dev.json";

  /** Per-task wall-clock cap so a stuck run can't hold a container forever. */
  private int taskWallTimeoutMinutes = 60;

  /**
   * Per-task dollar ceiling written to the minimal benchmark run row. Benchmark campaigns must
   * lower this explicitly when a repeated-attempt budget is smaller than the historical $8
   * default; the worker still stops as soon as the observed spend reaches this ceiling.
   */
  private double hardCapUsd = 8.0;

  /**
   * RL-2026-05-059 — hard cap on concurrent benchmark runs. A flood of submissions OR orphaned runs
   * from a killed runner can otherwise pile onto the shared box and OOM the JVM (the 2026-05-31
   * jetsam crash: 4 flagship runs + orphans exceeded 23GB → kernel killed the JVM). Each provider
   * submits 1-at-a-time, so 4 = one in-flight per provider. Memory-bounded — raise only with RAM
   * headroom.
   */
  private int maxConcurrentRuns = 4;

  /**
   * Seconds a {@code submit()} waits for a free concurrency slot before returning HTTP 429
   * (backpressure / rate limit). Generous so a runner blocks rather than fails when the box is
   * busy.
   */
  private int submitQueueWaitSeconds = 180;

  /**
   * Docker image template — substituted with the instance ID's underscore-separated form (matches
   * SWE-bench's published image naming convention). Example: {@code sqlfluff__sqlfluff-1625} →
   * {@code docker.io/swebench/sweb.eval.x86_64.sqlfluff_1776_sqlfluff-1625:latest}.
   */
  private String imageTemplate = "docker.io/swebench/sweb.eval.x86_64.%s:latest";

  public boolean isSweBenchPro() {
    String normalized = suite == null ? "" : suite.trim().toLowerCase();
    return normalized.equals("swe-bench-pro")
        || normalized.equals("swebench-pro")
        || normalized.equals("swe_pro")
        || normalized.equals("pro");
  }

  public String workdir() {
    return isSweBenchPro() ? "/app" : "/testbed";
  }
}
