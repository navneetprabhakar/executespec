package com.navneet.executespec.adr;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import lombok.extern.slf4j.Slf4j;

/**
 * E3.12 — parses an ADR's YAML frontmatter (between the leading {@code ---} delimiters) into a
 * strongly-typed {@link Parsed} record.
 *
 * <p>Required fields per plan §5.11: {@code id}, {@code title}, {@code status}, {@code date}.
 * Validation collects every error into {@link FrontmatterException} so the customer sees all
 * problems in one PR-validation pass (same pattern as the standards parser).
 */
@Component
@Slf4j
public class AdrFrontmatterParser {

  private static final Pattern FRONTMATTER =
      Pattern.compile("(?s)^\\s*---\\s*\\n(.+?)\\n---\\s*\\n?(.*)$");

  public Parsed parse(String fileContent) {
    if (fileContent == null || fileContent.isBlank()) {
      throw new FrontmatterException(List.of("ADR file is empty"));
    }
    Matcher m = FRONTMATTER.matcher(fileContent);
    if (!m.find()) {
      throw new FrontmatterException(
          List.of(
              "ADR file is missing YAML frontmatter "
                  + "(expected leading '---' / '---' delimiters)"));
    }
    String yamlBody = m.group(1);
    String markdownBody = m.group(2);
    Object raw;
    try {
      raw = new Yaml().load(yamlBody);
    } catch (Exception ex) {
      throw new FrontmatterException(List.of("YAML parse failed: " + ex.getMessage()));
    }
    if (!(raw instanceof Map<?, ?> mapRaw)) {
      throw new FrontmatterException(List.of("Frontmatter must be a YAML map"));
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) mapRaw;
    List<String> errors = new ArrayList<>();

    Integer id = parseInt(map.get("id"), errors);
    String title = strOrNull(map.get("title"));
    if (title == null || title.isBlank()) errors.add("'title' required");
    String statusStr = strOrNull(map.get("status"));
    Adr.Status status = parseStatus(statusStr, errors);
    LocalDate date = parseDate(map.get("date"), errors);

    if (!errors.isEmpty()) throw new FrontmatterException(errors);

    return new Parsed(
        id,
        title,
        status,
        date,
        strList(map.get("deciders")),
        intList(map.get("supersedes")),
        intList(map.get("superseded_by")),
        intList(map.get("related_adrs")),
        strList(map.get("related_standards")),
        strList(map.get("tags")),
        markdownBody);
  }

  private Integer parseInt(Object raw, List<String> errors) {
    if (raw == null) {
      errors.add("'id' required");
      return null;
    }
    if (raw instanceof Number n) return n.intValue();
    try {
      return Integer.parseInt(raw.toString());
    } catch (NumberFormatException ex) {
      errors.add("'id' must be an integer (got '" + raw + "')");
      return null;
    }
  }

  private Adr.Status parseStatus(String raw, List<String> errors) {
    if (raw == null) {
      errors.add("'status' required");
      return null;
    }
    try {
      return Adr.Status.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException ex) {
      errors.add("'status' must be PROPOSED|ACCEPTED|DEPRECATED|SUPERSEDED (got '" + raw + "')");
      return null;
    }
  }

  private LocalDate parseDate(Object raw, List<String> errors) {
    if (raw == null) {
      errors.add("'date' required");
      return null;
    }
    if (raw instanceof LocalDate ld) return ld;
    if (raw instanceof java.util.Date d) {
      return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }
    try {
      return LocalDate.parse(raw.toString());
    } catch (Exception ex) {
      errors.add("'date' must be ISO yyyy-MM-dd (got '" + raw + "')");
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private List<String> strList(Object raw) {
    if (!(raw instanceof List<?> l)) return List.of();
    List<String> out = new ArrayList<>();
    for (Object o : l) if (o != null) out.add(o.toString());
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<Integer> intList(Object raw) {
    if (!(raw instanceof List<?> l)) return List.of();
    List<Integer> out = new ArrayList<>();
    for (Object o : l) {
      if (o == null) continue;
      try {
        out.add(o instanceof Number n ? n.intValue() : Integer.parseInt(o.toString()));
      } catch (NumberFormatException ex) {
        // skip — frontmatter validation runs once at PR-merge; bad
        // refs are not fatal here, just dropped
      }
    }
    return out;
  }

  private static String strOrNull(Object o) {
    return o == null ? null : o.toString();
  }

  public record Parsed(
      Integer id,
      String title,
      Adr.Status status,
      LocalDate date,
      List<String> deciders,
      List<Integer> supersedes,
      List<Integer> supersededBy,
      List<Integer> relatedAdrs,
      List<String> relatedStandards,
      List<String> tags,
      String markdownBody) {}

  public static class FrontmatterException extends RuntimeException {
    public final List<String> errors;

    public FrontmatterException(List<String> errors) {
      super("ADR frontmatter invalid:\n  - " + String.join("\n  - ", errors));
      this.errors = List.copyOf(errors);
    }
  }
}
