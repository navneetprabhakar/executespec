package com.navneet.executespec.contracts.breaking;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.contracts.ContractRegistry;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.7 — pure-Java JSON Schema diff classifier. No external CLI required; the deterministic rules
 * from plan §2.5 are encoded directly:
 *
 * <ul>
 *   <li>Field removal — {@link ContractDiff.Change.Severity#BREAKING}
 *   <li>Type change on an existing property — {@code BREAKING}
 *   <li>Adding a property to {@code required[]} — {@code BREAKING}
 *   <li>Removing a property from {@code required[]} — {@code NON_BREAKING}
 *   <li>Adding an optional property — {@code NON_BREAKING}
 * </ul>
 *
 * <p>Walks the schema tree depth-first; tracks the current JSON-pointer path so {@link
 * ContractDiff.Change#location} is precise (e.g. {@code properties.user.properties.email}).
 */
@Component
@Slf4j
public class JsonSchemaBreakingChangeClassifier implements BreakingChangeClassifier {

  private final ObjectMapper mapper;

  public JsonSchemaBreakingChangeClassifier(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public ContractRegistry.ContractType type() {
    return ContractRegistry.ContractType.JSON_SCHEMA;
  }

  @Override
  public String name() {
    return "JsonSchemaBreakingChangeClassifier";
  }

  @Override
  public ContractDiff classify(String oldContent, String newContent) {
    List<ContractDiff.Change> changes = new ArrayList<>();
    try {
      JsonNode oldRoot = mapper.readTree(oldContent);
      JsonNode newRoot = mapper.readTree(newContent);
      walk(oldRoot, newRoot, "", changes);
    } catch (Exception ex) {
      log.warn("[JsonSchemaBreakingChangeClassifier] parse failed: {}", ex.getMessage());
      return new ContractDiff(
          "JSON parse failed: " + ex.getMessage(),
          List.of(),
          ContractDiff.Confidence.UNCLASSIFIED,
          name());
    }
    long breaking =
        changes.stream().filter(c -> c.severity() == ContractDiff.Change.Severity.BREAKING).count();
    String summary =
        breaking > 0
            ? breaking + " breaking change" + (breaking == 1 ? "" : "s") + " detected"
            : changes.isEmpty()
                ? "No structural changes detected"
                : changes.size() + " non-breaking change(s) detected";
    return new ContractDiff(summary, changes, ContractDiff.Confidence.DETERMINISTIC, name());
  }

  void walk(JsonNode oldNode, JsonNode newNode, String path, List<ContractDiff.Change> out) {
    if (oldNode == null || newNode == null) return;

    // Type change on this node?
    String oldType = textOrNull(oldNode.get("type"));
    String newType = textOrNull(newNode.get("type"));
    if (oldType != null && newType != null && !oldType.equals(newType)) {
      out.add(
          new ContractDiff.Change(
              ContractDiff.Change.Severity.BREAKING,
              "type changed from " + oldType + " to " + newType,
              path.isEmpty() ? "(root)" : path));
    }

    // required[] diff
    Set<String> oldReq = stringSet(oldNode.get("required"));
    Set<String> newReq = stringSet(newNode.get("required"));
    for (String r : newReq) {
      if (!oldReq.contains(r)) {
        out.add(
            new ContractDiff.Change(
                ContractDiff.Change.Severity.BREAKING,
                "property '" + r + "' added to required[]",
                path + ".required"));
      }
    }
    for (String r : oldReq) {
      if (!newReq.contains(r)) {
        out.add(
            new ContractDiff.Change(
                ContractDiff.Change.Severity.NON_BREAKING,
                "property '" + r + "' removed from required[]",
                path + ".required"));
      }
    }

    // properties diff (recursion)
    JsonNode oldProps = oldNode.get("properties");
    JsonNode newProps = newNode.get("properties");
    if (oldProps != null && newProps != null) {
      // Removed properties = breaking
      Iterator<Map.Entry<String, JsonNode>> oldFields = oldProps.fields();
      while (oldFields.hasNext()) {
        Map.Entry<String, JsonNode> e = oldFields.next();
        String childPath = path + ".properties." + e.getKey();
        if (newProps.get(e.getKey()) == null) {
          out.add(
              new ContractDiff.Change(
                  ContractDiff.Change.Severity.BREAKING,
                  "property '" + e.getKey() + "' removed",
                  childPath));
        } else {
          walk(e.getValue(), newProps.get(e.getKey()), childPath, out);
        }
      }
      // Added properties = non-breaking
      Iterator<Map.Entry<String, JsonNode>> newFields = newProps.fields();
      while (newFields.hasNext()) {
        Map.Entry<String, JsonNode> e = newFields.next();
        if (oldProps.get(e.getKey()) == null) {
          out.add(
              new ContractDiff.Change(
                  ContractDiff.Change.Severity.NON_BREAKING,
                  "property '" + e.getKey() + "' added",
                  path + ".properties." + e.getKey()));
        }
      }
    }
  }

  private static String textOrNull(JsonNode n) {
    return n == null || n.isNull() ? null : n.asText();
  }

  private static Set<String> stringSet(JsonNode arr) {
    Set<String> out = new HashSet<>();
    if (arr == null || !arr.isArray()) return out;
    arr.forEach(
        n -> {
          if (n.isTextual()) out.add(n.asText());
        });
    return out;
  }
}
