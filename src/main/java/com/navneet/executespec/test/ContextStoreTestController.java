package com.navneet.executespec.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.context.ContextStore;
import com.navneet.executespec.context.SavedContext;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 Phase 8 verification endpoint.
 *
 * <p>Exercises {@link ContextStore} through a full round-trip with every message type we care about
 * (system / user / assistant+toolCalls / toolResponse):
 *
 * <ol>
 *   <li>Build a fake history covering all 4 Spring AI message subclasses
 *   <li>Save it
 *   <li>Confirm {@code exists()} is true
 *   <li>Load it back; verify count, types, and content survive serialization
 *   <li>Delete it
 *   <li>Confirm {@code exists()} is false
 * </ol>
 *
 * <p>Requires Redis running at the configured host. If Redis is unavailable, the wrapper logs a
 * WARN and returns empty/false; the endpoint surfaces that as a {@code redisWired: false} flag for
 * clarity.
 *
 * <p>Usage: {@code GET /api/v2/test/context}
 */
// Audit r5 (§6.2, 2026-05-06) — gate test scaffold on non-prod profiles.
// These /api/v2/test/* controllers were Phase-0 verification scaffolds
// that registered as @RestController in every profile, including prod.
// They expose internal mechanics (raw planner/worker/router calls)
// that have no business on a customer-facing surface. @Profile here
// keeps them reachable on local + dev for engineers but cuts them out
// of every prod-active profile (prod, gcp).
@org.springframework.context.annotation.Profile({"local", "dev"})
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class ContextStoreTestController {

  private final ContextStore store;

  @GetMapping("/context")
  public Map<String, Object> testContextStore() throws IOException {
    Path workspace = Files.createTempDirectory("v2-context-test-");
    try {
      // Use a UUID in the task id so repeated calls don't collide.
      ExecutionContext ctx =
          new ExecutionContext(
              "context-test-run", "context-test-task-" + UUID.randomUUID(), workspace.toRealPath());

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("runId", ctx.runId());
      out.put("taskId", ctx.taskId());
      out.put("redisKey", ContextStore.keyFor(ctx));

      // Seed — one of every message type.
      List<Message> history = buildTestHistory();
      out.put("seededMessageCount", history.size());

      // Save
      int bytesWritten = store.save(ctx, history, 7);
      out.put("bytesWritten", bytesWritten);
      boolean redisWired = bytesWritten > 0;
      out.put("redisWired", redisWired);
      if (!redisWired) {
        out.put("ok", false);
        out.put("note", "Redis not wired — save returned 0. Check Redis connectivity.");
        return out;
      }

      // Exists after save
      boolean existsAfterSave = store.exists(ctx);
      out.put("existsAfterSave", existsAfterSave);

      // Load + verify
      Optional<SavedContext> loaded = store.load(ctx);
      if (loaded.isEmpty()) {
        out.put("ok", false);
        out.put("error", "load returned empty immediately after save");
        return out;
      }
      SavedContext sc = loaded.get();
      out.put("loadedMessageCount", sc.history().size());
      out.put("loadedIteration", sc.iteration());
      out.put("loadedSizeBytes", sc.sizeBytes());

      // Per-type check — the loaded history should match original type sequence.
      List<String> originalTypes = history.stream().map(m -> simpleType(m)).toList();
      List<String> loadedTypes = sc.history().stream().map(m -> simpleType(m)).toList();
      boolean typesMatch = originalTypes.equals(loadedTypes);
      out.put("originalTypes", originalTypes);
      out.put("loadedTypes", loadedTypes);
      out.put("typesMatch", typesMatch);

      // Content check — user message text should round-trip
      Message loadedUser =
          sc.history().stream().filter(m -> m instanceof UserMessage).findFirst().orElse(null);
      boolean userTextRoundTrips =
          loadedUser instanceof UserMessage um
              && "Compute the sum of [10, 20, 30].".equals(um.getText());
      out.put("userTextRoundTrips", userTextRoundTrips);

      // Tool call round-trip check
      AssistantMessage loadedAsst =
          sc.history().stream()
              .filter(m -> m instanceof AssistantMessage)
              .map(m -> (AssistantMessage) m)
              .findFirst()
              .orElse(null);
      boolean toolCallRoundTrips =
          loadedAsst != null
              && loadedAsst.getToolCalls() != null
              && loadedAsst.getToolCalls().size() == 1
              && "readFile".equals(loadedAsst.getToolCalls().get(0).name())
              && loadedAsst.getToolCalls().get(0).arguments().contains("numbers.txt");
      out.put("toolCallRoundTrips", toolCallRoundTrips);

      // Tool response round-trip check
      ToolResponseMessage loadedTrm =
          sc.history().stream()
              .filter(m -> m instanceof ToolResponseMessage)
              .map(m -> (ToolResponseMessage) m)
              .findFirst()
              .orElse(null);
      boolean toolResponseRoundTrips =
          loadedTrm != null
              && loadedTrm.getResponses() != null
              && loadedTrm.getResponses().size() == 1
              && "tc_abc123".equals(loadedTrm.getResponses().get(0).id());
      out.put("toolResponseRoundTrips", toolResponseRoundTrips);

      // Delete + verify
      store.delete(ctx);
      boolean existsAfterDelete = store.exists(ctx);
      out.put("existsAfterDelete", existsAfterDelete);

      boolean ok =
          existsAfterSave
              && typesMatch
              && userTextRoundTrips
              && toolCallRoundTrips
              && toolResponseRoundTrips
              && !existsAfterDelete;
      out.put("ok", ok);
      return out;
    } finally {
      deleteTree(workspace);
    }
  }

  private static List<Message> buildTestHistory() {
    return List.of(
        new SystemMessage("You are a test agent."),
        new UserMessage("Compute the sum of [10, 20, 30]."),
        AssistantMessage.builder()
            .content("I'll read numbers.txt first.")
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "tc_abc123", "function", "readFile", "{\"path\":\"numbers.txt\"}")))
            .build(),
        ToolResponseMessage.builder()
            .responses(
                List.of(
                    new ToolResponseMessage.ToolResponse(
                        "tc_abc123", "readFile", "{\"ok\":true,\"content\":\"10\\n20\\n30\\n\"}")))
            .build());
  }

  private static String simpleType(Message m) {
    if (m instanceof SystemMessage) return "system";
    if (m instanceof UserMessage) return "user";
    if (m instanceof AssistantMessage) return "assistant";
    if (m instanceof ToolResponseMessage) return "tool";
    return "unknown";
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
