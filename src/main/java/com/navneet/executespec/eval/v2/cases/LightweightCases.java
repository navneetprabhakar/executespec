package com.navneet.executespec.eval.v2.cases;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.eval.v2.TierEvalCase;
import com.navneet.executespec.router.AgentTier;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

/**
 * Production-shaped LIGHTWEIGHT-tier eval cases.
 *
 * <p>Mirrors {@link com.navneet.executespec.agent.SmartClassifierAgent}'s invocation shape:
 * synthetic "JSON-only spec classifier" system message + the verbatim classpath {@code
 * classifier.md} body with the production variables substituted. No tools — the classifier emits
 * raw JSON.
 *
 * <p>Three cases cover the three production input bands the classifier actually has to discriminate
 * between: a SINGLE_SHOT spec, a MEDIUM REST + auth + DB spec, and a LEGACY cross-cutting migration
 * spec. Each carries the EXPECTED strategy in its name + validator so we can score whether the
 * model classified correctly.
 */
public final class LightweightCases {

  private LightweightCases() {}

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public static List<TierEvalCase> all() {
    return List.of(singleShotCase(), mediumCase(), legacyCase());
  }

  private static TierEvalCase singleShotCase() {
    String spec =
        "Add a /healthz endpoint to the existing Spring Boot service that returns {\"ok\": true}.";
    return classifierCase(
        "lightweight-classify-single-shot", spec, "SINGLE_SHOT", "single-feature");
  }

  private static TierEvalCase mediumCase() {
    String spec =
        """
        Build a small TODO REST API in Node.js with the following:
        - Express + Postgres + Prisma
        - /api/todos GET/POST/PATCH/DELETE
        - Bearer-token auth (mint via /auth/login against a fixed admin user)
        - Joi validation on all request bodies
        - Jest tests covering each endpoint
        - A Dockerfile + docker-compose with postgres
        - A README with run instructions
        """;
    return classifierCase("lightweight-classify-medium", spec, "MEDIUM", "multi-component");
  }

  private static TierEvalCase legacyCase() {
    String spec =
        """
Migrate our payments stack from synchronous Stripe charges to an event-driven model using AWS SQS.
Every checkout, refund, and webhook handler across the api, admin, and web repos must switch from
direct Stripe API calls to a producer-consumer pattern: producers enqueue a job into payments-queue,
a worker consumes it, idempotency keys carry through end-to-end, the dashboard shows live job state,
and we keep PCI-DSS scope unchanged. Backfill the last 30 days of charges into the new event store
so historical reports keep working. Update SDKs in 3 backend services + 1 mobile app.
""";
    return classifierCase("lightweight-classify-legacy", spec, "LEGACY", "cross-cutting migration");
  }

  private static TierEvalCase classifierCase(
      String name, String spec, String expectedStrategy, String description) {
    String classifierBody = loadClassifierPrompt(spec);
    List<Message> messages =
        List.of(
            new SystemMessage(
                "You are a JSON-only spec classifier. Respond with one JSON object and nothing"
                    + " else."),
            new UserMessage(classifierBody));
    return new TierEvalCase() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public AgentTier tier() {
        return AgentTier.LIGHTWEIGHT;
      }

      @Override
      public List<Message> messages() {
        return messages;
      }

      @Override
      public List<ToolCallback> tools() {
        return List.of();
      }

      @Override
      public boolean requiresToolCall() {
        return false;
      }

      @Override
      public ValidationVerdict validate(
          String assistantText, String toolCallName, String toolCallArgsJson) {
        if (assistantText == null || assistantText.isBlank()) {
          return new ValidationVerdict(0, "empty response");
        }
        String trimmed = stripCodeFences(assistantText.trim());
        JsonNode node;
        try {
          node = MAPPER.readTree(trimmed);
        } catch (Exception e) {
          return new ValidationVerdict(
              0, "response not parseable JSON: " + truncate(e.getMessage(), 80));
        }
        if (!node.isObject()) {
          return new ValidationVerdict(0, "response root not a JSON object");
        }
        if (!node.has("strategy") || !node.get("strategy").isTextual()) {
          return new ValidationVerdict(20, "missing strategy field");
        }
        String strategy = node.get("strategy").asText();
        List<String> validValues = List.of("SINGLE_SHOT", "SMALL", "MEDIUM", "LARGE", "LEGACY");
        if (!validValues.contains(strategy)) {
          return new ValidationVerdict(
              40, "strategy not in enum: '" + strategy + "' (" + description + ")");
        }
        // Correctness band: exact match = 100, neighbor band = 70, far = 40.
        int distance = distanceBetween(strategy, expectedStrategy, validValues);
        int correctness =
            switch (distance) {
              case 0 -> 100;
              case 1 -> 70;
              case 2 -> 50;
              default -> 30;
            };
        return new ValidationVerdict(
            correctness, "classified " + strategy + " (expected " + expectedStrategy + ")");
      }
    };
  }

  private static String loadClassifierPrompt(String spec) {
    String body;
    try {
      body =
          new ClassPathResource("prompts/classifier.md").getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("classifier.md missing from classpath", e);
    }
    // Substitute the same variables the production PromptAssembler would.
    return body.replace("{{HEURISTIC_VERDICT}}", "SMALL")
        .replace("{{REPO_COUNT}}", "1")
        .replace("{{REPO_NAMES}}", "(none)")
        .replace("{{PROJECT_TYPE}}", "unknown")
        .replace("{{PROJECT_MEMORY}}", "(no project memory yet)")
        .replace("{{SPEC}}", spec);
  }

  private static String stripCodeFences(String s) {
    if (s.startsWith("```")) {
      int firstNl = s.indexOf('\n');
      if (firstNl < 0) return s;
      String body = s.substring(firstNl + 1);
      if (body.endsWith("```")) {
        body = body.substring(0, body.length() - 3);
      }
      return body.trim();
    }
    return s;
  }

  private static int distanceBetween(String a, String b, List<String> ordered) {
    int ai = ordered.indexOf(a);
    int bi = ordered.indexOf(b);
    if (ai < 0 || bi < 0) return Integer.MAX_VALUE;
    return Math.abs(ai - bi);
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
