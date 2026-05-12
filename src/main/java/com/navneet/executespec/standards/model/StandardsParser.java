package com.navneet.executespec.standards.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.navneet.executespec.util.SafeYamlLoader;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.2 — YAML → {@link Standard} parser + validator.
 *
 * <p>Input is a YAML document containing a list of standards (the shape customers ship in {@code
 * .executespec/config/<project>/standards.yaml}). Output is a list of validated {@code Standard}
 * records OR a {@link ParseException} with every problem collected so the customer sees all errors
 * in one pass.
 *
 * <p>Validation rules from plan §5.3 + §3.2 task spec:
 *
 * <ul>
 *   <li>{@code id}, {@code severity}, {@code description}, {@code enforcement.type} required
 *   <li>Standard ids unique within document
 *   <li>Severity is one of {@link Standard.Severity}
 *   <li>Enforcement type matches one of the four supported strategies + has its required fields
 *       (e.g. {@code lint} requires {@code tool})
 *   <li>AST rule kind is non-blank when {@code type=ast-check}
 * </ul>
 */
@Component
@Slf4j
public class StandardsParser {

  /**
   * Parse the YAML body into a list of validated standards.
   *
   * @throws ParseException with every collected error (no silent skips)
   */
  public List<Standard> parse(String yamlBody) {
    if (yamlBody == null || yamlBody.isBlank()) return List.of();
    Object raw;
    try {
      raw = SafeYamlLoader.load(yamlBody);
    } catch (Exception ex) {
      throw new ParseException(List.of("YAML parse failed: " + ex.getMessage()));
    }
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> list)) {
      throw new ParseException(
          List.of(
              "Top-level must be a list of standards (got "
                  + raw.getClass().getSimpleName()
                  + ")"));
    }

    List<Standard> out = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();
    for (int i = 0; i < list.size(); i++) {
      Object item = list.get(i);
      if (!(item instanceof Map<?, ?> m)) {
        errors.add("Entry #" + i + " is not a map");
        continue;
      }
      try {
        Standard s = parseOne(m, i, seenIds, errors);
        if (s != null) out.add(s);
      } catch (Exception ex) {
        errors.add("Entry #" + i + ": " + ex.getMessage());
      }
    }
    if (!errors.isEmpty()) throw new ParseException(errors);
    return out;
  }

  @SuppressWarnings("unchecked")
  Standard parseOne(Map<?, ?> m, int idx, Set<String> seenIds, List<String> errors) {
    Map<String, Object> map = (Map<String, Object>) m;
    String id = strOrNull(map.get("id"));
    if (id == null || id.isBlank()) {
      errors.add("Entry #" + idx + ": missing 'id'");
      return null;
    }
    if (!seenIds.add(id)) {
      errors.add("Entry #" + idx + ": duplicate id '" + id + "'");
      return null;
    }

    String severityStr = strOrNull(map.get("severity"));
    if (severityStr == null) {
      errors.add("'" + id + "': missing 'severity'");
      return null;
    }
    Standard.Severity severity;
    try {
      severity = Standard.Severity.valueOf(severityStr.toUpperCase());
    } catch (IllegalArgumentException ex) {
      errors.add(
          "'"
              + id
              + "': invalid severity '"
              + severityStr
              + "' (expected BLOCKING|WARNING|ADVISORY)");
      return null;
    }

    String description = strOrNull(map.get("description"));
    if (description == null || description.isBlank()) {
      errors.add("'" + id + "': missing 'description'");
      return null;
    }

    Object enforcementRaw = map.get("enforcement");
    if (!(enforcementRaw instanceof Map)) {
      errors.add("'" + id + "': missing or non-map 'enforcement'");
      return null;
    }
    Standard.Enforcement enforcement;
    try {
      enforcement = parseEnforcement((Map<String, Object>) enforcementRaw, id, errors);
    } catch (Exception ex) {
      errors.add("'" + id + "' enforcement: " + ex.getMessage());
      return null;
    }
    if (enforcement == null) return null; // error already recorded

    Standard.Scope scope = parseScope(map.get("scope"));
    String rationale = strOrNull(map.get("rationale"));
    List<String> relatedAdrs = strListOrEmpty(map.get("related_adrs"));
    boolean locked = Boolean.TRUE.equals(map.get("org_level_locked"));

    return new Standard(
        id, scope, severity, description, enforcement, rationale, relatedAdrs, locked);
  }

  @SuppressWarnings("unchecked")
  Standard.Enforcement parseEnforcement(Map<String, Object> em, String stdId, List<String> errors) {
    String type = strOrNull(em.get("type"));
    if (type == null) {
      errors.add("'" + stdId + "': enforcement.type required");
      return null;
    }
    switch (type.toLowerCase()) {
      case "lint":
        {
          String tool = strOrNull(em.get("tool"));
          String cfg = strOrNull(em.get("config"));
          if (tool == null) errors.add("'" + stdId + "': lint enforcement requires 'tool'");
          return tool == null ? null : new Standard.LintEnforcement(tool, cfg);
        }
      case "file-pattern":
        {
          List<String> forbidden = strListOrEmpty(em.get("forbidden_patterns"));
          List<String> required = strListOrEmpty(em.get("required_patterns"));
          if (forbidden.isEmpty() && required.isEmpty()) {
            errors.add(
                "'"
                    + stdId
                    + "': file-pattern enforcement requires at least "
                    + "'forbidden_patterns' or 'required_patterns'");
            return null;
          }
          return new Standard.FilePatternEnforcement(forbidden, required);
        }
      case "llm-review":
        {
          String prompt = strOrNull(em.get("review_prompt"));
          if (prompt == null) {
            errors.add("'" + stdId + "': llm-review enforcement requires 'review_prompt'");
            return null;
          }
          return new Standard.LlmReviewEnforcement(prompt);
        }
      case "ast-check":
        {
          Object ruleRaw = em.get("rule");
          if (!(ruleRaw instanceof Map)) {
            errors.add("'" + stdId + "': ast-check enforcement requires 'rule' map");
            return null;
          }
          Map<String, Object> rule = (Map<String, Object>) ruleRaw;
          String kind = strOrNull(rule.get("kind"));
          String script = strOrNull(rule.get("script"));
          if (kind == null) {
            errors.add("'" + stdId + "': ast-check rule.kind required");
            return null;
          }
          if ("custom".equalsIgnoreCase(kind) && script == null) {
            errors.add("'" + stdId + "': ast-check rule.kind=custom requires rule.script");
            return null;
          }
          Map<String, Object> params = new java.util.HashMap<>(rule);
          params.remove("kind");
          params.remove("script");
          return new Standard.AstCheckEnforcement(kind, params, script);
        }
      default:
        errors.add(
            "'"
                + stdId
                + "': unknown enforcement.type '"
                + type
                + "' (expected lint|file-pattern|llm-review|ast-check)");
        return null;
    }
  }

  @SuppressWarnings("unchecked")
  Standard.Scope parseScope(Object raw) {
    if (!(raw instanceof Map)) return null;
    Map<String, Object> m = (Map<String, Object>) raw;
    Standard.Scope scope =
        new Standard.Scope(
            strListOrNull(m.get("languages")),
            strListOrNull(m.get("paths")),
            strListOrNull(m.get("file_patterns")),
            strListOrNull(m.get("repos")));
    return scope.isEmpty() ? null : scope;
  }

  private static String strOrNull(Object o) {
    return o == null ? null : o.toString();
  }

  @SuppressWarnings("unchecked")
  private static List<String> strListOrEmpty(Object o) {
    if (!(o instanceof List<?> l)) return List.of();
    List<String> out = new ArrayList<>();
    for (Object item : l) if (item != null) out.add(item.toString());
    return out;
  }

  @SuppressWarnings("unchecked")
  private static List<String> strListOrNull(Object o) {
    List<String> l = strListOrEmpty(o);
    return l.isEmpty() ? null : l;
  }

  /** Thrown when one or more standards fail validation. */
  public static class ParseException extends RuntimeException {
    public final List<String> errors;

    public ParseException(List<String> errors) {
      super("Standards YAML invalid:\n  - " + String.join("\n  - ", errors));
      this.errors = List.copyOf(errors);
    }
  }
}
