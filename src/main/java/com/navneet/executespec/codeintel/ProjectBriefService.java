package com.navneet.executespec.codeintel;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds the short natural-language "project brief" injected into the planner system prompt +
 * summarises the architecture doc into a 2-4 sentence {@code summary.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectBriefService {

  private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,3})\\s+(.+?)\\s*$");

  private static final String SUMMARY_SYSTEM =
      """
      Summarise the supplied architecture document into 2 to 4 concise sentences.
      Rules:
      - Focus on what the project does, the repos involved, and the main components.
      - Plain prose, no markdown, no lists, no headings.
      - No preamble ("This project..." is fine; "Here is the summary" is not).
      - Keep under 80 words.
      """;

  private final ProjectWorkspaceService workspace;
  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;

  /**
   * Returns a compact block ready to paste into a planner system prompt: summary + top-level arch
   * headings + codemap index. Caps at ~800 tokens.
   */
  public String buildBrief(String projectId) {
    StringBuilder sb = new StringBuilder();
    workspace
        .readFile(projectId, "summary.md")
        .ifPresent(
            s -> {
              if (!s.isBlank()) sb.append("## PROJECT SUMMARY\n").append(s.trim()).append("\n\n");
            });
    workspace
        .readFile(projectId, "architecture.md")
        .ifPresent(
            doc -> {
              List<String> headings = extractHeadings(doc, 3);
              if (!headings.isEmpty()) {
                sb.append("## ARCHITECTURE OUTLINE\n");
                for (String h : headings) sb.append("- ").append(h).append('\n');
                sb.append('\n');
              }
            });
    String result = sb.toString();
    // ~800 tokens ≈ 3200 chars. Cap defensively.
    if (result.length() > 3200) result = result.substring(0, 3200) + "\n…(truncated)\n";
    return result;
  }

  /**
   * Produce a 2-4 sentence summary of the given architecture markdown. Never throws — on failure
   * returns a deterministic one-liner derived from the doc.
   */
  public String summarise(String projectId, String archMarkdown, LlmProvider provider) {
    if (archMarkdown == null || archMarkdown.isBlank()) return "";
    try {
      LlmProvider effective = provider != null ? provider : LlmProvider.OPENAI;
      ResolvedModel resolved = modelResolver.resolve("ProjectBriefService", effective);
      Prompt prompt =
          new Prompt(List.of(new SystemMessage(SUMMARY_SYSTEM), new UserMessage(archMarkdown)));
      ChatResponse response =
          fallbackLlmRouter.callWithFallback(
              resolved.providerName(),
              resolved.getModel(),
              prompt,
              "project-brief-" + projectId,
              "project-brief",
              FallbackLlmRouter.FallbackMode.IMMEDIATE,
              0,
              false);
      String text = response.getResult().getOutput().getText();
      return text == null ? "" : text.trim();
    } catch (Exception e) {
      log.debug("ProjectBriefService summarise failed for {}: {}", projectId, e.getMessage());
      String fallback =
          extractHeadings(archMarkdown, 1).stream()
              .findFirst()
              .orElse("Project architecture overview.");
      return fallback;
    }
  }

  private static List<String> extractHeadings(String markdown, int maxDepth) {
    java.util.List<String> out = new java.util.ArrayList<>();
    Matcher m = HEADING.matcher(markdown);
    while (m.find()) {
      int depth = m.group(1).length();
      if (depth > maxDepth) continue;
      String title = m.group(2).trim();
      out.add(depth == 1 ? "# " + title : (depth == 2 ? "## " + title : "### " + title));
      if (out.size() >= 40) break;
    }
    return out;
  }
}
