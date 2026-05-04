package com.navneet.executespec.v2.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.navneet.executespec.v2.agent.WorkerAgent;
import com.navneet.executespec.v2.agent.WorkerResult;
import com.navneet.executespec.v2.agent.WorkerTaskRequest;
import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 Phase 3 verification endpoint.
 *
 * <p>Runs a real micro-task end-to-end through {@link WorkerAgent}: the agent must read an input
 * file, perform a small computation, write the result to a new file, and call {@code completeTask}.
 * Verifies:
 *
 * <ul>
 *   <li>Multi-turn loop works (≥ 2 LLM calls — one for read, one for write, one for completeTask)
 *   <li>Tool result feedback threading is correct (model uses the readFile result)
 *   <li>Completion detection via the structured {@code completeTask} signal
 *   <li>Output file ends up on disk with the expected content
 * </ul>
 *
 * <p>Default model is {@code claude-haiku-4-5-20251001} for fast iteration; override via {@code
 * ?provider=...&model=...} query params to test other matrix members.
 */
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class WorkerTestController {

  private final WorkerAgent worker;

  @GetMapping("/worker")
  public Map<String, Object> testWorker(
      @RequestParam(defaultValue = "anthropic") String provider,
      @RequestParam(defaultValue = "claude-haiku-4-5-20251001") String model)
      throws IOException {
    Path workspace = Files.createTempDirectory("v2-worker-test-");
    try {
      // Seed the input file the worker will be told to read.
      Files.writeString(workspace.resolve("numbers.txt"), "10\n20\n30\n");

      ExecutionContext ctx =
          new ExecutionContext("worker-test-run", "worker-test-task", workspace.toRealPath());

      String systemPrompt =
          """
          You are a code-execution agent operating in a sandboxed workspace. You have
          access to filesystem and shell tools. Use the tools to complete the user's
          task — do not explain or hedge. When the task is complete, call the
          completeTask tool with a brief summary. Do not output any text that is not
          needed to complete the task.
          """;
      String userPrompt =
          """
          Task: Read the file 'numbers.txt' (one integer per line). Compute the sum of
          those integers. Create a new file 'sum.txt' containing only the resulting
          sum as a string (no other content, no trailing newline required).
          Then call completeTask with a one-line summary.
          """;

      WorkerResult result =
          worker.run(
              WorkerTaskRequest.builder()
                  .provider(provider)
                  .model(model)
                  .systemPrompt(systemPrompt)
                  .userPrompt(userPrompt)
                  .context(ctx)
                  .maxIterations(15)
                  .temperature(0.0)
                  .maxTokensPerCall(1024)
                  .build());

      // Verify the output file ended up correct.
      Path sumFile = workspace.resolve("sum.txt");
      String sumOnDisk = Files.exists(sumFile) ? Files.readString(sumFile).trim() : null;
      boolean sumCorrect = "60".equals(sumOnDisk);

      Map<String, Object> out = new LinkedHashMap<>();
      out.put("provider", provider);
      out.put("model", model);
      out.put("workspace", workspace.toString());
      out.put("outcome", result.outcome().name());
      out.put("iterations", result.iterations());
      out.put("toolCallsDispatched", result.toolCallsDispatched());
      out.put("totalLatencyMs", result.totalLatencyMs());
      out.put("completionSummary", result.completionSummary());
      out.put("finalAssistantText", result.finalAssistantText());
      out.put("toolCallLog", result.toolCallLog());
      out.put("sumFileExists", sumOnDisk != null);
      out.put("sumOnDisk", sumOnDisk);
      out.put("sumCorrect", sumCorrect);
      out.put("ok", result.outcome() == WorkerResult.Outcome.COMPLETED && sumCorrect);
      return out;
    } finally {
      deleteTree(workspace);
    }
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
