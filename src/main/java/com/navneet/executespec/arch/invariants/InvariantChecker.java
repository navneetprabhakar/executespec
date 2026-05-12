package com.navneet.executespec.arch.invariants;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.util.SafeYamlLoader;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.14 — checks an architecture invariant against a workspace snapshot (file paths + content
 * access via callback). Three rule kinds per plan §5.12:
 *
 * <ul>
 *   <li>{@code exclusive-writer} — only the listed services may write to the listed table
 *   <li>{@code no-direct-call} — service A may not import/call service B directly (must go via C)
 *   <li>{@code layer-isolation} — files in layer X may not import from layer Y
 * </ul>
 *
 * <p>v1 implementation is text-pattern based (same approach as the AST checker E3.7+8). Real AST
 * upgrade is the same §13 deferral.
 *
 * <p>Both PLAN and ARCH_REGEN call sites use this same checker — the caller decides which {@link
 * ArchInvariantViolation.DetectionPhase} to record on persisted violations.
 */
@Component
@Slf4j
public class InvariantChecker {

  @FunctionalInterface
  public interface ContentLoader {
    String load(String path);
  }

  /**
   * Run a single invariant against the workspace.
   *
   * @param invariantYaml the YAML body from the invariant_yaml column
   * @param repoFilePaths repo-relative paths in the workspace
   * @param loader per-file content loader
   */
  public List<Finding> check(
      String invariantYaml, List<String> repoFilePaths, ContentLoader loader) {
    if (invariantYaml == null || invariantYaml.isBlank()) return List.of();
    try {
      Object raw = SafeYamlLoader.load(invariantYaml);
      // Support both single-invariant body and a one-element list
      if (raw instanceof List<?> l && !l.isEmpty()) raw = l.get(0);
      if (!(raw instanceof Map)) return List.of();
      @SuppressWarnings("unchecked")
      Map<String, Object> spec = (Map<String, Object>) raw;
      return checkOne(spec, repoFilePaths, loader);
    } catch (Exception ex) {
      log.warn("[InvariantChecker] parse failed: {}", ex.getMessage());
      return List.of();
    }
  }

  @SuppressWarnings("unchecked")
  List<Finding> checkOne(Map<String, Object> spec, List<String> files, ContentLoader loader) {
    Object enforcementRaw = spec.get("enforcement");
    if (!(enforcementRaw instanceof Map)) return List.of();
    Map<String, Object> enforcement = (Map<String, Object>) enforcementRaw;
    Object ruleRaw = enforcement.get("rule");
    if (!(ruleRaw instanceof Map)) return List.of();
    Map<String, Object> rule = (Map<String, Object>) ruleRaw;
    String kind = strOrNull(rule.get("kind"));
    if (kind == null) return List.of();

    return switch (kind.toLowerCase()) {
      case "exclusive-writer" -> exclusiveWriter(rule, files, loader);
      case "no-direct-call" -> noDirectCall(rule, files, loader);
      case "layer-isolation" -> layerIsolation(rule, files, loader);
      default -> {
        log.debug("[InvariantChecker] unsupported rule kind '{}' — skip", kind);
        yield List.of();
      }
    };
  }

  @SuppressWarnings("unchecked")
  List<Finding> exclusiveWriter(
      Map<String, Object> rule, List<String> files, ContentLoader loader) {
    String table = strOrNull(rule.get("table"));
    Object allowedRaw = rule.get("allowed_writers");
    if (table == null || !(allowedRaw instanceof List)) return List.of();
    List<String> allowed = (List<String>) allowedRaw;
    // Pattern: any file containing 'INSERT INTO <table>' / 'UPDATE <table>' /
    // 'DELETE FROM <table>' that's NOT in an allowed-writer's directory.
    java.util.regex.Pattern writePat =
        java.util.regex.Pattern.compile(
            "(?i)(?:INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\s+"
                + java.util.regex.Pattern.quote(table)
                + "\\b");
    List<Finding> findings = new ArrayList<>();
    for (String file : files) {
      if (allowed.stream().anyMatch(file::contains)) continue;
      String content = loader.load(file);
      if (content == null) continue;
      if (writePat.matcher(content).find()) {
        findings.add(
            new Finding(
                "exclusive-writer",
                file,
                "File '"
                    + file
                    + "' writes to table '"
                    + table
                    + "' but is not in allowed_writers "
                    + allowed));
      }
    }
    return findings;
  }

  @SuppressWarnings("unchecked")
  List<Finding> noDirectCall(Map<String, Object> rule, List<String> files, ContentLoader loader) {
    String fromService = strOrNull(rule.get("from"));
    String forbiddenTarget = strOrNull(rule.get("forbidden_target"));
    if (fromService == null || forbiddenTarget == null) return List.of();
    List<Finding> findings = new ArrayList<>();
    for (String file : files) {
      if (!file.contains(fromService)) continue;
      String content = loader.load(file);
      if (content == null) continue;
      if (content.contains(forbiddenTarget)) {
        findings.add(
            new Finding(
                "no-direct-call",
                file,
                "Service '"
                    + fromService
                    + "' (file "
                    + file
                    + ") makes a direct reference to '"
                    + forbiddenTarget
                    + "'"));
      }
    }
    return findings;
  }

  List<Finding> layerIsolation(Map<String, Object> rule, List<String> files, ContentLoader loader) {
    String fromLayer = strOrNull(rule.get("from"));
    String forbiddenLayer = strOrNull(rule.get("forbidden"));
    if (fromLayer == null || forbiddenLayer == null) return List.of();
    // Reuse glob from contracts/detection so we get the same ** semantics
    List<Finding> findings = new ArrayList<>();
    for (String file : files) {
      if (!com.navneet.executespec.contracts.detection.DetectorUtils.matchesGlob(file, fromLayer))
        continue;
      String content = loader.load(file);
      if (content == null) continue;
      if (content.contains(forbiddenLayer)) {
        findings.add(
            new Finding(
                "layer-isolation",
                file,
                "Layer '"
                    + fromLayer
                    + "' file '"
                    + file
                    + "' references forbidden layer '"
                    + forbiddenLayer
                    + "'"));
      }
    }
    return findings;
  }

  private static String strOrNull(Object o) {
    return o == null ? null : o.toString();
  }

  public record Finding(String ruleKind, String file, String message) {}
}
