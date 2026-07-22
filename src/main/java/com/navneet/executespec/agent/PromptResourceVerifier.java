package com.navneet.executespec.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>Verifies every critical prompt resource at startup. Fail-fast: any missing resource throws and
 * Spring refuses to come up, so a broken deploy is loud at boot instead of silently malfunctioning
 * under traffic.
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
          "prompts/run-ledger.txt",
          // RR-1131 — mandatory SECURITY fragments transitively assembled into the planner/worker
          // prompts via {{include:security-rules:...}}. The role overlays are loaded through
          // PromptAssembler.loadFileSafe, which SUPPRESSES a missing-file exception and silently
          // yields a weaker prompt; the base fragment carries the exact-tool / invalid-request /
          // unsafe-task BLOCKED rules. Verifying them at boot turns that silent degradation into a
          // loud startup failure.
          "prompts/fragments/security-rules.md",
          "prompts/fragments/security-rules-planner.md",
          "prompts/fragments/security-rules-worker.md",
          // RR-1131 — active worker/preview prompts that were omitted from the manifest even though
          // their features are wired.
          "prompts/preview-render.md",
          "prompts/tool-output-compactor.md",
          "prompts/worker-history-compactor.md");

  // Migration-recipe prompts (e.g. {@code prompts/migrations/*-fallback.md}) intentionally not
  // verified here — their paths are stored per-recipe in the database and loaded on demand;
  // missing ones surface as a per-task failure rather than a hard boot block.

  @PostConstruct
  public void verify() {
    // RR-1131 — verify existence AND non-blank content. A zero-byte or whitespace-only prompt file
    // previously passed the existence-only check, so a truncated build artefact could certify boot
    // and then degrade the agent under first traffic. Unreadable/empty is a boot failure.
    List<String> broken = new ArrayList<>();
    for (String path : CRITICAL_PROMPTS) {
      ClassPathResource res = new ClassPathResource(path);
      if (!res.exists()) {
        broken.add(path + " (missing)");
        continue;
      }
      try {
        String content = res.getContentAsString(StandardCharsets.UTF_8);
        if (content == null || content.isBlank()) {
          broken.add(path + " (empty)");
        }
      } catch (IOException e) {
        broken.add(path + " (unreadable)");
      }
    }
    if (!broken.isEmpty()) {
      String msg =
          "[PromptResourceVerifier] BOOT FAIL — "
              + broken.size()
              + " critical prompt file(s) missing/empty/unreadable on classpath. "
              + "JAR is broken / build artefact incomplete. Affected: "
              + String.join(", ", broken);
      log.error(msg);
      throw new IllegalStateException(msg);
    }
    log.info(
        "[PromptResourceVerifier] verified {} critical prompt resources on classpath",
        CRITICAL_PROMPTS.size());
  }
}
