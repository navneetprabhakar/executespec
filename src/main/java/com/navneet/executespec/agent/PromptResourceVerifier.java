package com.navneet.executespec.agent;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Boot-time sanity check for every agent prompt the system depends on.
 *
 * <p>Originally each agent had its own load-fail path that silently degraded to a heuristic
 * fallback — observed pattern: a deploy goes out with a stale or incomplete {@code prompts/}
 * directory in the JAR, the agent first-runs, can't load its prompt, falls back to a heuristic, and
 * the cost / quality regression only surfaces hours later when a customer's run fails. The
 * 2026-05-13 audit caught this twice: {@code reviewer-general.md} missing (PR fix in 9d43d9ee added
 * per-agent @PostConstruct check) and {@code classifier.md} still missing on dev (this component's
 * reason for existing).
 *
 * <p>Verifies every critical prompt resource at startup. Fail-fast: any missing resource throws
 * and Spring refuses to come up, so a broken deploy is loud at boot instead of silently
 * malfunctioning under traffic.
 */
@Slf4j
@Component
public class PromptResourceVerifier {

  /**
   * Critical prompt resources the agent pipeline can't function without. Update this list whenever
   * a new agent adds a hard-coded prompt path. Optional/fallback fragments (e.g. {@code
   * prompts/fragments/}) intentionally not enumerated — those are graceful-degradation paths.
   */
  private static final List<String> CRITICAL_PROMPTS =
      List.of(
          "prompts/classifier.md",
          "prompts/outline-planner.md",
          "prompts/outline-planner-skeleton.md",
          "prompts/coherence-planner.md",
          "prompts/task-expander.md",
          "prompts/reviewer-general.md",
          "prompts/reviewer-wiring.md",
          "prompts/codereview/code-review-agent.md",
          "prompts/codereview/pr-review-agent.md",
          "prompts/execution_planner.txt",
          "prompts/memory-compaction.txt",
          "prompts/run-ledger.txt");

  // Migration-recipe prompts (e.g. {@code prompts/migrations/*-fallback.md}) intentionally not
  // verified here — their paths are stored per-recipe in the database and loaded on demand;
  // missing ones surface as a per-task failure rather than a hard boot block.

  @PostConstruct
  public void verify() {
    List<String> missing = new ArrayList<>();
    for (String path : CRITICAL_PROMPTS) {
      ClassPathResource res = new ClassPathResource(path);
      if (!res.exists()) {
        missing.add(path);
      }
    }
    if (!missing.isEmpty()) {
      String msg =
          "[PromptResourceVerifier] BOOT FAIL — "
              + missing.size()
              + " critical prompt file(s) missing from classpath. "
              + "JAR is broken / build artefact incomplete. Missing: "
              + String.join(", ", missing);
      log.error(msg);
      throw new IllegalStateException(msg);
    }
    log.info(
        "[PromptResourceVerifier] verified {} critical prompt resources on classpath",
        CRITICAL_PROMPTS.size());
  }
}
