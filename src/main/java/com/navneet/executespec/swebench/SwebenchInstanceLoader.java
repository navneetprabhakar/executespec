package com.navneet.executespec.swebench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads SWE-bench instance metadata from a local JSON dump of {@code princeton-nlp/SWE-Bench_Lite}.
 * Lazy-loaded + cached in-process — the dataset is small (~300 dev instances), fits in memory.
 *
 * <p>Dump shape: {@code [{instance_id, repo, base_commit, problem_statement, FAIL_TO_PASS,
 * PASS_TO_PASS, version, ...}, ...]}. We extract only the fields we need to build a worker prompt +
 * capture a patch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SwebenchInstanceLoader {

  private final SwebenchProperties props;
  private final ObjectMapper mapper = new ObjectMapper();

  /** {@code instance_id → SwebenchInstance} cache; populated on first {@link #findById} call. */
  private volatile Map<String, SwebenchInstance> cache;

  /**
   * Subset of SWE-bench instance fields the worker pipeline cares about.
   *
   * <p>{@code testPatch} is the unified-diff that ADDS the FAIL_TO_PASS tests on top of {@code
   * baseCommit}. Crucially, those test bodies do NOT exist in {@code /testbed} at agent-run time —
   * the SWE-bench harness applies them only AFTER the agent's patch lands. We inject {@code
   * testPatch} into the worker's user prompt so it can read the gold assertions; without it, the
   * agent guesses at what behaviour to produce based on test NAMES alone (failure mode of
   * astroid-1268: produced {@code return "Unknown"} when the assertion required {@code
   * "Unknown.Unknown()"}).
   *
   * <p>{@code patch} is the gold-standard solution diff. We deliberately do NOT inject it (that
   * would be cheating) but record the field for offline analysis tooling.
   */
  public record SwebenchInstance(
      String instanceId,
      String repo,
      String baseCommit,
      String problemStatement,
      List<String> failToPass,
      List<String> passToPass,
      String version,
      String testPatch,
      String patch,
      String dockerhubTag,
      String repoLanguage,
      String interfaceName) {
    public boolean isSweBenchPro() {
      return dockerhubTag != null && !dockerhubTag.isBlank();
    }
  }

  public SwebenchInstance findById(String instanceId) {
    Map<String, SwebenchInstance> c = cache;
    if (c == null) {
      synchronized (this) {
        if (cache == null) cache = loadAll();
        c = cache;
      }
    }
    SwebenchInstance found = c.get(instanceId);
    if (found == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          "instance_id '"
              + instanceId
              + "' not found in "
              + props.getInstancesFile()
              + " ("
              + c.size()
              + " loaded)");
    }
    return found;
  }

  /** For listing / preview endpoints. Returns an immutable view of the cache. */
  public List<String> allInstanceIds() {
    Map<String, SwebenchInstance> c = cache;
    if (c == null) {
      synchronized (this) {
        if (cache == null) cache = loadAll();
        c = cache;
      }
    }
    return new ArrayList<>(c.keySet());
  }

  private Map<String, SwebenchInstance> loadAll() {
    Path p = Paths.get(props.getInstancesFile());
    if (!Files.isRegularFile(p)) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "swebench instances file missing at " + p + " (set swebench.instances-file)");
    }
    try {
      JsonNode arr = mapper.readTree(p.toFile());
      if (!arr.isArray()) {
        throw new IOException("expected JSON array, got " + arr.getNodeType());
      }
      Map<String, SwebenchInstance> out = new ConcurrentHashMap<>(arr.size() * 2);
      Iterator<JsonNode> it = arr.elements();
      while (it.hasNext()) {
        JsonNode node = it.next();
        String id = node.path("instance_id").asText();
        if (id == null || id.isBlank()) continue;
        out.put(id, fromNode(node));
      }
      log.info("SwebenchInstanceLoader: loaded {} instances from {}", out.size(), p);
      return out;
    } catch (IOException ioe) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "failed to parse swebench instances file " + p + ": " + ioe.getMessage());
    }
  }

  private SwebenchInstance fromNode(JsonNode n) {
    return new SwebenchInstance(
        n.path("instance_id").asText(),
        n.path("repo").asText(),
        n.path("base_commit").asText(),
        n.path("problem_statement").asText(),
        // SWE-bench stores FAIL_TO_PASS / PASS_TO_PASS as JSON strings inside the field, not as
        // arrays — quirky dataset format. SWE-bench Pro uses lowercase fields and often arrays.
        parseFlexibleList(firstPresent(n, "FAIL_TO_PASS", "fail_to_pass")),
        parseFlexibleList(firstPresent(n, "PASS_TO_PASS", "pass_to_pass")),
        n.path("version").asText(),
        n.path("test_patch").asText(""),
        n.path("patch").asText(""),
        n.path("dockerhub_tag").asText(""),
        n.path("repo_language").asText(""),
        n.path("interface").asText(""));
  }

  private JsonNode firstPresent(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && !value.isMissingNode() && !value.isNull()) return value;
    }
    return mapper.createArrayNode();
  }

  private List<String> parseFlexibleList(JsonNode rawNode) {
    if (rawNode == null || rawNode.isNull() || rawNode.isMissingNode()) return List.of();
    if (rawNode.isArray()) {
      List<String> out = new ArrayList<>(rawNode.size());
      for (JsonNode el : rawNode) out.add(el.asText());
      return out;
    }
    if (!rawNode.isTextual()) return List.of();
    return parseJsonStringList(rawNode.asText("[]"));
  }

  private List<String> parseJsonStringList(String raw) {
    try {
      JsonNode arr = mapper.readTree(raw);
      if (!arr.isArray()) return List.of();
      List<String> out = new ArrayList<>(arr.size());
      for (JsonNode el : arr) out.add(el.asText());
      return out;
    } catch (IOException ioe) {
      log.warn("SwebenchInstanceLoader: bad list field {} — using empty", ioe.getMessage());
      return List.of();
    }
  }

  /**
   * Translates an instance ID to the SWE-bench docker image name. Convention: SWE-bench tags each
   * instance with {@code <repo-org>_1776_<repo-task>}, where the {@code __} in the instance ID maps
   * to {@code _1776_}. Example: {@code sqlfluff__sqlfluff-1625} → {@code
   * sqlfluff_1776_sqlfluff-1625} → image template substitution.
   */
  public String imageNameFor(String instanceId) {
    SwebenchInstance instance = findById(instanceId);
    if (instance.isSweBenchPro() || props.isSweBenchPro()) {
      String tag = instance.dockerhubTag();
      if (tag == null || tag.isBlank()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "SWE-bench Pro instance '"
                + instanceId
                + "' is missing dockerhub_tag in "
                + props.getInstancesFile());
      }
      if (tag.contains("/") && tag.contains(":")) return tag;
      return props.getProImageRepository() + ":" + tag;
    }
    return String.format(props.getImageTemplate(), instanceId.replace("__", "_1776_"));
  }
}
