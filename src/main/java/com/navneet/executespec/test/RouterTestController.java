package com.navneet.executespec.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 Phase 2 verification endpoint.
 *
 * <p>End-to-end probe of {@link LlmRouter} for every model in the V2 matrix:
 *
 * <ol>
 *   <li>Create a temp workspace with a known file
 *   <li>Send a prompt that requires the model to call {@code readFile}
 *   <li>Verify the model emits a native tool call (not text)
 *   <li>Dispatch the tool through the registry, capture the result
 *   <li>Report the call name, args, and dispatch outcome per provider/model
 * </ol>
 *
 * <p>This proves Phase 0 (native tool calling) + Phase 1 (tool registry) + Phase 2 (router) all
 * compose correctly. It does NOT exercise the multi-turn agent loop (tool result → next call) —
 * that's Phase 3 (WorkerAgent).
 */
// Audit r5 (§6.2, 2026-05-06) — see ContextStoreTestController for rationale.
@org.springframework.context.annotation.Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class RouterTestController {

  private final LlmRouter router;
  private final ToolRegistry registry;
  private final ObjectMapper objectMapper;

  @GetMapping("/router")
  public Map<String, Object> testRouter() throws IOException {
    Path workspace = Files.createTempDirectory("v2-router-test-");
    try {
      // Seed a known file the model will be told to read.
      Files.writeString(workspace.resolve("secret.txt"), "the secret value is purple-banana-42");

      ExecutionContext ctx =
          new ExecutionContext("router-test-run", "router-test-task", workspace.toRealPath());

      List<Message> messages =
          List.of(
              new SystemMessage(
                  "You are a test agent. When asked to read a file, "
                      + "use the readFile tool to do so. Output the readFile tool call "
                      + "and stop — do not emit explanatory text."),
              new UserMessage("Read the file 'secret.txt' and report what it contains."));

      // Same matrix as Phase 0's /native-tools/all (excluding the gpt-4o controls
      // since they're not in the V2 production matrix).
      List<String[]> matrix =
          List.of(
              new String[] {"anthropic", "claude-sonnet-4-6"},
              new String[] {"anthropic", "claude-haiku-4-5-20251001"},
              new String[] {"gemini", "gemini-2.5-flash"},
              new String[] {"gemini", "gemini-3-flash-preview"},
              new String[] {"openai", "gpt-5.4"},
              new String[] {"openai", "gpt-5.4-mini"});

      List<Map<String, Object>> results = new ArrayList<>();
      for (String[] pair : matrix) {
        results.add(probe(pair[0], pair[1], messages, ctx));
      }

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("workspace", workspace.toString());
      out.put("totalTested", results.size());
      out.put("passed", results.stream().filter(r -> Boolean.TRUE.equals(r.get("ok"))).count());
      out.put("failed", results.stream().filter(r -> !Boolean.TRUE.equals(r.get("ok"))).count());
      out.put("results", results);
      return out;
    } finally {
      deleteTree(workspace);
    }
  }

  private Map<String, Object> probe(
      String provider, String model, List<Message> messages, ExecutionContext ctx) {
    log.info("RouterTest probe START provider={} model={}", provider, model);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("provider", provider);
    r.put("model", model);
    try {
      ChatResponse resp =
          router.call(
              LlmRequest.builder()
                  .provider(provider)
                  .model(model)
                  .messages(messages)
                  .context(ctx)
                  .temperature(0.0)
                  .maxTokens(512)
                  .build());

      // Locate the first tool call across all generations (Anthropic returns
      // multiple gens with the tool call in #1; OpenAI/Gemini in #0).
      AssistantMessage.ToolCall first = null;
      for (var gen : resp.getResults()) {
        var calls = gen.getOutput().getToolCalls();
        if (calls != null && !calls.isEmpty()) {
          first = calls.get(0);
          break;
        }
      }
      if (first == null) {
        r.put("ok", false);
        r.put(
            "error",
            "no native tool call in response; finishReason="
                + resp.getResult().getMetadata().getFinishReason());
        return r;
      }
      r.put("toolName", first.name());
      r.put("toolArgs", first.arguments());

      // Dispatch the tool through the registry — closes the loop.
      Tool tool = registry.find(first.name());
      if (tool == null) {
        r.put("ok", false);
        r.put("error", "model called unknown tool: " + first.name());
        return r;
      }
      ToolResult result = tool.execute(objectMapper.readTree(first.arguments()), ctx);
      switch (result) {
        case ToolResult.Ok ok -> {
          String content = ok.content();
          boolean foundSecret = content.contains("purple-banana-42");
          r.put("ok", foundSecret);
          r.put("dispatchKind", "Ok");
          r.put(
              "dispatchPreview",
              content.length() > 200 ? content.substring(0, 200) + "..." : content);
          if (!foundSecret) r.put("error", "tool result missing expected secret");
        }
        case ToolResult.Err err -> {
          r.put("ok", false);
          r.put("dispatchKind", "Err");
          r.put("dispatchKindEnum", err.kind().name());
          r.put("dispatchMessage", err.message());
        }
      }
    } catch (Exception e) {
      r.put("ok", false);
      String msg = e.getMessage();
      r.put(
          "error",
          e.getClass().getSimpleName()
              + ": "
              + (msg == null ? "" : msg.substring(0, Math.min(300, msg.length()))));
    }
    return r;
  }

  private static void deleteTree(Path root) {
    try {
      if (!Files.exists(root)) return;
      try (var walk = Files.walk(root)) {
        walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                  }
                });
      }
    } catch (IOException ignored) {
    }
  }
}
