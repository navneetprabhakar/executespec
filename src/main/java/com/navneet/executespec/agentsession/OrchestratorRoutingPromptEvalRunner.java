package com.navneet.executespec.agentsession;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Optional live model eval for the orchestrator routing prompts.
 *
 * <p>This is deliberately separate from the always-on deterministic corpus test. CI can run the
 * corpus without provider keys; operators can invoke this runner when credentials are available.
 */
@Service
@RequiredArgsConstructor
public class OrchestratorRoutingPromptEvalRunner {

  private final LlmRouter llmRouter;
  private final ModelRegistry modelRegistry;
  private final ObjectMapper objectMapper;
  private final OrchestratorRoutingPromptCatalog promptCatalog;

  public EvalReport run(List<String> providers, Integer limit) {
    List<OrchestratorRoutingCorpus.Row> rows = OrchestratorRoutingCorpus.load(objectMapper);
    if (limit != null && limit > 0 && limit < rows.size()) {
      rows = rows.subList(0, limit);
    }
    List<String> providerList =
        providers == null || providers.isEmpty()
            ? List.of("openai", "anthropic", "gemini", "deepseek")
            : providers;
    return runWithClient(
        rows, providerList, (provider, row) -> callLiveProvider(provider, row), Instant.now());
  }

  public EvalReport runWithClient(
      List<OrchestratorRoutingCorpus.Row> rows,
      List<String> providers,
      RoutingModelClient client,
      Instant startedAt) {
    long startMs = System.currentTimeMillis();
    List<RowResult> results = new ArrayList<>();
    for (String provider : providers) {
      for (OrchestratorRoutingCorpus.Row row : rows) {
        ModelOutput output;
        try {
          output = client.classify(provider, row);
        } catch (RuntimeException e) {
          results.add(
              RowResult.schemaFailure(
                  row.id(),
                  provider,
                  "?",
                  row.expectedRoutes(),
                  row.forbidRun(),
                  row.severity(),
                  "client_error: " + e.getMessage(),
                  0));
          continue;
        }
        results.add(score(provider, row, output));
      }
    }
    List<ProviderSummary> summaries = summarize(results);
    String evaluationId = "orchestrator-routing-eval-" + UUID.randomUUID();
    long durationMs = System.currentTimeMillis() - startMs;
    return new EvalReport(
        evaluationId,
        startedAt,
        durationMs,
        results,
        summaries,
        renderMarkdown(evaluationId, startedAt, durationMs, results, summaries));
  }

  private static String renderMarkdown(
      String evaluationId,
      Instant startedAt,
      long durationMs,
      List<RowResult> results,
      List<ProviderSummary> summaries) {
    StringBuilder out = new StringBuilder();
    out.append("# Orchestrator Routing Eval - ").append(startedAt).append("\n\n");
    out.append("Evaluation ID: `").append(evaluationId).append("`\n\n");
    out.append("Duration: ").append(durationMs / 1000).append("s\n\n");
    out.append("Rows: ").append(results.size()).append("\n\n");
    out.append("## Provider Summary\n\n");
    out.append(
        "| Provider | Rows | Schema failures | Run false positives | Dangerous-to-run |"
            + " Ambiguous-to-run | Accuracy |\n");
    out.append("|---|---:|---:|---:|---:|---:|---:|\n");
    for (ProviderSummary summary : summaries) {
      out.append("| ")
          .append(escapeCell(summary.provider()))
          .append(" | ")
          .append(summary.total())
          .append(" | ")
          .append(summary.schemaFailures())
          .append(" | ")
          .append(summary.runFalsePositives())
          .append(" | ")
          .append(summary.dangerousToRun())
          .append(" | ")
          .append(summary.ambiguousToRun())
          .append(" | ")
          .append(String.format(Locale.ROOT, "%.1f%%", summary.routeAccuracyPct()))
          .append(" |\n");
    }
    out.append("\n## Failed Rows\n\n");
    out.append("| Row | Provider | Model | Route | Expected | Reason |\n");
    out.append("|---|---|---|---|---|---|\n");
    List<RowResult> failed =
        results.stream()
            .filter(
                row ->
                    !row.schemaValid()
                        || !row.matched()
                        || row.runFalsePositive()
                        || row.dangerousToRun()
                        || row.ambiguousToRun())
            .toList();
    if (failed.isEmpty()) {
      out.append("| - | - | - | - | - | none |\n");
    } else {
      for (RowResult row : failed) {
        out.append("| ")
            .append(escapeCell(row.rowId()))
            .append(" | ")
            .append(escapeCell(row.provider()))
            .append(" | ")
            .append(escapeCell(row.model()))
            .append(" | ")
            .append(escapeCell(row.route()))
            .append(" | ")
            .append(escapeCell(String.join(",", row.expectedRoutes())))
            .append(" | ")
            .append(
                escapeCell(row.failureReason() == null ? "route_mismatch" : row.failureReason()))
            .append(" |\n");
      }
    }
    return out.toString();
  }

  private static String escapeCell(String value) {
    if (value == null || value.isBlank()) {
      return "-";
    }
    return value.replace("|", "\\|").replace("\n", " ");
  }

  private ModelOutput callLiveProvider(String provider, OrchestratorRoutingCorpus.Row row) {
    String normalized = provider.toLowerCase(Locale.ROOT);
    String model = modelRegistry.lightweightModelFor(normalized);
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException(
          "no lightweight model configured for provider " + provider);
    }
    String runId = "orchestrator-routing-eval-" + UUID.randomUUID();
    ExecutionContext ctx = new ExecutionContext(runId, row.id(), Path.of("/tmp"));
    long started = System.currentTimeMillis();
    ChatResponse response =
        llmRouter.call(
            LlmRequest.builder()
                .provider(normalized)
                .model(model)
                .messages(
                    List.of(
                        new SystemMessage(promptCatalog.fullPromptFor(normalized)),
                        new UserMessage(userPrompt(row))))
                .context(ctx)
                .temperature(0.0)
                .maxTokens(900)
                .toolsOverride(List.of())
                .agentRole("OrchestratorRoutingPromptEval")
                .stepId("orchestrator-routing." + row.id())
                .build());
    String text =
        response == null || response.getResult() == null || response.getResult().getOutput() == null
            ? ""
            : response.getResult().getOutput().getText();
    return new ModelOutput(model, text == null ? "" : text, System.currentTimeMillis() - started);
  }

  private String userPrompt(OrchestratorRoutingCorpus.Row row) {
    try {
      return "Classify this orchestrator turn. Return only JSON.\n\n"
          + objectMapper.writeValueAsString(
              Map.of(
                  "message",
                  row.message(),
                  "context",
                  row.context() == null ? Map.of() : row.context()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("failed to serialize routing eval row " + row.id(), e);
    }
  }

  private RowResult score(String provider, OrchestratorRoutingCorpus.Row row, ModelOutput output) {
    ModelDecision decision;
    try {
      decision = objectMapper.readValue(output.rawJson(), ModelDecision.class);
    } catch (Exception e) {
      return RowResult.schemaFailure(
          row.id(),
          provider,
          output.model(),
          row.expectedRoutes(),
          row.forbidRun(),
          row.severity(),
          "invalid_json: " + e.getMessage(),
          output.latencyMs());
    }
    if (decision.route() == null
        || !OrchestratorRoutingPromptCatalog.ROUTES.contains(decision.route())
        || decision.reasonCode() == null
        || decision.reasonCode().isBlank()
        || decision.confidence() == null
        || decision.requiresUserConfirmation() == null
        || decision.negativeEvidence() == null) {
      return RowResult.schemaFailure(
          row.id(),
          provider,
          output.model(),
          row.expectedRoutes(),
          row.forbidRun(),
          row.severity(),
          "schema_invalid",
          output.latencyMs());
    }
    boolean matched = row.expectedRoutes().contains(decision.route());
    boolean runFalsePositive = row.forbidRun() && "CODE_RUN_REQUIRED".equals(decision.route());
    boolean dangerousToRun =
        ("security".equalsIgnoreCase(row.severity()) || row.id().startsWith("security-"))
            && "CODE_RUN_REQUIRED".equals(decision.route());
    boolean ambiguousToRun =
        row.id().startsWith("ambiguous-") && "CODE_RUN_REQUIRED".equals(decision.route());
    return new RowResult(
        row.id(),
        provider,
        output.model(),
        decision.route(),
        row.expectedRoutes(),
        true,
        matched,
        runFalsePositive,
        dangerousToRun,
        ambiguousToRun,
        null,
        output.latencyMs());
  }

  private static List<ProviderSummary> summarize(List<RowResult> results) {
    return results.stream()
        .collect(java.util.stream.Collectors.groupingBy(RowResult::provider))
        .entrySet()
        .stream()
        .map(
            entry -> {
              List<RowResult> rows = entry.getValue();
              int total = rows.size();
              long schemaFailures = rows.stream().filter(r -> !r.schemaValid()).count();
              long runFalsePositives = rows.stream().filter(RowResult::runFalsePositive).count();
              long dangerousToRun = rows.stream().filter(RowResult::dangerousToRun).count();
              long ambiguousToRun = rows.stream().filter(RowResult::ambiguousToRun).count();
              long matched = rows.stream().filter(RowResult::matched).count();
              double accuracy = total == 0 ? 0.0 : (matched * 100.0) / total;
              return new ProviderSummary(
                  entry.getKey(),
                  total,
                  schemaFailures,
                  runFalsePositives,
                  dangerousToRun,
                  ambiguousToRun,
                  accuracy);
            })
        .sorted(java.util.Comparator.comparing(ProviderSummary::provider))
        .toList();
  }

  @FunctionalInterface
  public interface RoutingModelClient {
    ModelOutput classify(String provider, OrchestratorRoutingCorpus.Row row);
  }

  public record ModelOutput(String model, String rawJson, long latencyMs) {}

  public record ModelDecision(
      String route,
      Double confidence,
      String reasonCode,
      Boolean requiresUserConfirmation,
      List<String> negativeEvidence) {}

  public record EvalReport(
      String evaluationId,
      Instant startedAt,
      long durationMs,
      List<RowResult> results,
      List<ProviderSummary> summaries,
      String markdownReport) {}

  public record RowResult(
      String rowId,
      String provider,
      String model,
      String route,
      List<String> expectedRoutes,
      boolean schemaValid,
      boolean matched,
      boolean runFalsePositive,
      boolean dangerousToRun,
      boolean ambiguousToRun,
      String failureReason,
      long latencyMs) {

    static RowResult schemaFailure(
        String rowId,
        String provider,
        String model,
        List<String> expectedRoutes,
        boolean forbidRun,
        String severity,
        String reason,
        long latencyMs) {
      return new RowResult(
          rowId,
          provider,
          model,
          null,
          expectedRoutes,
          false,
          false,
          false,
          false,
          false,
          reason,
          latencyMs);
    }
  }

  public record ProviderSummary(
      String provider,
      int total,
      long schemaFailures,
      long runFalsePositives,
      long dangerousToRun,
      long ambiguousToRun,
      double routeAccuracyPct) {}
}
