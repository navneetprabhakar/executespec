package com.navneet.executespec.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.GitTool;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ContextAgent {

  private final ContextAgentScanner scanner;
  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;
  private final ContextAgentProperties props;
  private final StateStore stateStore;
  private final GitTool gitTool;
  private final ObjectMapper objectMapper;

  @Value("classpath:prompts/context-agent.txt")
  private Resource promptTemplate;

  public ContextAgent(
      ContextAgentScanner scanner,
      FallbackLlmRouter fallbackLlmRouter,
      ModelResolver modelResolver,
      ContextAgentProperties props,
      StateStore stateStore,
      GitTool gitTool,
      ObjectMapper objectMapper) {
    this.scanner = scanner;
    this.fallbackLlmRouter = fallbackLlmRouter;
    this.modelResolver = modelResolver;
    this.props = props;
    this.stateStore = stateStore;
    this.gitTool = gitTool;
    this.objectMapper = objectMapper;
  }

  public void runAsync(
      PipelineRun run, List<WorkerReport> workerReports, ReviewReport reviewReport) {
    Thread.ofVirtual()
        .name("context-agent-" + run.getRunId())
        .start(() -> run(run, workerReports, reviewReport));
  }

  private void run(PipelineRun run, List<WorkerReport> workerReports, ReviewReport reviewReport) {
    if (!props.isEnabled()) return;
    log.info("Context Agent starting for run {}", run.getRunId());

    try {
      Path projectPath = gitTool.getWorkspacePath(run.getRunId());
      ContextAgentInput input = scanner.scan(run, workerReports, reviewReport, projectPath);

      if (!hasAnythingChanged(input)) {
        log.info("Context Agent: nothing changed in run {} — skipping", run.getRunId());
        return;
      }

      String llmResponse = callLlm(input, run);
      if (llmResponse == null || llmResponse.isBlank()) {
        log.warn("Context Agent: empty LLM response for run {}", run.getRunId());
        return;
      }

      writeContextDoc(projectPath, input.currentContextDoc(), llmResponse, run.getRunId());
      appendDecisionsEntry(projectPath, input, run);
      // Auto-commit DISABLED — JGit-commit against the customer's project workspace from a
      // background pipeline thread can co-opt in-flight edits. The files are written; the
      // caller (RunOrchestrator / project flow) handles commit via ScmPushService.
      // Audit 2026-05-12 review item F #45.

      log.info(
          "Context Agent completed for run {} — files written, NOT auto-committed (handled by"
              + " pipeline push flow)",
          run.getRunId());

    } catch (Exception e) {
      log.error("Context Agent failed for run {}: {}", run.getRunId(), e.getMessage(), e);
    }
  }

  private String callLlm(ContextAgentInput input, PipelineRun run) throws IOException {
    String systemPrompt = promptTemplate.getContentAsString(StandardCharsets.UTF_8);
    String userMessage = buildUserMessage(input);

    ResolvedModel resolved = modelResolver.resolve("ContextAgent", run.getAiProviderEnum());

    Prompt prompt =
        new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)));

    var response =
        fallbackLlmRouter.callWithFallback(
            resolved.providerName(),
            resolved.getModel(),
            prompt,
            run.getRunId(),
            "context-agent",
            FallbackLlmRouter.FallbackMode.IMMEDIATE,
            0,
            false);

    // Track tokens for billing accuracy
    try {
      int inTok = response.getMetadata().getUsage().getPromptTokens().intValue();
      int outTok = response.getMetadata().getUsage().getCompletionTokens().intValue();
      stateStore.addTokensToRun(run.getRunId(), inTok, outTok);
      log.debug("ContextAgent tokens: run={} in={} out={}", run.getRunId(), inTok, outTok);
    } catch (Exception e) {
      log.warn(
          "ContextAgent: failed to track tokens for run {}: {}", run.getRunId(), e.getMessage());
    }

    return response.getResult().getOutput().getText();
  }

  private String buildUserMessage(ContextAgentInput input) {
    var sb = new StringBuilder();
    sb.append("## Current CODEBASE_CONTEXT.md\n\n")
        .append(input.currentContextDoc())
        .append("\n\n");
    sb.append("## Git Changes This Run\n\n").append(input.gitDiffSummary()).append("\n\n");

    if (!input.newPackagesDetected().isEmpty()) {
      sb.append("## New Packages Detected (not in context doc)\n\n");
      input.newPackagesDetected().forEach(p -> sb.append("- ").append(p).append("\n"));
      sb.append("\n");
    }
    if (!input.newFilesDetected().isEmpty()) {
      sb.append("## New Classes Detected (not in context doc)\n\n");
      input.newFilesDetected().forEach(f -> sb.append("- ").append(f).append("\n"));
      sb.append("\n");
    }

    sb.append("## Worker Reports\n\n");
    input.workerReportSummaries().forEach(r -> sb.append("- ").append(r).append("\n"));
    sb.append("\n");

    sb.append("## Reviewer Summary\n\n").append(input.reviewerSummary()).append("\n\n");
    sb.append("## Recent decisions.md (do not duplicate)\n\n")
        .append(input.recentDecisions())
        .append("\n\n");
    sb.append("Run goal: ").append(input.runGoal()).append("\n");
    return sb.toString();
  }

  /**
   * Parses the LLM's JSON patch response and applies KEEP/UPDATE/ADD/REMOVE operations to the
   * current CODEBASE_CONTEXT.md. If the response is not valid JSON patch format, preserves the
   * current context doc and writes the rejected response to a sidecar for operator debugging.
   */
  void writeContextDoc(Path projectPath, String currentDoc, String llmResponse, String runId)
      throws IOException {
    Path p = projectPath.resolve(".agent-context/CODEBASE_CONTEXT.md");
    Files.createDirectories(p.getParent());

    // Strip markdown code fences if the model wrapped the JSON in ```json ... ```
    String stripped = llmResponse.strip();
    if (stripped.startsWith("```")) {
      int firstNewline = stripped.indexOf('\n');
      int lastFence = stripped.lastIndexOf("```");
      if (firstNewline >= 0 && lastFence > firstNewline) {
        stripped = stripped.substring(firstNewline + 1, lastFence).strip();
      }
    }

    // Attempt JSON patch parsing
    if (stripped.startsWith("[")) {
      try {
        String patched = applyPatch(currentDoc != null ? currentDoc : "", stripped, runId);
        Files.writeString(
            p, patched, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        log.info("Context Agent: applied JSON patch to CODEBASE_CONTEXT.md for run {}", runId);
        return;
      } catch (Exception e) {
        log.warn(
            "Context Agent: JSON patch parse failed for run {} — preserving current doc: {}",
            runId,
            e.getMessage());
      }
    }

    // Fallback: preserve canonical doc; raw LLM text is diagnostic only. Overwriting
    // CODEBASE_CONTEXT.md with malformed prose makes the next run's context worse than stale.
    log.warn(
        "Context Agent: LLM response was not a valid JSON patch array for run {} — writing rejected"
            + " sidecar only",
        runId);
    if (currentDoc != null && !Files.exists(p)) {
      Files.writeString(p, currentDoc, StandardOpenOption.CREATE_NEW);
    }
    Path rejected =
        projectPath.resolve(".agent-context/CODEBASE_CONTEXT.rejected." + runId + ".txt");
    Files.writeString(
        rejected, llmResponse, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
  }

  /**
   * Applies a JSON patch array to the current document. Each operation has {@code action}
   * (KEEP/UPDATE/ADD/REMOVE), {@code section} (heading text), and optionally {@code content} (for
   * UPDATE/ADD) and {@code after} (for ADD insertion point).
   *
   * <p>The document is parsed into sections by top-level markdown headings (lines starting with #).
   * Sections are identified by their heading line. The patch is applied in order.
   */
  private String applyPatch(String currentDoc, String patchJson, String runId) throws Exception {
    JsonNode patchArray = objectMapper.readTree(patchJson);
    if (!patchArray.isArray()) {
      throw new IllegalArgumentException("Patch JSON is not an array");
    }

    // Parse current document into an ordered map: heading → full section text (including heading)
    // Use LinkedHashMap to preserve section order
    LinkedHashMap<String, String> sections = parseSections(currentDoc);

    for (JsonNode op : patchArray) {
      String action = op.path("action").asText("").toUpperCase();
      String section = op.path("section").asText("").strip();
      String content = op.path("content").asText(null);
      String after = op.path("after").asText(null);

      if (section.isBlank()) {
        log.debug("Context Agent patch: skipping op with blank section");
        continue;
      }

      switch (action) {
        case "KEEP" -> {
          // No-op: leave existing section unchanged
          log.debug("Context Agent patch: KEEP [{}]", section);
        }
        case "UPDATE" -> {
          if (content == null) {
            log.warn("Context Agent patch: UPDATE [{}] missing content — skipped", section);
            continue;
          }
          String headingPrefix = findMatchingHeading(sections, section);
          if (headingPrefix != null) {
            sections.put(headingPrefix, headingPrefix + "\n\n" + content.strip() + "\n");
            log.debug("Context Agent patch: UPDATE [{}]", section);
          } else {
            // Section not found — treat as ADD at end
            log.warn(
                "Context Agent patch: UPDATE [{}] not found — appending as new section", section);
            sections.put("## " + section, "## " + section + "\n\n" + content.strip() + "\n");
          }
        }
        case "ADD" -> {
          if (content == null) {
            log.warn("Context Agent patch: ADD [{}] missing content — skipped", section);
            continue;
          }
          String newHeading = "## " + section;
          String newSection = newHeading + "\n\n" + content.strip() + "\n";
          if (after != null && !after.isBlank()) {
            String afterKey = findMatchingHeading(sections, after);
            if (afterKey != null) {
              sections = insertAfter(sections, afterKey, newHeading, newSection);
              log.debug("Context Agent patch: ADD [{}] after [{}]", section, after);
              continue;
            }
          }
          // Append at end if no `after` anchor found
          sections.put(newHeading, newSection);
          log.debug("Context Agent patch: ADD [{}] at end", section);
        }
        case "REMOVE" -> {
          String headingPrefix = findMatchingHeading(sections, section);
          if (headingPrefix != null) {
            sections.remove(headingPrefix);
            log.debug("Context Agent patch: REMOVE [{}]", section);
          } else {
            log.debug("Context Agent patch: REMOVE [{}] — section not found, skipping", section);
          }
        }
        default ->
            log.warn("Context Agent patch: unknown action [{}] for section [{}]", action, section);
      }
    }

    return String.join("\n", sections.values());
  }

  /**
   * Parses a markdown document into an ordered map of heading → section text. A "section" starts at
   * a top-level heading (# or ##) and extends until the next heading. Text before the first heading
   * is stored under the key "" (empty string) as a preamble.
   */
  private LinkedHashMap<String, String> parseSections(String doc) {
    LinkedHashMap<String, String> sections = new LinkedHashMap<>();
    if (doc == null || doc.isBlank()) return sections;

    String[] lines = doc.split("\n", -1);
    String currentKey = "";
    List<String> currentLines = new ArrayList<>();

    for (String line : lines) {
      if (line.startsWith("#")) {
        // Flush current section
        if (!currentLines.isEmpty()) {
          sections.put(currentKey, String.join("\n", currentLines));
        }
        currentKey = line; // heading line is the key
        currentLines = new ArrayList<>();
        currentLines.add(line);
      } else {
        currentLines.add(line);
      }
    }
    // Flush last section
    if (!currentLines.isEmpty()) {
      sections.put(currentKey, String.join("\n", currentLines));
    }
    return sections;
  }

  /**
   * Finds the heading key in the sections map that best matches the given section name. Matching is
   * done by checking if the heading line contains the section name (case-insensitive).
   */
  private String findMatchingHeading(LinkedHashMap<String, String> sections, String sectionName) {
    String lower = sectionName.toLowerCase();
    for (String key : sections.keySet()) {
      if (key.toLowerCase().contains(lower)
          || lower.contains(key.replaceAll("^#+\\s*", "").toLowerCase())) {
        return key;
      }
    }
    return null;
  }

  /** Returns a new LinkedHashMap with a new entry inserted immediately after {@code afterKey}. */
  private LinkedHashMap<String, String> insertAfter(
      LinkedHashMap<String, String> original, String afterKey, String newKey, String newValue) {
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : original.entrySet()) {
      result.put(entry.getKey(), entry.getValue());
      if (entry.getKey().equals(afterKey)) {
        result.put(newKey, newValue);
      }
    }
    // If afterKey not found (shouldn't happen since caller checks), append
    if (!result.containsKey(newKey)) {
      result.put(newKey, newValue);
    }
    return result;
  }

  private void appendDecisionsEntry(Path projectPath, ContextAgentInput input, PipelineRun run)
      throws IOException {
    Path p = projectPath.resolve(".agent-context/decisions.md");
    Files.createDirectories(p.getParent());

    String ts =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());

    var entry = new StringBuilder();
    entry
        .append("\n## Context Update — ")
        .append(ts)
        .append(" (run: ")
        .append(run.getRunId())
        .append(")\n\n");
    entry.append("**Goal:** ").append(run.getUserRequest()).append("\n");

    if (!input.newPackagesDetected().isEmpty())
      entry
          .append("**New packages:** ")
          .append(String.join(", ", input.newPackagesDetected()))
          .append("\n");
    if (!input.newFilesDetected().isEmpty())
      entry
          .append("**New classes:** ")
          .append(String.join(", ", input.newFilesDetected()))
          .append("\n");

    long fileCount = input.gitDiffSummary().lines().filter(l -> l.startsWith("  ")).count();
    entry.append("**Files changed:** ").append(fileCount).append("\n");
    entry.append("*Auto-updated by Context Agent.*\n");

    Files.writeString(p, entry.toString(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);
  }

  private boolean hasAnythingChanged(ContextAgentInput input) {
    return !input.newPackagesDetected().isEmpty()
        || !input.newFilesDetected().isEmpty()
        || !"Git diff unavailable".equals(input.gitDiffSummary())
        || !input.workerReportSummaries().isEmpty();
  }
}
