package com.navneet.executespec.v2.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.v2.observability.V2Metrics;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Unit tests for the heuristic fast-path inside {@link SmartClassifierAgent}, focused on the
 * [Q-NEW-04] endpoint-count override.
 *
 * <p>Background (audit-output/CLASSIFIER_DEEPDIVE.md): the audit's Express to-do spec ({@code
 * scripts/v2-eval-specs/medium.txt}, 698 chars) was bucketed as SMALL by the original heuristic —
 * correct on length alone, but a 5-endpoint API spec is structurally MEDIUM. The new
 * endpoint-counter (≥4 distinct {@code POST/GET/PUT/DELETE/PATCH /…} lines) bumps SMALL → MEDIUM
 * regardless of length.
 *
 * <p>These tests exercise the heuristic via reflection on the private {@code runHeuristic} method.
 * Reflection is normally a smell, but {@code runHeuristic} is intentionally private (the public
 * {@code classify} does cache + LLM verification on top), and these tests need to pin the heuristic
 * in isolation from the LLM and Redis collaborators.
 */
class SmartClassifierHeuristicTest {

  @SuppressWarnings("unchecked")
  private static SmartClassifierAgent newAgent() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
    when(ops.get(anyString())).thenReturn(null);

    return new SmartClassifierAgent(
        mock(LlmRouterV2.class),
        mock(ModelResolver.class),
        redis,
        mock(PromptAssembler.class),
        new ObjectMapper(),
        mock(V2Metrics.class));
  }

  private static Object runHeuristic(SmartClassifierAgent agent, String spec, int repoCount)
      throws Exception {
    Method m =
        SmartClassifierAgent.class.getDeclaredMethod("runHeuristic", String.class, int.class);
    m.setAccessible(true);
    return m.invoke(agent, spec, repoCount);
  }

  private static String reasoningFor(Object verdict) throws Exception {
    return (String) verdict.getClass().getDeclaredMethod("reasoning").invoke(verdict);
  }

  private static String strategyFor(Object verdict) throws Exception {
    Object strategy = verdict.getClass().getDeclaredMethod("strategy").invoke(verdict);
    return ((Enum<?>) strategy).name();
  }

  @Test
  void mediumExpressTodoSpec_5Endpoints_bumpsToMedium() throws Exception {
    // The exact spec text from scripts/v2-eval-specs/medium.txt (698 chars).
    String spec =
        """
Build a minimal Express (Node.js) REST API for a to-do list with in-memory storage.

Endpoints:
- POST /todos — body {title}, returns 201 with the created todo {id, title, completed:false, createdAt}
- GET /todos — returns all todos, sorted newest first
- GET /todos/:id — returns one todo or 404
- PATCH /todos/:id — body {title?, completed?}, returns the updated todo or 404
- DELETE /todos/:id — returns 204 or 404

Constraints:
- Express 4, no external database
- Use UUIDs for ids
- Input validation: title required and non-empty on POST; reject unknown body fields on PATCH
- Include a README with install + run instructions (npm install, npm start)
- Listen on port from PORT env var or 3000
""";

    Object verdict = runHeuristic(newAgent(), spec, 1);

    assertThat(strategyFor(verdict))
        .as("5 distinct HTTP-verb endpoint lines should bump SMALL → MEDIUM")
        .isEqualTo("MEDIUM");
    assertThat(reasoningFor(verdict)).contains("HTTP endpoints");
  }

  @Test
  void shortSpecWith3Endpoints_staysSmall() throws Exception {
    // 3 endpoints isn't enough to override; the spec stays SMALL by length.
    String spec =
        """
        Build a tiny POST/GET/PATCH counter API.

        Endpoints:
        - POST /counter — increment and return new value
        - GET /counter — return current value
        - PATCH /counter — body {value:int}, set value
        """;

    Object verdict = runHeuristic(newAgent(), spec, 1);

    assertThat(strategyFor(verdict)).isEqualTo("SMALL");
  }

  @Test
  void mediumLengthSpecWith4Endpoints_returnsMedium() throws Exception {
    // 4-endpoint spec at ~1500 chars (under the 2000 LARGE cap).
    // Confirms the existing length-based MEDIUM bucket still works
    // alongside the endpoint-count override.
    StringBuilder midSpec = new StringBuilder();
    midSpec.append("Build a multi-resource API with users + sessions + audit.\n\nEndpoints:\n");
    midSpec.append("- POST /users — create user\n");
    midSpec.append("- GET /users/:id — fetch user\n");
    midSpec.append("- DELETE /sessions/:token — revoke session\n");
    midSpec.append("- GET /audit — list audit events\n\n");
    // Pad to ~1500 chars (under the 2000 LARGE boundary).
    for (int i = 0; i < 18; i++) {
      midSpec
          .append("- Constraint ")
          .append(i)
          .append(": validate inputs and return clear errors.\n");
    }

    Object verdict = runHeuristic(newAgent(), midSpec.toString(), 1);

    assertThat(strategyFor(verdict)).isEqualTo("MEDIUM");
  }

  @Test
  void over2000CharSpecGoesLargeEvenWithEndpoints() throws Exception {
    // Endpoint override is capped at ≤2000 chars by design — bigger
    // specs really do tend to be LARGE (lots of supporting context).
    StringBuilder bigSpec = new StringBuilder();
    bigSpec.append("Build a multi-resource API.\n\nEndpoints:\n");
    bigSpec.append("- POST /users\n- GET /users/:id\n- DELETE /sessions/:token\n- GET /audit\n\n");
    for (int i = 0; i < 50; i++) {
      bigSpec
          .append("- Constraint ")
          .append(i)
          .append(": include thoughtful validation, error handling, and clear ")
          .append("failure modes for downstream callers across the API surface.\n");
    }

    Object verdict = runHeuristic(newAgent(), bigSpec.toString(), 1);

    assertThat(strategyFor(verdict)).isEqualTo("LARGE");
  }

  @Test
  void noEndpointsAndShortSpec_staysSmall() throws Exception {
    // Library spec without HTTP endpoints — the new override doesn't fire,
    // so length-only bucketing applies. Closes a regression risk.
    String spec =
        """
        Implement a small recursive-descent parser for a calculator
        grammar with +, -, *, /, parentheses, and floating-point literals.
        Include a small REPL that reads expressions from stdin and prints
        results. Add unit tests for at least 10 example expressions.
        Use idiomatic TypeScript with strict mode enabled.
        """;

    Object verdict = runHeuristic(newAgent(), spec, 1);

    assertThat(strategyFor(verdict)).isEqualTo("SMALL");
  }

  @Test
  void singleShotShortFix_uses150CharFastPath() throws Exception {
    Object verdict = runHeuristic(newAgent(), "Add a /healthz route returning 200 OK.", 1);

    assertThat(strategyFor(verdict)).isEqualTo("SINGLE_SHOT");
  }

  @Test
  void multiRepoTriggersLargeRegardlessOfEndpointCount() throws Exception {
    // 3 repos triggers the ≥3-repo branch BEFORE the endpoint-count check
    // — confirms ordering. (Endpoint-count is a SMALL→MEDIUM signal, not
    // a MEDIUM→LARGE one; the multi-repo branch wins.)
    String spec = "Add - POST /a - GET /a - DELETE /a - PATCH /a across 3 services.";
    Object verdict = runHeuristic(newAgent(), spec, 3);

    assertThat(strategyFor(verdict)).isEqualTo("LARGE");
  }

  @Test
  void endpointPattern_isCaseInsensitive() throws Exception {
    // Lowercase verbs (sometimes seen in casual specs) still count.
    String spec =
        """
        Endpoints:
        - post /todos
        - get /todos
        - delete /todos/:id
        - patch /todos/:id
        """;
    Object verdict = runHeuristic(newAgent(), spec, 1);

    assertThat(strategyFor(verdict)).isEqualTo("MEDIUM");
  }

  @Test
  void endpointPattern_handlesAsteriskBullet() throws Exception {
    // Asterisk-bulleted endpoints (Markdown alt) match too.
    String spec =
        """
        * POST /a
        * GET /a
        * DELETE /a
        * PATCH /a
        """;
    Object verdict = runHeuristic(newAgent(), spec, 1);

    assertThat(strategyFor(verdict)).isEqualTo("MEDIUM");
  }

  @Test
  void endpointPattern_ignoresInlineMentions() throws Exception {
    // "POST /todos" inline in prose (not at line start) should NOT count
    // — only line-anchored declarations count. Confirms we're not
    // over-counting on prose mentions.
    String spec =
        "Use POST /todos to create. Use GET /todos to fetch. "
            + "Use PATCH /todos/:id to update. Use DELETE /todos/:id to remove. "
            + "These verbs are mentioned inline in narrative prose.";
    Object verdict = runHeuristic(newAgent(), spec, 1);

    assertThat(strategyFor(verdict)).isEqualTo("SMALL");
  }
}
